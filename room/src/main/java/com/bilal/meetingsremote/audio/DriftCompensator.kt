package com.bilal.meetingsremote.audio

import android.util.Log
import com.bilal.meetingsremote.source.FfmpegVideoSource
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Follows pipeline-latency drift between (expensive, infrequent) absolute
 * offset estimates, using the provider's min-filtered (arrival − videoPTS)
 * mapping — that value moving within one RTSP connection means the camera's
 * latency changed (network congestion, buffer growth).
 *
 * [anchor] is called whenever an absolute delay is applied (GCC-PHAT, ML
 * lip-sync, or manual); from then on the mic delay is adjusted by however far
 * the mapping has moved since the anchor. PTS origins differ between
 * connections, so on reconnect the tracker re-anchors and carries the current
 * delay forward unchanged until the next absolute estimate.
 */
class DriftCompensator(
    private val provider: FfmpegVideoSource,
    private val onAdjust: (ms: Int) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    // Anchor state (poll thread + anchor() callers; all volatile, races benign).
    @Volatile private var baseDelayMs = -1
    @Volatile private var anchorOffsetNs = Long.MIN_VALUE
    @Volatile private var anchorGen = -1
    @Volatile private var lastAppliedMs = -1

    /** An absolute delay was just applied — measure drift relative to here. */
    fun anchor(delayMs: Int) {
        baseDelayMs = delayMs
        lastAppliedMs = delayMs
        anchorOffsetNs = provider.currentMapOffsetNs()
        anchorGen = provider.connectionGen
    }

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "drift-comp") { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    fun stats(): String =
        "base=${baseDelayMs}ms anchored=${anchorOffsetNs != Long.MIN_VALUE} " +
        "gen=$anchorGen applied=${lastAppliedMs}ms"

    private fun loop() {
        while (running) {
            try { Thread.sleep(2_000) } catch (_: InterruptedException) { continue }
            val base = baseDelayMs
            if (base < 0) continue // nothing anchored yet
            val off = provider.currentMapOffsetNs()
            if (off == Long.MIN_VALUE) continue
            val gen = provider.connectionGen
            if (gen != anchorGen || anchorOffsetNs == Long.MIN_VALUE) {
                // New connection: PTS timeline changed, drift unknowable.
                // Carry the current delay forward and re-anchor.
                baseDelayMs = lastAppliedMs
                anchorOffsetNs = off
                anchorGen = gen
                continue
            }
            val driftMs = ((off - anchorOffsetNs) / 1_000_000L).toInt()
            val target = (base + driftMs).coerceAtLeast(0)
            if (abs(target - lastAppliedMs) > THRESHOLD_MS) {
                Log.i(TAG, "latency drifted ${driftMs}ms since anchor -> delay ${target}ms")
                lastAppliedMs = target
                onAdjust(target)
            }
        }
    }

    companion object {
        private const val TAG = "DriftCompensator"
        private const val THRESHOLD_MS = 40
    }
}
