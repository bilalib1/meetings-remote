package com.bilal.zoomroom.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    @Test
    fun firstFrameAlwaysEmits() {
        assertTrue(FramePacer(30).shouldEmit(123L))
    }

    @Test
    fun thirtyFpsInputToFifteenFpsCapDropsHalf() {
        val pacer = FramePacer(15)
        val frameNs = 1_000_000_000L / 30
        var emitted = 0
        for (i in 0 until 30) {
            if (pacer.shouldEmit(i * frameNs)) emitted++
        }
        assertEquals(15, emitted)
    }

    @Test
    fun neverEmitsFasterThanCap() {
        val pacer = FramePacer(15)
        val minGap = 1_000_000_000L / 15
        var last = Long.MIN_VALUE
        val frameNs = 1_000_000_000L / 60
        for (i in 0 until 240) {
            val t = i * frameNs
            if (pacer.shouldEmit(t)) {
                if (last != Long.MIN_VALUE) assertTrue(t - last >= minGap)
                last = t
            }
        }
    }

    @Test
    fun slowInputPassesThrough() {
        val pacer = FramePacer(30)
        val frameNs = 1_000_000_000L / 10 // 10 fps input
        var emitted = 0
        for (i in 0 until 10) {
            if (pacer.shouldEmit(i * frameNs)) emitted++
        }
        assertEquals(10, emitted)
    }
}
