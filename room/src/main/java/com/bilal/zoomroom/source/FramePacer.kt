package com.bilal.zoomroom.source

/**
 * Caps an incoming frame stream to a negotiated fps. Frames are dropped, never
 * queued — a stale frame is pure latency (plan §9).
 */
class FramePacer(fpsMax: Int) {
    private val minIntervalNs: Long = if (fpsMax <= 0) 0 else 1_000_000_000L / fpsMax
    private var lastEmitNs = Long.MIN_VALUE

    /** Returns true if a frame arriving at [nowNs] should be forwarded. */
    fun shouldEmit(nowNs: Long): Boolean {
        if (lastEmitNs != Long.MIN_VALUE && nowNs - lastEmitNs < minIntervalNs) return false
        lastEmitNs = nowNs
        return true
    }
}
