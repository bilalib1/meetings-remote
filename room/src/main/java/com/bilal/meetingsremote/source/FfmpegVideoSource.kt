package com.bilal.meetingsremote.source

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

    /**
     * Camera-audio tap for the AV-sync estimator: decoded 16 kHz mono s16
     * samples, each block tagged with the wall-clock ns at which that audio is
     * "on screen" — i.e. mapped through the mux timeline onto the moment the
     * equal-PTS video frame left for Zoom. Called on the pump thread.
     */
    @Volatile var audioTap: ((samples: ShortArray, count: Int, wallNs: Long) -> Unit)? = null
    @Volatile var hasAudio = false
        private set
    // Min-filtered (wall - videoPts) mapping for the audio tap; reset per connect.
    private var mapOffsetNs = Long.MIN_VALUE

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
        // Pacing to the negotiated fps happens in native, keyed off the stream's
        // own PTS: MediaCodec delivers frames in bursts, so wall-clock arrival
        // time here carries no cadence (measured 2026-07-08, plan §19).
        val noPace = sysProp("debug.room.nopace") == "1"
        while (running) {
            status = "connecting"
            val h = nativeOpen(rtspUrl, target.width, target.height,
                if (noPace) 0 else target.fps)
            if (h == 0L) {
                status = "camera offline — reconnecting"
                if (!sleep(backoffMs)) return
                backoffMs = minOf(backoffMs * 2, 10_000L)
                continue
            }
            handle = h
            backoffMs = 1000L
            status = "connected"
            hasAudio = nativeHasAudio(h)
            mapOffsetNs = Long.MIN_VALUE
            val w = nativeWidth(h)
            val hgt = nativeHeight(h)
            val frame = ByteArray(w * hgt * 3 / 2)
            val aBuf = ShortArray(4096)
            val aPts = LongArray(1)
            val buf = ByteBuffer.allocateDirect(frame.size)
            var total = 0L
            var emits = 0L; var lastArrive = 0L
            // Arrival-gap buckets around the expected ~33 ms of a 30 fps source:
            // <20 ms = burst pair, >45 ms = stall. A healthy smooth source puts
            // nearly everything in the middle bucket.
            var dtFast = 0; var dtMid = 0; var dtSlow = 0; var dtMax = 0L
            var win = System.nanoTime()
            try {
                while (running) {
                    val n = nativeNextFrame(h, frame)
                    if (n <= 0) break // EOF / disconnect
                    val arr = System.nanoTime()
                    if (lastArrive != 0L) {
                        val d = arr - lastArrive
                        if (d > dtMax) dtMax = d
                        when {
                            d < 20_000_000L -> dtFast++
                            d <= 45_000_000L -> dtMid++
                            else -> dtSlow++
                        }
                    }
                    lastArrive = arr
                    emits++; total++
                    buf.clear(); buf.put(frame, 0, n); buf.flip()
                    sink.onFrame(buf, w, hgt)
                    // Drain camera audio, placing each block on the wall clock
                    // via the mux timeline: video with PTS v leaves ~now, so
                    // audio with PTS a is "on screen" at now + (a - v). Frame
                    // arrivals burst (dt max >100 ms), so the PTS->wall offset
                    // is min-filtered: track the least-delayed frame seen and
                    // creep upward slowly to follow real latency increases —
                    // a stable timeline is what GCC-PHAT needs.
                    val tap = audioTap
                    if (tap != null && hasAudio) {
                        val vPts = nativeLastVideoPtsUs(h)
                        if (vPts != Long.MIN_VALUE) {
                            val cand = arr - vPts * 1000L
                            mapOffsetNs = when {
                                mapOffsetNs == Long.MIN_VALUE || cand < mapOffsetNs -> cand
                                else -> mapOffsetNs + ((cand - mapOffsetNs) * 0.005).toLong()
                            }
                            while (true) {
                                val an = nativeReadAudio(h, aBuf, aPts)
                                if (an <= 0) break
                                if (aPts[0] == Long.MIN_VALUE) continue
                                tap(aBuf.copyOf(an), an, mapOffsetNs + aPts[0] * 1000L)
                            }
                        }
                    }
                    if (arr - win >= 3_000_000_000L) {
                        val el = (arr - win) / 1e9
                        Log.i(TAG, "pump: emitted $total frames ${w}x$hgt | " +
                            "emit=${"%.1f".format(emits/el)} fps | " +
                            "dt(ms) <20:$dtFast 20-45:$dtMid >45:$dtSlow max:${dtMax/1_000_000} " +
                            "target=${target.fps} nopace=$noPace")
                        emits = 0; dtFast = 0; dtMid = 0; dtSlow = 0; dtMax = 0; win = arr
                    }
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

    private external fun nativeOpen(url: String, wantW: Int, wantH: Int, paceFps: Int): Long
    private external fun nativeNextFrame(handle: Long, out: ByteArray): Int
    private external fun nativeHasAudio(handle: Long): Boolean
    private external fun nativeReadAudio(handle: Long, out: ShortArray, ptsUs: LongArray): Int
    private external fun nativeLastVideoPtsUs(handle: Long): Long
    private external fun nativeWidth(handle: Long): Int
    private external fun nativeHeight(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        private const val TAG = "FfmpegVideoSource"

        /** Experiment toggle, same idea as debug.room.swdec in the native layer. */
        private fun sysProp(name: String): String = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, name) as? String ?: ""
        } catch (_: Exception) { "" }

        init {
            // FFmpeg libs load first (dependency order), then our wrapper.
            for (lib in listOf("avutil", "swresample", "avcodec", "swscale", "avformat", "rtspdecoder")) {
                System.loadLibrary(lib)
            }
        }
    }
}
