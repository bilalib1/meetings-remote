package com.bilal.zoomroom.sdk

import android.util.Log
import com.bilal.zoomroom.source.FrameSink
import com.bilal.zoomroom.source.Negotiated
import com.bilal.zoomroom.source.VideoSourceProvider
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

    /** Optional tap for a local self-preview; receives the same I420 frames. */
    @Volatile var previewSink: FrameSink? = null

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
        Log.i(TAG, "onStopSend")
        sending = false
        provider.stop()
    }

    override fun onUninitialized() {
        Log.i(TAG, "onUninitialized")
        sending = false
        provider.stop()
        sender = null
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
            val s = sender
            if (s == null) {
                if (sent % 60L == 0L) Log.w(TAG, "frame $sent dropped: no sender")
            } else {
                s.sendVideoFrame(
                    buffer, w, h, w * h * 3 / 2,
                    ZoomSDKVideoSender.ROTATION_ACTION_0,
                    ExternalSourceDataFormat.ExternalSourceDataFormat_I420_FULL,
                )
                if (sent == 0L || sent % 60L == 0L) Log.i(TAG, "sendVideoFrame #$sent ${w}x$h")
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
