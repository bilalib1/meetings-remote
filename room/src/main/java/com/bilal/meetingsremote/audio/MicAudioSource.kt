package com.bilal.meetingsremote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import us.zoom.sdk.IZoomSDKAudioRawDataSender
import us.zoom.sdk.IZoomSDKVirtualAudioMicEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * The meeting mic: tablet AudioRecord -> variable delay ring -> Zoom SDK
 * virtual audio source (plan 2026-07-10-audio-and-av-sync).
 *
 * The delay is the AV-sync knob — the external camera's video arrives hundreds
 * of ms late, so the (instant) mic audio is held back to match. Delay changes
 * are applied with a one-block crossfade so corrections are inaudible.
 *
 * Capture uses VOICE_COMMUNICATION + platform AEC: the far end plays from the
 * tablet speaker, and echo must be cancelled at capture time (before our
 * delay), which is exactly what the platform AEC does. Zoom still runs its own
 * noise suppression on whatever we send.
 */
class MicAudioSource : IZoomSDKVirtualAudioMicEvent {

    @Volatile private var sender: IZoomSDKAudioRawDataSender? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    // Delay ring: RING_SECONDS of mono s16. writeTotal is the monotonic sample
    // count; reads trail it by delaySamples. Positions before the first write
    // read as silence (ring starts zeroed and is large enough that a stale
    // wraparound read would need >RING_SECONDS of delay, which is clamped out).
    private val ring = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private var writeTotal = 0L

    @Volatile private var delayTargetSamples = 0
    private var delaySamples = 0

    /** Undelayed capture tap for the sync estimator: (block, wallNs of first
     *  sample). Called on the capture thread; must be quick. */
    @Volatile var micTap: ((ShortArray, Long) -> Unit)? = null

    // Observability (audioStats test hook).
    @Volatile var state = "idle"; private set
    @Volatile private var blocksSent = 0L

    // Sync-tap clock (capture thread only).
    private var clockAnchorNs = 0L
    private var clockBlocks = 0L
    private fun expectedNs() = clockAnchorNs + (clockBlocks + 1) * BLOCK_MS * 1_000_000L

    val delayMs: Int get() = delayTargetSamples * 1000 / SAMPLE_RATE

    /** Set the mic delay (the sync correction). Clamped, crossfaded in. */
    fun setDelayMs(ms: Int) {
        val clamped = ms.coerceIn(0, MAX_DELAY_MS)
        if (clamped != ms) Log.w(TAG, "delay $ms ms clamped to $clamped")
        delayTargetSamples = clamped * SAMPLE_RATE / 1000
        Log.i(TAG, "delay target -> $clamped ms")
    }

    fun stats(): String =
        "state=$state delayMs=$delayMs blocksSent=$blocksSent sender=${sender != null}"

    // ------------------------------------------------ IZoomSDKVirtualAudioMicEvent

    override fun onMicInitialize(rawDataSender: IZoomSDKAudioRawDataSender) {
        Log.i(TAG, "onMicInitialize")
        sender = rawDataSender
        state = "initialized"
    }

    override fun onMicStartSend() {
        Log.i(TAG, "onMicStartSend (running=$running)")
        if (running) return
        running = true
        state = "starting"
        worker = thread(name = "mic-audio") { captureLoop() }
    }

    override fun onMicStopSend() {
        Log.i(TAG, "onMicStopSend")
        stopCapture()
        state = "stopped"
    }

    override fun onMicUninitialized() {
        Log.i(TAG, "onMicUninitialized")
        stopCapture()
        sender = null
        state = "uninitialized"
    }

    private fun stopCapture() {
        running = false
        worker?.join(2000)
        worker = null
    }

    // --------------------------------------------------------------- capture

    @SuppressLint("MissingPermission") // RECORD_AUDIO requested at app start
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, BLOCK_SAMPLES * 8 * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${rec.state})")
            state = "record-init-failed"
            rec.release()
            return
        }
        // Platform AEC on the session (cancels the tablet speaker's far-end
        // audio at capture time, before our delay). NS is deliberately OFF:
        // Zoom runs its own noise suppression on the PCM we send, and Samsung's
        // NS hard-gates non-speech to digital zero, which starves the AV-sync
        // correlation tap (measured 2026-07-10).
        val aec = if (AcousticEchoCanceler.isAvailable())
            AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        val ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = false }
        Log.i(TAG, "capture start: rate=$SAMPLE_RATE aec=${aec != null} nsOff=${ns != null}")

        clockAnchorNs = 0L
        val block = ShortArray(BLOCK_SAMPLES)
        val delayed = ShortArray(BLOCK_SAMPLES)
        val fade = ShortArray(BLOCK_SAMPLES)
        val out = ByteBuffer.allocateDirect(BLOCK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        rec.startRecording()
        state = "sending"
        try {
            while (running) {
                var got = 0
                while (got < BLOCK_SAMPLES && running) {
                    val n = rec.read(block, got, BLOCK_SAMPLES - got)
                    if (n < 0) { Log.e(TAG, "read error $n"); state = "read-error"; return }
                    got += n
                }
                if (!running) break
                // Stable block clock for the sync tap: read-return times jitter
                // with AudioRecord's internal buffering, so advance an anchored
                // sample counter instead, resyncing only on gross drift (device
                // stall / dropped samples).
                val now = System.nanoTime()
                if (clockAnchorNs == 0L || now - expectedNs() > 100_000_000L) {
                    clockAnchorNs = now - BLOCK_MS * 1_000_000L
                    clockBlocks = 0
                }
                val blockWallNs = clockAnchorNs + clockBlocks * BLOCK_MS * 1_000_000L
                clockBlocks++
                micTap?.invoke(block.copyOf(), blockWallNs)

                // Append to ring.
                for (i in 0 until BLOCK_SAMPLES) {
                    ring[((writeTotal + i) % ring.size).toInt()] = block[i]
                }
                writeTotal += BLOCK_SAMPLES

                // Emit the delayed block, crossfading if the target moved.
                val target = delayTargetSamples
                readRing(writeTotal - BLOCK_SAMPLES - delaySamples, delayed)
                if (target != delaySamples) {
                    readRing(writeTotal - BLOCK_SAMPLES - target, fade)
                    for (i in 0 until BLOCK_SAMPLES) {
                        val t = i.toFloat() / BLOCK_SAMPLES
                        delayed[i] = ((1 - t) * delayed[i] + t * fade[i]).toInt().toShort()
                    }
                    delaySamples = target
                }
                out.clear()
                for (s in delayed) out.putShort(s)
                val s = sender ?: continue
                val err = s.send(out, BLOCK_SAMPLES * 2, SAMPLE_RATE)
                blocksSent++
                if (blocksSent == 1L || blocksSent % 3000L == 0L) { // every 30 s
                    Log.i(TAG, "send #$blocksSent err=${err.name} delayMs=$delayMs")
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
            ns?.release()
            Log.i(TAG, "capture stopped after $blocksSent blocks")
        }
    }

    /** Copy BLOCK_SAMPLES from absolute ring position [from] (silence if <0). */
    private fun readRing(from: Long, dst: ShortArray) {
        for (i in dst.indices) {
            val pos = from + i
            dst[i] = if (pos < 0) 0 else ring[(pos % ring.size).toInt()]
        }
    }

    companion object {
        private const val TAG = "MicAudioSource"
        const val SAMPLE_RATE = 48_000
        private const val BLOCK_MS = 10
        private const val BLOCK_SAMPLES = SAMPLE_RATE * BLOCK_MS / 1000
        private const val RING_SECONDS = 10
        private const val MAX_DELAY_MS = 8_000
    }
}
