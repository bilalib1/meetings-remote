package com.bilal.zoomroom.source

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.alexvas.rtsp.RtspClient
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RTSP camera (plan step 4): RTSP over TCP-interleaved -> H.264/H.265 NALs ->
 * MediaCodec ByteBuffer decode -> packed I420 -> sink, paced to the
 * negotiated fps. Reconnects with backoff while started.
 */
class RtspVideoSource(private val rtspUrl: String) : VideoSourceProvider {

    @Volatile private var running = false
    private val stopFlag = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile var status: String = "idle"
        private set

    override fun start(target: Negotiated, sink: FrameSink) {
        stop()
        running = true
        stopFlag.set(false)
        worker = thread(name = "rtsp-source") {
            var backoffMs = 1000L
            while (running) {
                status = "connecting"
                try {
                    runOnce(target, sink)
                    backoffMs = 1000L // clean disconnect: retry quickly
                } catch (e: Exception) {
                    Log.w(TAG, "RTSP session failed: $e")
                }
                if (!running) break
                status = "camera offline — reconnecting"
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs = minOf(backoffMs * 2, 10_000L)
            }
            status = "stopped"
        }
    }

    override fun stop() {
        running = false
        stopFlag.set(true)
        worker?.interrupt()
        worker?.join(2000)
        worker = null
    }

    /** One connect->decode session; returns on disconnect, throws on error. */
    private fun runOnce(target: Negotiated, sink: FrameSink) {
        val uri = Uri.parse(rtspUrl)
        val port = if (uri.port > 0) uri.port else 554
        val nalQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
        val disconnected = AtomicBoolean(false)
        var sdp: RtspClient.SdpInfo? = null

        val socket = Socket()
        socket.connect(InetSocketAddress(uri.host, port), 5000)
        try {
            val listener = object : RtspClient.RtspClientListener {
                override fun onRtspConnecting() {}
                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    sdp = sdpInfo
                    status = "connected"
                }

                override fun onRtspVideoNalUnitReceived(
                    data: ByteArray, offset: Int, length: Int, timestamp: Long,
                ) {
                    if (length <= 0) return
                    val nal = data.copyOfRange(offset, offset + length)
                    // Live feed: drop the oldest, never let the queue back up.
                    while (!nalQueue.offer(nal)) nalQueue.poll()
                }

                override fun onRtspAudioSampleReceived(
                    data: ByteArray, offset: Int, length: Int, timestamp: Long,
                ) {}

                override fun onRtspApplicationDataReceived(
                    data: ByteArray, offset: Int, length: Int, timestamp: Long,
                ) {}

                override fun onRtspDisconnecting() {}
                override fun onRtspDisconnected() { disconnected.set(true) }
                override fun onRtspFailedUnauthorized() {
                    status = "camera auth failed"
                    disconnected.set(true)
                }
                override fun onRtspFailed(message: String?) {
                    status = "camera error: $message"
                    disconnected.set(true)
                }
            }

            var user: String? = null
            var pass: String? = null
            uri.userInfo?.split(":", limit = 2)?.let {
                user = it.getOrNull(0)
                pass = it.getOrNull(1)
            }

            val client = RtspClient.Builder(socket, rtspUrl, stopFlag, listener)
                .requestVideo(true)
                .requestAudio(false)
                .apply { if (user != null) withCredentials(user, pass ?: "") }
                .build()

            val rtspThread = thread(name = "rtsp-client") { client.execute() }

            // Wait for SDP (carries codec + SPS/PPS) before creating the decoder.
            val deadline = System.currentTimeMillis() + 10_000
            while (sdp?.videoTrack == null && !disconnected.get() && running) {
                if (System.currentTimeMillis() > deadline) error("no SDP within 10s")
                Thread.sleep(50)
            }
            val track = sdp?.videoTrack ?: error("disconnected before SDP")

            decodeLoop(track, nalQueue, disconnected, target, sink)
            rtspThread.join(2000)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun decodeLoop(
        track: RtspClient.VideoTrack,
        nalQueue: ArrayBlockingQueue<ByteArray>,
        disconnected: AtomicBoolean,
        target: Negotiated,
        sink: FrameSink,
    ) {
        val mime = if (track.videoCodec == RtspClient.VIDEO_CODEC_H265)
            MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, target.width, target.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            csd(track)?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        }
        val codec = MediaCodec.createDecoderByType(mime)
        val pacer = FramePacer(target.fps)
        var i420: ByteArray? = null
        var outBuf: ByteBuffer? = null
        var pts = 0L

        try {
            codec.configure(format, null, null, 0)
            codec.start()
            while (running && !disconnected.get()) {
                val nal = nalQueue.poll(20, TimeUnit.MILLISECONDS)
                if (nal != null) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.getInputBuffer(inIdx)!!.apply { clear(); put(nal) }
                        codec.queueInputBuffer(inIdx, 0, nal.size, pts, 0)
                        pts += 33_000
                    }
                }
                // Drain everything that's ready; only the newest matters.
                while (true) {
                    val info = MediaCodec.BufferInfo()
                    val outIdx = codec.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    val emit = pacer.shouldEmit(System.nanoTime())
                    if (emit) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            val w = image.width and 1.inv()
                            val h = image.height and 1.inv()
                            val size = Yuv.i420Size(w, h)
                            if (i420?.size != size) {
                                i420 = ByteArray(size)
                                outBuf = ByteBuffer.allocateDirect(size)
                            }
                            val planes = image.planes
                            Yuv.toI420(
                                w, h,
                                arrayOf(planes[0].buffer, planes[1].buffer, planes[2].buffer),
                                intArrayOf(planes[0].rowStride, planes[1].rowStride, planes[2].rowStride),
                                intArrayOf(planes[0].pixelStride, planes[1].pixelStride, planes[2].pixelStride),
                                i420!!,
                            )
                            image.close()
                            outBuf!!.clear()
                            outBuf!!.put(i420!!)
                            outBuf!!.flip()
                            sink.onFrame(outBuf!!, w, h)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** csd-0 blob: Annex-B start codes + (VPS+)SPS+PPS from the SDP. */
    private fun csd(track: RtspClient.VideoTrack): ByteArray? {
        val sps = track.sps ?: return null
        val pps = track.pps ?: return null
        val start = byteArrayOf(0, 0, 0, 1)
        val vps = if (track.videoCodec == RtspClient.VIDEO_CODEC_H265) track.vps else null
        var out = ByteArray(0)
        if (vps != null) out += start + vps
        out += start + sps + start + pps
        return out
    }

    companion object {
        private const val TAG = "RtspVideoSource"
        private const val QUEUE_CAP = 60
    }
}
