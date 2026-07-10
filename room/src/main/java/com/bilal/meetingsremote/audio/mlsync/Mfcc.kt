package com.bilal.meetingsremote.audio.mlsync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MFCC matching python_speech_features.mfcc defaults — the exact features
 * SyncNet's audio branch was trained on (16 kHz mono s16, 25 ms window,
 * 10 ms hop, 512-pt FFT, 26 mel filters 0–8 kHz, DCT-II ortho, 13 coeffs,
 * ceplifter 22, first coefficient replaced by log frame energy).
 *
 * Output: FloatArray[frames][13], 100 frames/s.
 */
object Mfcc {

    private const val SAMPLE_RATE = 16_000
    private const val WIN = 400          // 25 ms
    private const val HOP = 160          // 10 ms
    private const val NFFT = 512
    private const val NFILT = 26
    private const val NCEP = 13
    private const val PREEMPH = 0.97
    private const val LIFTER = 22

    private val melBank: Array<DoubleArray> by lazy { buildMelBank() }

    fun compute(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < WIN) return emptyArray()
        // Pre-emphasis over the whole signal (python_speech_features order).
        val sig = DoubleArray(pcm.size)
        sig[0] = pcm[0].toDouble()
        for (i in 1 until pcm.size) sig[i] = pcm[i] - PREEMPH * pcm[i - 1]

        val frames = 1 + (pcm.size - WIN) / HOP
        val out = Array(frames) { FloatArray(NCEP) }
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val power = DoubleArray(NFFT / 2 + 1)
        val feats = DoubleArray(NFILT)
        for (f in 0 until frames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            var energy = 0.0
            for (i in 0 until WIN) {          // rectangular window (default)
                val v = sig[off + i]
                re[i] = v
            }
            fft(re, im)
            for (k in 0..NFFT / 2) {
                power[k] = (re[k] * re[k] + im[k] * im[k]) / NFFT
                energy += power[k]
            }
            // (python_speech_features energy = sum of power spectrum)
            for (m in 0 until NFILT) {
                var s = 0.0
                val bank = melBank[m]
                for (k in 0..NFFT / 2) s += power[k] * bank[k]
                feats[m] = ln(max(s, 1e-30))
            }
            // DCT-II, ortho norm, first NCEP coeffs.
            for (c in 0 until NCEP) {
                var s = 0.0
                for (m in 0 until NFILT) {
                    s += feats[m] * cos(PI * c * (2 * m + 1) / (2.0 * NFILT))
                }
                s *= if (c == 0) sqrt(1.0 / NFILT) else sqrt(2.0 / NFILT)
                // Ceplifter.
                val lift = 1 + (LIFTER / 2.0) * sin(PI * c / LIFTER)
                out[f][c] = (s * lift).toFloat()
            }
            // appendEnergy=True: coeff 0 := log(total frame energy).
            out[f][0] = ln(max(energy, 1e-30)).toFloat()
        }
        return out
    }

    private fun buildMelBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1)
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(NFILT + 2) {
            melToHz(lowMel + (highMel - lowMel) * it / (NFILT + 1))
        }
        val bins = IntArray(NFILT + 2) {
            Math.floor((NFFT + 1) * points[it] / SAMPLE_RATE).toInt()
        }
        return Array(NFILT) { m ->
            val bank = DoubleArray(NFFT / 2 + 1)
            for (k in bins[m] until bins[m + 1]) {
                if (k in bank.indices)
                    bank[k] = (k - bins[m]).toDouble() / (bins[m + 1] - bins[m])
            }
            for (k in bins[m + 1] until bins[m + 2]) {
                if (k in bank.indices)
                    bank[k] = (bins[m + 2] - k).toDouble() / (bins[m + 2] - bins[m + 1])
            }
            bank
        }
    }

    /** Iterative in-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
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
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
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
}
