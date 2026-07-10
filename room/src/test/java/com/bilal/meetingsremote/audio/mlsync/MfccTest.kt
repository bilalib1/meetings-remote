package com.bilal.meetingsremote.audio.mlsync

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Validates Mfcc against python_speech_features.mfcc (the exact features
 * SyncNet trains on). Reference vectors generated 2026-07-10 with:
 * sum of 440 Hz + 1300 Hz sines + LCG noise, int16, 1 s @16 kHz.
 */
class MfccTest {

    private fun signal(): ShortArray {
        var seed = 12345L
        return ShortArray(16000) { i ->
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val noise = (seed.toDouble() / 0x7fffffff) * 2 - 1
            (6000 * sin(2 * PI * 440 * i / 16000) +
             3000 * sin(2 * PI * 1300 * i / 16000) + 1500 * noise).toInt().toShort()
        }
    }

    @Test
    fun matchesPythonSpeechFeatures() {
        val m = Mfcc.compute(signal())
        assertEquals(99, m.size)
        val expected = mapOf(
            0 to floatArrayOf(20.24866f, -16.64593f, -6.23619f, -24.22084f, -14.34726f,
                -14.19935f, -27.25301f, -45.84661f, -17.44827f, 25.25078f, 34.80495f,
                10.15703f, -6.09112f),
            7 to floatArrayOf(20.25102f, -13.22844f, 1.54219f, -11.90302f, -0.58000f,
                -3.47151f, -28.31217f, -40.25673f, -17.13381f, 30.10645f, 41.38047f,
                11.71328f, -5.23386f),
            50 to floatArrayOf(20.25956f, -14.73367f, -3.70570f, -19.19132f, -9.90763f,
                -8.01328f, -29.27855f, -38.63795f, -14.82458f, 25.74016f, 37.78332f,
                8.52558f, -2.81878f),
        )
        for ((frame, exp) in expected) {
            for (c in 0 until 13) {
                assertEquals("frame $frame coeff $c", exp[c], m[frame][c], 0.02f)
            }
        }
    }
}
