package com.bilal.zoomroom.source

/**
 * Caps an incoming frame stream to a negotiated fps. Frames are dropped, never
 * queued — a stale frame is pure latency (plan §9).
 *
 * Uses a running deadline that advances by exactly one interval per emit
 * (accumulator), so the emitted *average* matches the cap even when the source
 * rate isn't an integer multiple of it. A naive "drop if since-last < interval"
 * gate instead quantizes to source-frame multiples: a 30 fps source into a
 * 25 fps cap can only emit every 2nd frame (15 fps), never 25. That bug was
 * throttling RTSP video to ~10-15 fps regardless of decoder/network health.
 */
class FramePacer(fpsMax: Int) {
    private val minIntervalNs: Long = if (fpsMax <= 0) 0 else 1_000_000_000L / fpsMax
    private var nextEmitNs = Long.MIN_VALUE

    /** Returns true if a frame arriving at [nowNs] should be forwarded. */
    fun shouldEmit(nowNs: Long): Boolean {
        if (minIntervalNs == 0L) return true
        if (nextEmitNs == Long.MIN_VALUE) {          // first frame always passes
            nextEmitNs = nowNs + minIntervalNs
            return true
        }
        if (nowNs < nextEmitNs) return false
        nextEmitNs += minIntervalNs
        // Slow source (gap > interval): don't bank credit for a future burst.
        if (nextEmitNs <= nowNs) nextEmitNs = nowNs + minIntervalNs
        return true
    }
}
