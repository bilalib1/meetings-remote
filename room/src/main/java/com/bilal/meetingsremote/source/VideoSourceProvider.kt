package com.bilal.meetingsremote.source

import java.nio.ByteBuffer

/** Resolution/fps negotiated with the Zoom SDK for our outgoing video. */
data class Negotiated(val width: Int, val height: Int, val fps: Int)

/** Receives packed I420 frames ready for ZoomSDKVideoSender.sendVideoFrame(). */
fun interface FrameSink {
    fun onFrame(buffer: ByteBuffer, width: Int, height: Int)
}

/**
 * One camera source: test pattern, RTSP, UVC, or device camera.
 * start() may be called again after stop() (SDK stop/start send cycles).
 */
interface VideoSourceProvider {
    fun start(target: Negotiated, sink: FrameSink)
    fun stop()
}
