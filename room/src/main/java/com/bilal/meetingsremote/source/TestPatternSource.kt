package com.bilal.zoomroom.source

import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Synthetic I420 source (plan step 3): horizontal luma gradient background, a
 * red bar sweeping left-to-right once per ~4 s, and a white tick block whose
 * vertical position steps with wall-clock seconds — enough to eyeball both
 * motion and rough latency on the far end.
 */
class TestPatternSource : VideoSourceProvider {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun start(target: Negotiated, sink: FrameSink) {
        stop()
        running = true
        worker = thread(name = "test-pattern") { renderLoop(target, sink) }
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    private fun renderLoop(target: Negotiated, sink: FrameSink) {
        val w = target.width and 1.inv()
        val h = target.height and 1.inv()
        val fps = if (target.fps in 1..60) target.fps else 30
        val ySize = w * h
        val cSize = (w / 2) * (h / 2)
        val frame = ByteArray(ySize + 2 * cSize)
        val buf = ByteBuffer.allocateDirect(frame.size)
        val intervalNs = 1_000_000_000L / fps
        var frameIdx = 0L
        var nextNs = System.nanoTime()

        while (running) {
            render(frame, w, h, frameIdx)
            buf.clear()
            buf.put(frame)
            buf.flip()
            sink.onFrame(buf, w, h)
            frameIdx++

            nextNs += intervalNs
            val sleepNs = nextNs - System.nanoTime()
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    return
                }
            } else {
                nextNs = System.nanoTime() // fell behind; don't try to catch up
            }
        }
    }

    private fun render(frame: ByteArray, w: Int, h: Int, frameIdx: Long) {
        val ySize = w * h
        val cw = w / 2
        val ch = h / 2

        // Y: gradient background.
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                frame[rowBase + x] = (16 + x * 200 / w).toByte()
            }
        }
        // Chroma: neutral gray.
        java.util.Arrays.fill(frame, ySize, frame.size, 128.toByte())

        // Sweeping red bar.
        val barW = w / 16
        val barX = ((frameIdx * 8) % w).toInt()
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in barX until minOf(barX + barW, w)) frame[rowBase + x] = 81
        }
        val uBase = ySize
        val vBase = ySize + cw * ch
        for (cy in 0 until ch) {
            val rowBase = cy * cw
            for (cx in barX / 2 until minOf((barX + barW) / 2, cw)) {
                frame[uBase + rowBase + cx] = 90
                frame[vBase + rowBase + cx] = 240.toByte()
            }
        }

        // Seconds tick: white block, row = seconds % 10.
        val sec = (System.currentTimeMillis() / 1000 % 10).toInt()
        val blockH = h / 12
        val top = sec * (h - blockH) / 9
        for (y in top until top + blockH) {
            val rowBase = y * w
            for (x in 0 until w / 10) frame[rowBase + x] = 235.toByte()
        }
    }
}
