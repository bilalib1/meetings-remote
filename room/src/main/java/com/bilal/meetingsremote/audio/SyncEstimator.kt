package com.bilal.meetingsremote.audio

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates how far the camera pipeline delays "the world" relative to the
 * tablet mic, by GCC-PHAT cross-correlation of the two audio streams (plan
 * 2026-07-10-audio-and-av-sync — chosen over ML lip-sync: ~ms accuracy,
 * ~100 ms CPU per estimate, no model, works during normal speech).
 *
 * Both streams are laid onto one wall-clock timeline:
 *  - tablet mic blocks arrive with their capture time (near-zero latency);
 *  - camera audio arrives with the time its equal-PTS *video* frame left for
 *    Zoom (mux-aligned mapping done in FfmpegVideoSource).
 * A sound at wall time t therefore shows up in the camera ring at t + L where
 * L is the whole camera pipeline latency (encode, network, decode) — the
 * correlation peak lag IS the mic delay to apply.
 */
class SyncEstimator(private val onOffset: (ms: Int) -> Unit) {

    private val epochNs = AtomicLong(0)
    private val micRing = TimelineRing(epochNs)
    private val camRing = TimelineRing(epochNs)

    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var forceNow = false

    // Observability.
    @Volatile private var lastResult = "none yet"
    @Volatile private var applied = -1
    private var lastEstimateEnd = 0L
    private val history = ArrayDeque<Double>()

    /** Tablet mic tap: 48 kHz mono block + capture wall ns (from MicAudioSource). */
    fun onMicAudio(block: ShortArray, wallNs: Long) {
        // Decimate 48 k -> 16 k (mean of 3 — crude low-pass, fine for
        // correlation of speech).
        val out = ShortArray(block.size / 3)
        for (i in out.indices) {
            val j = i * 3
            out[i] = ((block[j] + block[j + 1] + block[j + 2]) / 3).toShort()
        }
        micRing.write(out, out.size, wallNs)
    }

    /** Camera audio tap: 16 kHz mono + mapped wall ns (from FfmpegVideoSource). */
    fun onCameraAudio(samples: ShortArray, n: Int, wallNs: Long) {
        camRing.write(samples, n, wallNs)
        // Liveness: track when the camera track last carried real signal, so a
        // present-but-muted/dead mic (packets flow but they're near-silence) is
        // told apart from a working one. Cheap peak over the block.
        var peak = 0
        for (i in 0 until n) { val a = kotlin.math.abs(samples[i].toInt()); if (a > peak) peak = a }
        if (peak > SILENCE_PEAK) lastCamSignalNs = System.nanoTime()
    }

    @Volatile private var lastCamSignalNs = 0L

    fun start() {
        if (running) return
        running = true
        startedNs = System.nanoTime()
        worker = thread(name = "av-sync") { loop() }
    }

    @Volatile private var startedNs = 0L
    @Volatile private var lastConfidentNs = 0L

    /**
     * Does GCC-PHAT own sync (so the ML lip-sync fallback should idle)? This
     * is the cascade decision for a camera WITH an audio track — false means
     * the track is dead/muted/uncorrelated and ML should take over. Cases:
     *  - first few s: hold, camera audio may still be arriving;
     *  - locked recently: owns (held through a couple missed cycles);
     *  - mic is live but not yet locked: owns only through startup patience
     *    (its 30 s cycle re-tries); past that, uncorrelated audio → ML helps;
     *  - mic dead/muted (no recent signal) and no lock → false → ML engages
     *    fast (no 3-min wait).
     */
    fun gccOwnsSync(): Boolean {
        val now = System.nanoTime()
        if (startedNs != 0L && now - startedNs < INITIAL_HOLD_NS) return true
        if (lastConfidentNs != 0L && now - lastConfidentNs < CONFIDENT_HOLD_NS) return true
        if (lastCamSignalNs != 0L && startedNs != 0L &&
            now - startedNs < STARTUP_PATIENCE_NS) return true
        return false
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /** Test hook: run an estimate on the next loop tick. */
    fun estimateNow() { forceNow = true; worker?.interrupt() }

    fun stats(): String {
        val camLiveAgo = if (lastCamSignalNs == 0L) "never"
            else "${(System.nanoTime() - lastCamSignalNs) / 1_000_000_000L}s"
        return "running=$running owns=${gccOwnsSync()} camLive=$camLiveAgo " +
            "last=$lastResult appliedMs=$applied mic=${micRing.stats()} cam=${camRing.stats()}"
    }

    private fun loop() {
        Log.i(TAG, "estimator started")
        var next = System.nanoTime() + FIRST_AFTER_NS
        while (running) {
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            if (!running) break
            if (System.nanoTime() < next && !forceNow) continue
            forceNow = false
            next = System.nanoTime() + INTERVAL_NS
            runCatching { estimate() }
                .onFailure { Log.w(TAG, "estimate failed: $it") }
        }
        Log.i(TAG, "estimator stopped")
    }

    internal fun estimate() { // internal for the unit test
        // Common window: end a little behind the freshest sample both rings
        // have, so late camera blocks can't land inside it after we copy.
        val end = minOf(micRing.lastIdx(), camRing.lastIdx()) - RATE / 2
        if (end <= lastEstimateEnd) {
            lastResult = "stalled rings (end=$end) — skipped"
            return
        }
        lastEstimateEnd = end
        val start = end - WINDOW
        if (start - MAX_LAG < maxOf(micRing.firstIdx(), camRing.firstIdx())) {
            lastResult = "not enough buffered yet"
            return
        }
        val mic = micRing.read(start, WINDOW)
        val cam = camRing.read(start - MAX_LAG, WINDOW + 2 * MAX_LAG)
        val micRms = rms(mic)
        val camRms = rms(cam)
        if (micRms < MIN_RMS || camRms < MIN_RMS) {
            lastResult = "too quiet (micRms=%.0f camRms=%.0f)".format(micRms, camRms)
            Log.i(TAG, "skip: $lastResult")
            return
        }

        val t0 = System.nanoTime()
        val corr = gccPhat(mic, cam)
        // Peak over lag k ∈ [0, 2*MAX_LAG]; real lag = k - MAX_LAG samples.
        var peakK = 0
        var peak = Double.NEGATIVE_INFINITY
        for (k in 0..2 * MAX_LAG) if (corr[k] > peak) { peak = corr[k]; peakK = k }
        // Confidence: main peak vs the best peak ≥80 ms away, and vs the floor.
        // The guard is wide because room reverb spreads the true peak into a
        // ridge tens of ms across — its own shoulders aren't rival hypotheses
        // (measured 2026-07-10: sidelobes at ±30 ms of the main peak).
        var second = Double.NEGATIVE_INFINITY
        var sumSq = 0.0
        val guard = RATE * 80 / 1000
        for (k in 0..2 * MAX_LAG) {
            sumSq += corr[k] * corr[k]
            if (abs(k - peakK) > guard && corr[k] > second) second = corr[k]
        }
        val floor = sqrt(sumSq / (2 * MAX_LAG + 1))
        val peakRatio = peak / (second + 1e-12)
        val floorRatio = peak / (floor + 1e-12)
        // Parabolic sub-sample refinement.
        val frac = if (peakK in 1 until 2 * MAX_LAG) {
            val a = corr[peakK - 1]; val b = corr[peakK]; val c = corr[peakK + 1]
            val d = a - 2 * b + c
            if (abs(d) > 1e-12) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
        } else 0.0
        val offsetMs = (peakK + frac - MAX_LAG) * 1000.0 / RATE
        val ms = System.nanoTime() - t0
        // Top competing peaks (>25 ms apart) — tells ambiguity from bias when
        // the gate rejects.
        val tops = topPeaks(corr, guard)
        lastResult = "offset=%.1fms peakRatio=%.2f floorRatio=%.1f (%.0fms cpu) tops=%s"
            .format(offsetMs, peakRatio, floorRatio, ms / 1e6, tops)
        Log.i(TAG, "estimate: $lastResult micRms=%.0f camRms=%.0f".format(micRms, camRms))

        if (offsetMs < -250 || offsetMs > 5000) {
            Log.w(TAG, "offset ${offsetMs}ms outside sane range — rejected")
            return
        }
        if (peakRatio < MIN_PEAK_RATIO || floorRatio < MIN_FLOOR_RATIO) {
            // Borderline window (e.g. jittery transport smears the ridge).
            // Temporal consistency rescues it: three INDEPENDENT windows
            // agreeing within CONSISTENT_SPREAD_MS is evidence random
            // ambiguity can't fake — apply their median rather than leaving
            // the audio a second out of sync.
            if (floorRatio < 5.0) return
            recent.addLast(offsetMs)
            while (recent.size > 3) recent.removeFirst()
            if (recent.size == 3) {
                val sorted = recent.sorted()
                if (sorted[2] - sorted[0] <= CONSISTENT_SPREAD_MS) {
                    Log.i(TAG, "consistency accept: 3 windows within " +
                        "%.0fms -> median %.0fms".format(sorted[2] - sorted[0], sorted[1]))
                    apply(sorted[1])
                }
            }
            return
        }
        recent.clear()
        apply(offsetMs)
    }

    private fun apply(offsetMs: Double) {
        lastConfidentNs = System.nanoTime()
        synchronized(history) {
            history.addLast(offsetMs)
            while (history.size > 3) history.removeFirst()
            val median = history.sorted()[history.size / 2]
            val target = median.toInt().coerceAtLeast(0)
            if (applied < 0 || abs(target - applied) > APPLY_THRESHOLD_MS) {
                applied = target
                Log.i(TAG, "applying mic delay ${target}ms (median of ${history.size})")
                onOffset(target)
            }
        }
    }

    private val recent = ArrayDeque<Double>() // borderline-window offsets

    /**
     * GCC-PHAT: whitened cross-correlation of mic (len W) against cam
     * (len W+2L), zero-padded to N ≥ camLen so lags 0..2L are wrap-free.
     * Returns corr indexed by lag k.
     */
    private fun gccPhat(mic: DoubleArray, cam: DoubleArray): DoubleArray {
        var n = 1
        while (n < cam.size + 1) n = n shl 1
        val micRe = DoubleArray(n); val micIm = DoubleArray(n)
        val camRe = DoubleArray(n); val camIm = DoubleArray(n)
        mic.copyInto(micRe)
        cam.copyInto(camRe)
        fft(micRe, micIm, false)
        fft(camRe, camIm, false)
        // X = CAM * conj(MIC), PHAT-whitened.
        for (i in 0 until n) {
            val re = camRe[i] * micRe[i] + camIm[i] * micIm[i]
            val im = camIm[i] * micRe[i] - camRe[i] * micIm[i]
            val mag = sqrt(re * re + im * im) + 1e-12
            camRe[i] = re / mag
            camIm[i] = im / mag
        }
        fft(camRe, camIm, true)
        return camRe
    }

    /** Iterative in-place radix-2 FFT (inverse unnormalized is fine here). */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * Math.PI / len * (if (inverse) 1 else -1)
            val wRe = Math.cos(ang); val wIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** The 4 highest local peaks at least [guard] samples apart, as "lagMs:height". */
    private fun topPeaks(corr: DoubleArray, guard: Int): String {
        val picked = mutableListOf<Int>()
        val n = 2 * MAX_LAG
        while (picked.size < 4) {
            var bestK = -1
            var best = Double.NEGATIVE_INFINITY
            for (k in 0..n) {
                if (corr[k] > best && picked.all { abs(k - it) > guard }) {
                    best = corr[k]; bestK = k
                }
            }
            if (bestK < 0) break
            picked.add(bestK)
        }
        val h0 = corr[picked.first()]
        return picked.joinToString(" ") {
            "%.0fms:%.2f".format((it - MAX_LAG) * 1000.0 / RATE, corr[it] / h0)
        }
    }

    private fun rms(a: DoubleArray): Double {
        var s = 0.0
        for (v in a) s += v * v
        return sqrt(s / a.size)
    }

    /**
     * Mono s16 ring indexed by absolute sample position on the shared
     * wall-clock timeline: index = (wallNs - epoch) * RATE / 1e9. Writing at
     * the block's own anchor absorbs tap jitter; unwritten stretches read as
     * zeros (harmless to the correlation).
     */
    private class TimelineRing(private val epochNs: AtomicLong) {
        private val buf = ShortArray(RATE * RING_SECONDS)
        private var last = 0L    // absolute index just past the newest sample
        private val lock = Any()

        fun write(samples: ShortArray, n: Int, wallNs: Long) {
            epochNs.compareAndSet(0, wallNs)
            val start = (wallNs - epochNs.get()) * RATE / 1_000_000_000L
            if (start < 0) return
            synchronized(lock) {
                for (i in 0 until n) buf[((start + i) % buf.size).toInt()] = samples[i]
                if (start + n > last) last = start + n
            }
        }

        fun lastIdx(): Long = synchronized(lock) { last }
        fun firstIdx(): Long = synchronized(lock) { maxOf(0, last - buf.size) }

        fun read(from: Long, len: Int): DoubleArray {
            val out = DoubleArray(len)
            synchronized(lock) {
                for (i in 0 until len) {
                    val pos = from + i
                    out[i] = if (pos < 0 || pos >= last || pos < last - buf.size) 0.0
                             else buf[(pos % buf.size).toInt()].toDouble()
                }
            }
            return out
        }

        fun stats(): String = "last=${lastIdx()} (${lastIdx() / RATE}s)"
    }

    companion object {
        private const val TAG = "SyncEstimator"
        private const val RATE = 16_000
        private const val RING_SECONDS = 30
        private const val WINDOW = RATE * 6            // 6 s correlation window
        private const val MAX_LAG = RATE * 5 / 2       // ±2.5 s search
        private const val FIRST_AFTER_NS = 15_000_000_000L
        private const val INTERVAL_NS = 30_000_000_000L
        private const val MIN_RMS = 60.0               // s16 units; gate silence
        // Cascade timing (gccOwnsSync). INITIAL_HOLD covers camera-audio
        // arrival; CONFIDENT_HOLD spans ~3 estimate cycles so a couple of
        // missed locks don't hand off; STARTUP_PATIENCE bounds how long a
        // live-but-never-locking (uncorrelated) track keeps ML idle.
        private const val SILENCE_PEAK = 150           // s16 peak; below = silence
        private const val INITIAL_HOLD_NS = 8_000_000_000L
        private const val CONFIDENT_HOLD_NS = 90_000_000_000L
        private const val STARTUP_PATIENCE_NS = 75_000_000_000L
        // Calibrated 2026-07-10 vs measured distributions: ambiguous/garbage
        // windows peak-ratio ≈1.06–1.19; genuine speech locks ≈1.4–2.0 (with
        // the 80 ms guard). Median-of-3 + the 20 ms apply-hysteresis mop up
        // the occasional borderline accept.
        private const val MIN_PEAK_RATIO = 1.35
        private const val MIN_FLOOR_RATIO = 8.0
        private const val APPLY_THRESHOLD_MS = 20
        private const val CONSISTENT_SPREAD_MS = 250.0
    }
}
