package com.bilal.meetingsremote.audio.mlsync

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ML lip-sync AV-offset estimator — the fallback for cameras with no audio
 * track (plan 2026-07-10-audio-and-av-sync; user decision: ML, no flash).
 *
 * SyncNet (Chung & Zisserman 2016) exported to ONNX (validated against the
 * original torch pipeline, see docs/syncnet-preprocessing.md): a visual
 * branch embeds 5-frame 224×224 BGR face crops of the *decoded camera video*,
 * an audio branch embeds 0.2 s MFCC windows of the *tablet mic*; the offset
 * between mouth movement and speech is the argmin of the mean-L2 distance
 * curve over ±15 video frames (40 ms each) — that offset IS the camera
 * pipeline latency the mic delay must match.
 *
 * Runs one estimate per [INTERVAL_NS] on its own thread; face detection
 * (BlazeFace) localizes the crop every ~0.5 s on a helper thread. All video
 * work is skipped while [hasCamAudio] is true — GCC-PHAT owns sync then.
 */
class MlSyncEstimator(
    private val context: Context,
    private val hasCamAudio: () -> Boolean,
    private val onOffset: (ms: Int) -> Unit,
) {

    // ------------------------------------------------------------ mic audio
    // 16 kHz mono on the wall-clock timeline (same convention as SyncEstimator).
    private val aRing = ShortArray(16_000 * RING_SECONDS)
    private var aEpochNs = 0L
    private var aLast = 0L
    private val aLock = Any()

    fun onMicAudio(block48k: ShortArray, wallNs: Long) {
        val out = ShortArray(block48k.size / 3)
        for (i in out.indices) {
            val j = i * 3
            out[i] = ((block48k[j] + block48k[j + 1] + block48k[j + 2]) / 3).toShort()
        }
        synchronized(aLock) {
            if (aEpochNs == 0L) aEpochNs = wallNs
            val start = (wallNs - aEpochNs) * 16_000 / 1_000_000_000L
            if (start < 0) return
            for (i in out.indices) aRing[((start + i) % aRing.size).toInt()] = out[i]
            if (start + out.size > aLast) aLast = start + out.size
        }
    }

    private fun audioSegment(fromWallNs: Long, samples: Int): ShortArray? = synchronized(aLock) {
        if (aEpochNs == 0L) return null
        val from = (fromWallNs - aEpochNs) * 16_000 / 1_000_000_000L
        if (from < 0 || from + samples > aLast || from < aLast - aRing.size) return null
        ShortArray(samples) { aRing[((from + it) % aRing.size).toInt()] }
    }

    // ---------------------------------------------------------- video crops
    private class Crop(val bgr: ByteArray, val wallNs: Long) // 224*224*3

    private val crops = ArrayDeque<Crop>() // decode thread only (+estimate copy)
    private val cropsLock = Any()
    private val faceBox = AtomicReference<RectF?>(null)
    @Volatile private var lastFaceNs = 0L
    private var detLog = 0L

    // Latest downscaled frame for the detector thread.
    private val detectFrame = AtomicReference<Bitmap?>(null)
    private var frameCount = 0L

    /** ExternalVideoSource analysis tap; decode thread — keep it cheap. */
    fun onVideoFrame(buf: ByteBuffer, w: Int, h: Int) {
        if (hasCamAudio()) return
        val now = System.nanoTime()
        frameCount++
        if (frameCount % DETECT_EVERY == 0L) {
            detectFrame.set(i420ToBitmap(buf, w, h, 4))
        }
        if (frameCount % 100L == 0L) {
            Log.i(TAG, "frames=$frameCount ${w}x$h face=${faceBox.get()} " +
                "crops=${synchronized(cropsLock) { crops.size }}")
        }
        val box = faceBox.get() ?: return
        if (now - lastFaceNs > 3_000_000_000L) return // stale face — stop collecting
        val crop = cropBgr224(buf, w, h, box)
        synchronized(cropsLock) {
            crops.addLast(Crop(crop, now))
            while (crops.size > MAX_CROPS) crops.removeFirst()
        }
    }

    // -------------------------------------------------------------- control

    @Volatile private var running = false
    private var estThread: Thread? = null
    private var detThread: Thread? = null
    @Volatile var lastResult = "none yet"; private set

    fun start() {
        if (running) return
        running = true
        detThread = thread(name = "mlsync-face") { detectLoop() }
        estThread = thread(name = "mlsync-est") { estimateLoop() }
    }

    fun stop() {
        running = false
        detThread?.interrupt(); detThread = null
        estThread?.interrupt(); estThread = null
    }

    fun stats(): String = "running=$running crops=${synchronized(cropsLock) { crops.size }} " +
        "face=${faceBox.get() != null} last=$lastResult"

    // ---------------------------------------------------------- face detect

    private fun detectLoop() {
        val detector = runCatching {
            FaceDetector.createFromOptions(context,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath("blaze_face_short_range.tflite").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.5f)
                    .build())
        }.onFailure { Log.e(TAG, "face detector init failed: $it") }.getOrNull() ?: return
        while (running) {
            try { Thread.sleep(400) } catch (_: InterruptedException) { break }
            val bmp = detectFrame.getAndSet(null) ?: continue
            runCatching {
                val dets = detector.detect(BitmapImageBuilder(bmp).build()).detections()
                val det = dets.maxByOrNull { it.boundingBox().width() }
                if (det != null) {
                    val b = det.boundingBox()
                    // Back to full-res coords (bitmap was downscaled 4x).
                    faceBox.set(RectF(b.left * 4, b.top * 4, b.right * 4, b.bottom * 4))
                    lastFaceNs = System.nanoTime()
                    if (detLog++ % 20L == 0L) Log.i(TAG, "face @ ${faceBox.get()} (${dets.size} dets)")
                } else if (detLog++ % 20L == 0L) {
                    Log.i(TAG, "no face in ${bmp.width}x${bmp.height} frame")
                }
            }.onFailure { Log.w(TAG, "detect failed: $it") }
        }
        detector.close()
    }

    // ------------------------------------------------------------- estimate

    private fun estimateLoop() {
        val env = OrtEnvironment.getEnvironment()
        val (audioSess, visualSess) = runCatching {
            Pair(env.createSession(assetFile("syncnet_audio.onnx").absolutePath),
                 env.createSession(assetFile("syncnet_visual.onnx").absolutePath))
        }.onFailure { Log.e(TAG, "onnx session init failed: $it") }.getOrNull() ?: return
        Log.i(TAG, "onnx sessions ready")
        while (running) {
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            if (System.nanoTime() < nextEstimateNs) continue
            nextEstimateNs = System.nanoTime() + INTERVAL_NS
            if (hasCamAudio()) { lastResult = "idle (camera has audio)"; continue }
            runCatching { estimate(audioSess, visualSess) }
                .onFailure { Log.w(TAG, "estimate failed: $it"); lastResult = "failed: $it" }
        }
        audioSess.close(); visualSess.close()
    }

    @Volatile private var nextEstimateNs = System.nanoTime() + FIRST_AFTER_NS

    /** Test hook. */
    fun estimateNow() { nextEstimateNs = 0L }

    private fun estimate(audioSess: OrtSession, visualSess: OrtSession) {
        // Contiguous tail of face crops (gap < 100 ms), most recent SEG_FRAMES.
        // Take the last SEG_FRAMES, leaving the newest TAIL_FRAMES out so the
        // audio ring already holds the forward +shift margin (a video frame at
        // the segment end matches audio up to SHIFT_MAX frames later — that
        // audio must exist, not be in the future). Frames are treated as a
        // uniform 25 fps timeline; occasional arrival jitter or a single clip
        // loop-seam gap is minor noise the multi-window averaging absorbs. A
        // gross span error (reconnect mid-window) is rejected below.
        val seg: List<Crop> = synchronized(cropsLock) {
            if (crops.size < MIN_FRAMES + TAIL_FRAMES) {
                lastResult = "not enough face frames (${crops.size})"; return
            }
            crops.dropLast(TAIL_FRAMES).takeLast(SEG_FRAMES)
        }
        val spanMs = (seg.last().wallNs - seg.first().wallNs) / 1_000_000L
        val expMs = (seg.size - 1) * 40L
        if (spanMs > expMs * 2) {
            lastResult = "segment span ${spanMs}ms >> ${expMs}ms (discontinuity) — skipped"; return
        }
        // Treat consecutive crops as exactly 40 ms apart, anchored at the first.
        val t0 = seg.first().wallNs
        val n = seg.size

        // Audio covering the segment ± the sweep range.
        val marginMs = (SHIFT_MAX + 5) * 40L
        val audioStartNs = t0 - marginMs * 1_000_000L
        val audioSamples = ((n * 40L + 200L + 2 * marginMs) * 16).toInt()
        val pcm = audioSegment(audioStartNs, audioSamples)
            ?: run { lastResult = "audio ring can't cover segment"; return }
        val micRms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        if (micRms < 60) { lastResult = "too quiet (micRms=%.0f)".format(micRms); return }
        val mfcc = Mfcc.compute(pcm) // [T][13] @ 100 fps
        val colOf = { frameIdx: Int -> (marginMs / 10L).toInt() + 4 * frameIdx }

        val t1 = System.nanoTime()
        // Visual embeddings: 5-frame stacks, step VSTEP.
        val vIdx = (0..n - 5 step VSTEP).toList()
        val vemb = HashMap<Int, FloatArray>()
        vIdx.chunked(BATCH).forEach { chunk ->
            val fb = FloatBuffer.allocate(chunk.size * 3 * 5 * 224 * 224)
            for (i in chunk) for (c in 0 until 3) for (t in 0 until 5) {
                val bgr = seg[i + t].bgr
                var p = c // BGR bytes are interleaved b,g,r; channel c plane
                for (px in 0 until 224 * 224) { fb.put(bgr[px * 3 + c].toInt().and(0xff).toFloat()) }
            }
            fb.rewind()
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), fb,
                longArrayOf(chunk.size.toLong(), 3, 5, 224, 224)).use { t ->
                visualSess.run(mapOf("frames" to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = r[0].value as Array<FloatArray>
                    chunk.forEachIndexed { k, i -> vemb[i] = out[k] }
                }
            }
        }
        // Audio embeddings for every shifted position any vIdx will query.
        val aPos = sortedSetOf<Int>()
        for (i in vIdx) for (s in -SHIFT_MAX..SHIFT_MAX) aPos.add(i + s)
        val aemb = HashMap<Int, FloatArray>()
        aPos.filter { j -> colOf(j) >= 0 && colOf(j) + 20 <= mfcc.size }
            .chunked(BATCH).forEach { chunk ->
                val fb = FloatBuffer.allocate(chunk.size * 13 * 20)
                for (j in chunk) {
                    val c0 = colOf(j)
                    for (mel in 0 until 13) for (t in 0 until 20) fb.put(mfcc[c0 + t][mel])
                }
                fb.rewind()
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), fb,
                    longArrayOf(chunk.size.toLong(), 1, 13, 20)).use { t ->
                    audioSess.run(mapOf("mfcc" to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val out = r[0].value as Array<FloatArray>
                        chunk.forEachIndexed { k, j -> aemb[j] = out[k] }
                    }
                }
            }

        // Mean L2 distance per shift.
        val dists = DoubleArray(2 * SHIFT_MAX + 1) { Double.NaN }
        for (s in -SHIFT_MAX..SHIFT_MAX) {
            var sum = 0.0; var cnt = 0
            for (i in vIdx) {
                val v = vemb[i] ?: continue
                val a = aemb[i + s] ?: continue
                var d = 0.0
                for (k in v.indices) { val df = v[k] - a[k]; d += df * df }
                sum += sqrt(d); cnt++
            }
            if (cnt > 0) dists[s + SHIFT_MAX] = sum / cnt
        }
        val valid = dists.withIndex().filter { !it.value.isNaN() }
        if (valid.size < 10) { lastResult = "distance curve too sparse"; return }
        val minEntry = valid.minBy { it.value }
        val median = valid.map { it.value }.sorted()[valid.size / 2]
        val conf = median - minEntry.value
        val shift = minEntry.index - SHIFT_MAX
        // Video frame i matches audio at i+shift on the shared wall timeline;
        // shift < 0 → the matching audio is EARLIER → audio leads video by
        // -shift frames → delay the mic by that much.
        val delayMs = -shift * 40
        val ms = (System.nanoTime() - t1) / 1e6
        lastResult = ("shift=$shift -> delay=${delayMs}ms conf=%.2f minDist=%.2f " +
            "(v=${vIdx.size} a=${aemb.size} %.0fms cpu)").format(conf, minEntry.value, ms)
        Log.i(TAG, "estimate: $lastResult")
        if (conf < MIN_CONF) return
        if (delayMs < -250 || delayMs > 5000) return
        Log.i(TAG, "applying ML delay ${delayMs}ms")
        onOffset(max(0, delayMs))
    }

    // ------------------------------------------------------------- pixels

    /** Downscaled ARGB bitmap from the Y plane (detector input). */
    private fun i420ToBitmap(buf: ByteBuffer, w: Int, h: Int, scale: Int): Bitmap {
        val ow = w / scale; val oh = h / scale
        val px = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * scale * w
            for (x in 0 until ow) {
                val l = buf.get(sy + x * scale).toInt() and 0xff
                px[y * ow + x] = -0x1000000 or (l shl 16) or (l shl 8) or l
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** Square face crop → 224×224 interleaved BGR bytes (nearest neighbour). */
    private fun cropBgr224(buf: ByteBuffer, w: Int, h: Int, box: RectF): ByteArray {
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        val side = max(box.width(), box.height()) * CROP_SCALE
        val x0 = (cx - side / 2).toInt(); val y0 = (cy - side / 2).toInt()
        val out = ByteArray(224 * 224 * 3)
        val uOff = w * h
        val vOff = uOff + (w / 2) * (h / 2)
        for (oy in 0 until 224) {
            val sy = (y0 + oy * side / 224).toInt().coerceIn(0, h - 1)
            for (ox in 0 until 224) {
                val sx = (x0 + ox * side / 224).toInt().coerceIn(0, w - 1)
                val yv = buf.get(sy * w + sx).toInt() and 0xff
                val u = (buf.get(uOff + (sy / 2) * (w / 2) + sx / 2).toInt() and 0xff) - 128
                val v = (buf.get(vOff + (sy / 2) * (w / 2) + sx / 2).toInt() and 0xff) - 128
                val r = (yv + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (yv - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * u).toInt().coerceIn(0, 255)
                val p = (oy * 224 + ox) * 3
                out[p] = b.toByte(); out[p + 1] = g.toByte(); out[p + 2] = r.toByte()
            }
        }
        return out
    }

    private fun assetFile(name: String): File {
        val f = File(context.cacheDir, name)
        if (!f.exists() || f.length() == 0L) {
            context.assets.open(name).use { ins -> f.outputStream().use { ins.copyTo(it) } }
        }
        return f
    }

    companion object {
        private const val TAG = "MlSyncEstimator"
        private const val RING_SECONDS = 30
        private const val MAX_CROPS = 250          // ~10 s @25fps, ~37 MB
        private const val SEG_FRAMES = 125         // 5 s @25fps
        private const val TAIL_FRAMES = 40         // ~1.6 s > SHIFT_MAX forward margin
        private const val MIN_FRAMES = 75          // 3 s minimum
        private const val DETECT_EVERY = 12L       // detector input every ~0.5 s
        private const val SHIFT_MAX = 15           // ±600 ms sweep
        // Visual inference dominates cost (~130 ms/window on the tablet CPU);
        // every 3rd frame keeps a 5 s segment near ~40 windows (~5 s/estimate)
        // while still averaging enough windows for a stable curve.
        private const val VSTEP = 3
        private const val BATCH = 16
        private const val CROP_SCALE = 1.8f
        private const val MIN_CONF = 2.0
        private const val POLL_MS = 500L
        private const val FIRST_AFTER_NS = 20_000_000_000L
        private const val INTERVAL_NS = 60_000_000_000L
    }
}
