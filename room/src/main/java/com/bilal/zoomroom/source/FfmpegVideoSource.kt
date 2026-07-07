package com.bilal.zoomroom.source

import android.util.Log
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * RTSP camera via native FFmpeg: demux + MediaCodec hardware decode
 * (h264_mediacodec / hevc_mediacodec) + swscale NV12->I420, all in C. No pixel
 * processing on the JVM. Emits packed I420 frames to the sink, paced to fps,
 * and reconnects with backoff.
 */
class FfmpegVideoSource(private val rtspUrl: String) : VideoSourceProvider {

    @Volatile private var running = false
    @Volatile private var handle = 0L
    private var worker: Thread? = null
    @Volatile var status: String = "idle"
        private set

    override fun start(target: Negotiated, sink: FrameSink) {
        stop()
        running = true
        worker = thread(name = "ffmpeg-rtsp") { loop(target, sink) }
    }

    override fun stop() {
        running = false
        val h = handle
        if (h != 0L) nativeStop(h)
        worker?.join(3000)
        worker = null
    }

    private fun loop(target: Negotiated, sink: FrameSink) {
        var backoffMs = 1000L
        while (running) {
            status = "connecting"
            val h = nativeOpen(rtspUrl, target.width, target.height)
            if (h == 0L) {
                status = "camera offline — reconnecting"
                if (!sleep(backoffMs)) return
                backoffMs = minOf(backoffMs * 2, 10_000L)
                continue
            }
            handle = h
            backoffMs = 1000L
            status = "connected"
            val w = nativeWidth(h)
            val hgt = nativeHeight(h)
            val frame = ByteArray(w * hgt * 3 / 2)
            val buf = ByteBuffer.allocateDirect(frame.size)
            val pacer = FramePacer(target.fps)
            var count = 0L
            try {
                while (running) {
                    val n = nativeNextFrame(h, frame)
                    if (n <= 0) break // EOF / disconnect
                    if (!pacer.shouldEmit(System.nanoTime())) continue
                    buf.clear(); buf.put(frame, 0, n); buf.flip()
                    sink.onFrame(buf, w, hgt)
                    if (count++ % 300L == 0L) Log.i(TAG, "emitted $count frames ${w}x$hgt")
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode loop error: $e")
            } finally {
                handle = 0L
                nativeClose(h)
            }
            if (running) status = "camera offline — reconnecting"
            if (!running || !sleep(1000)) break
        }
        status = "stopped"
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (_: InterruptedException) { false }

    private external fun nativeOpen(url: String, wantW: Int, wantH: Int): Long
    private external fun nativeNextFrame(handle: Long, out: ByteArray): Int
    private external fun nativeWidth(handle: Long): Int
    private external fun nativeHeight(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        private const val TAG = "FfmpegVideoSource"

        init {
            // FFmpeg libs load first (dependency order), then our wrapper.
            for (lib in listOf("avutil", "swresample", "avcodec", "swscale", "avformat", "rtspdecoder")) {
                System.loadLibrary(lib)
            }
        }
    }
}
