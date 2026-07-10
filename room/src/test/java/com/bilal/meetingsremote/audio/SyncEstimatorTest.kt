package com.bilal.meetingsremote.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Validates the GCC-PHAT offset math end-to-end through the real taps: a
 * shared noise "world" is fed to the mic ring at capture time and to the
 * camera ring shifted by a known pipeline latency; the estimator must recover
 * that latency to within a few ms.
 */
class SyncEstimatorTest {

    private fun runCase(trueOffsetMs: Long, seconds: Int = 16, seed: Int = 7): Int? {
        var applied: Int? = null
        val est = SyncEstimator { ms -> applied = ms }
        val rnd = Random(seed)
        val epoch = 1_000_000_000L
        val blockMs = 10
        val n16 = 160          // 16 kHz samples per 10 ms
        for (b in 0 until seconds * 1000 / blockMs) {
            val wallNs = epoch + b * blockMs * 1_000_000L
            // The 16 kHz "world" signal for this block (speech-ish noise).
            val world = ShortArray(n16) { ((rnd.nextDouble() - 0.5) * 8000).toInt().toShort() }
            // Mic hears it instantly, at 48 kHz (each sample held 3x, so the
            // estimator's mean-of-3 decimation recovers the original).
            val mic48 = ShortArray(n16 * 3) { world[it / 3] }
            est.onMicAudio(mic48, wallNs)
            // Camera audio carries the same sound but surfaces trueOffsetMs
            // later on the video-send timeline.
            est.onCameraAudio(world, n16, wallNs + trueOffsetMs * 1_000_000L)
        }
        est.estimate()
        return applied
    }

    @Test
    fun recoversPositiveOffset() {
        val applied = runCase(473)
        assertTrue("no confident estimate applied", applied != null)
        assertTrue("got $applied, want ~473", abs(applied!! - 473) <= 5)
    }

    @Test
    fun recoversZeroOffset() {
        val applied = runCase(0)
        assertTrue("no confident estimate applied", applied != null)
        assertTrue("got $applied, want ~0", abs(applied!!) <= 5)
    }

    @Test
    fun recoversLargeOffset() {
        val applied = runCase(2100)
        assertTrue("no confident estimate applied", applied != null)
        assertTrue("got $applied, want ~2100", abs(applied!! - 2100) <= 5)
    }

    @Test
    fun silenceIsRejected() {
        var applied: Int? = null
        val est = SyncEstimator { ms -> applied = ms }
        val epoch = 1_000_000_000L
        for (b in 0 until 1600) {
            val wallNs = epoch + b * 10_000_000L
            est.onMicAudio(ShortArray(480), wallNs)
            est.onCameraAudio(ShortArray(160), 160, wallNs)
        }
        est.estimate()
        assertEquals(null, applied)
    }
}
