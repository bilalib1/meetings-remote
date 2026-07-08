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
    fun averageRateNeverExceedsCap() {
        // A proper downsampler holds the *average* at the cap; individual gaps
        // may be shorter (that's how a non-integer ratio averages out). Assert
        // the emitted count over 4s of 60 fps input stays at the 15 fps budget.
        val pacer = FramePacer(15)
        val frameNs = 1_000_000_000L / 60
        var emitted = 0
        for (i in 0 until 240) {          // 4 seconds
            if (pacer.shouldEmit(i * frameNs)) emitted++
        }
        assertTrue("expected ~60, got $emitted", emitted in 60..61)
    }

    @Test
    fun thirtyFpsInputToTwentyFiveFpsCapKeepsTwentyFive() {
        // Non-integer ratio: the old fixed-interval pacer quantized to every
        // 2nd frame (15 fps). The accumulator must hold the ~25 fps average.
        val pacer = FramePacer(25)
        val frameNs = 1_000_000_000L / 30
        var emitted = 0
        for (i in 0 until 30) {          // 1 second of 30 fps input
            if (pacer.shouldEmit(i * frameNs)) emitted++
        }
        assertTrue("expected ~25, got $emitted", emitted in 24..26)
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
