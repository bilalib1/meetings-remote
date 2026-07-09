package com.bilal.zoomroom

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Low-latency screen mirror to an AirPlay-2 receiver (e.g. the room's TCL Roku),
 * entirely in-app — no dongle, no Google Cast, no Miracast.
 *
 * Pipeline: [MediaProjection] → [VirtualDisplay] → hardware H.264 [MediaCodec]
 * → Annex-B byte stream → the doubletake Go sender (`airplaysender.aar`), which
 * does pair-verify, SETUP/RECORD, ChaCha20 stream encryption, NTP timing and
 * type-110 packetization. Proven against the real TV (test pattern confirmed).
 *
 * This receiver does NOT use FairPlay, so no Apple secret is involved.
 */
class AirPlayCaster(
    private val projection: MediaProjection,
    private val widthPx: Int,
    private val heightPx: Int,
    private val dpi: Int,
) {
    private var session: mobile.Session? = null
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    /**
     * Connect to [host]:[port] using an existing pairing ([pairingId] +
     * 32-byte [ed25519Seed]) and begin mirroring. Blocks briefly while the
     * AirPlay session is negotiated; throws if it fails (caller should surface
     * the error and not leave the Cast button lit).
     */
    fun start(
        host: String,
        port: Int,
        pairingId: String,
        ed25519Seed: ByteArray,
        fps: Int = 30,
        bitrateKbps: Int = 0,
    ) {
        // 1) Open the AirPlay session first — pair-verify + SETUP must succeed
        //    before we spend effort encoding frames nothing will receive.
        session = mobile.Mobile.start(
            host, port.toLong(), pairingId, ed25519Seed, fps.toLong(), bitrateKbps.toLong(),
        )

        // 2) Hardware H.264 encoder fed by a Surface, emitting an Annex-B stream.
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, widthPx, heightPx).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (bitrateKbps > 0) bitrateKbps * 1000 else DEFAULT_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s GOP — fast to attach, low latency
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = enc.createInputSurface()
        enc.start()
        codec = enc

        // 3) Mirror the screen into the encoder's input surface.
        virtualDisplay = projection.createVirtualDisplay(
            "airplay",
            widthPx, heightPx, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )

        // 4) Pump encoded output to the sender on a background thread.
        running = true
        drainThread = Thread(::drainLoop, "airplay-drain").apply { start() }
    }

    private fun drainLoop() {
        val enc = codec ?: return
        val sess = session ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                enc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder stopped: ${e.message}")
                break
            }
            if (idx < 0) continue // INFO_TRY_AGAIN / format-change — nothing to send
            val buf: ByteBuffer? = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                // MediaCodec AVC output (incl. the codec-config SPS/PPS buffer) is
                // Annex-B; doubletake's parser handles start codes, so forward all.
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                try {
                    sess.writeH264(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "writeH264 failed, ending stream: ${e.message}")
                    enc.releaseOutputBuffer(idx, false)
                    break
                }
            }
            enc.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    /** Tear everything down. Safe to call more than once. */
    fun stop() {
        running = false
        runCatching { drainThread?.join(500) }
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { session?.stop() }
        runCatching { projection.stop() }
        virtualDisplay = null; codec = null; inputSurface = null; session = null
    }

    companion object {
        private const val TAG = "AirPlayCaster"
        private const val DEFAULT_BITRATE = 6_000_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
