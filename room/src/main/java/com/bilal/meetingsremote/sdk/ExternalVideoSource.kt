package com.bilal.meetingsremote.sdk

import android.util.Log
import com.bilal.meetingsremote.source.FrameSink
import com.bilal.meetingsremote.source.Negotiated
import com.bilal.meetingsremote.source.VideoSourceProvider
import us.zoom.sdk.ExternalSourceDataFormat
import us.zoom.sdk.ZoomSDKVideoCapability
import us.zoom.sdk.ZoomSDKVideoSender
import us.zoom.sdk.ZoomSDKVideoSource

/**
 * Bridges a VideoSourceProvider into the Zoom SDK external video source API
 * (plan §7/§9): holds the sender from onInitialize(), pumps provider frames
 * between onStartSend()/onStopSend().
 */
class ExternalVideoSource(@Volatile var provider: VideoSourceProvider) : ZoomSDKVideoSource {

    @Volatile private var sender: ZoomSDKVideoSender? = null
    @Volatile private var negotiated = Negotiated(1280, 720, 30)
    @Volatile private var sending = false

    val isSending: Boolean get() = sending

    /** When the last frame left the provider (nanoTime). Construction counts as
     *  a frame so a fresh source gets a grace period before reading "offline". */
    @Volatile var lastFrameNs: Long = System.nanoTime()
        private set

    /** Optional tap for a local self-preview; receives the same I420 frames. */
    @Volatile var previewSink: FrameSink? = null

    /** Asked on an SDK-initiated stop: keep the provider (and its reconnect
     *  loop) running so a camera that comes back mid-meeting can auto-recover?
     *  Frames produced while stopped are dropped, not sent. */
    @Volatile var keepAliveOnStop: (() -> Boolean)? = null

    /** Fired (throttled) when frames arrive but the SDK isn't accepting them —
     *  the camera is back and someone should re-engage the source. */
    @Volatile var onOrphanFrame: (() -> Unit)? = null

    override fun onInitialize(
        videoSender: ZoomSDKVideoSender,
        supportedCaps: MutableList<ZoomSDKVideoCapability>?,
        suggested: ZoomSDKVideoCapability?,
    ) {
        sender = videoSender
        negotiated = pick(supportedCaps, suggested)
        Log.i(TAG, "onInitialize: negotiated=$negotiated caps=${supportedCaps?.size}")
    }

    override fun onPropertyChange(
        supportedCaps: MutableList<ZoomSDKVideoCapability>?,
        suggested: ZoomSDKVideoCapability?,
    ) {
        negotiated = pick(supportedCaps, suggested)
        Log.i(TAG, "onPropertyChange: negotiated=$negotiated")
        if (sending) {
            provider.stop()
            startProvider()
        }
    }

    override fun onStartSend() {
        Log.i(TAG, "onStartSend (sending=$sending)")
        if (sending) return // SDK can call twice; don't spin up a second decoder
        sending = true
        startProvider()
    }

    override fun onStopSend() {
        val keep = keepAliveOnStop?.invoke() == true
        Log.i(TAG, "onStopSend (keepAlive=$keep)")
        sending = false
        if (!keep) provider.stop()
    }

    override fun onUninitialized() {
        val keep = keepAliveOnStop?.invoke() == true
        Log.i(TAG, "onUninitialized (keepAlive=$keep)")
        sending = false
        sender = null
        if (!keep) provider.stop()
    }

    /** Swap the camera source live (e.g. RTSP -> test pattern). */
    fun swapProvider(newProvider: VideoSourceProvider) {
        val wasSending = sending
        if (wasSending) provider.stop()
        provider = newProvider
        if (wasSending) startProvider()
    }

    private fun startProvider() {
        var sent = 0L
        provider.start(negotiated) { buffer, w, h ->
            lastFrameNs = System.nanoTime()
            val s = sender
            if (sending && s != null) {
                s.sendVideoFrame(
                    buffer, w, h, w * h * 3 / 2,
                    ZoomSDKVideoSender.ROTATION_ACTION_0,
                    ExternalSourceDataFormat.ExternalSourceDataFormat_I420_FULL,
                )
                if (sent == 0L || sent % 60L == 0L) Log.i(TAG, "sendVideoFrame #$sent ${w}x$h")
            } else {
                // Camera producing but the SDK stopped accepting (it gave up
                // during an outage) — signal for auto-recovery, throttled.
                if (sent % 30L == 0L) {
                    Log.w(TAG, "frame $sent orphaned (sending=$sending sender=${s != null})")
                    onOrphanFrame?.invoke()
                }
            }
            previewSink?.onFrame(buffer, w, h)
            sent++
        }
    }

    private fun pick(
        caps: List<ZoomSDKVideoCapability>?,
        suggested: ZoomSDKVideoCapability?,
    ): Negotiated {
        val c = suggested ?: caps?.maxByOrNull { it.width * it.height }
        return if (c != null && c.width > 0 && c.height > 0) {
            Negotiated(c.width, c.height, if (c.frame in 1..60) c.frame else 30)
        } else {
            Negotiated(1280, 720, 30)
        }
    }

    companion object {
        private const val TAG = "ExternalVideoSource"
    }
}
