package com.bilal.zoomroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import java.nio.ByteBuffer

/**
 * Zoom-style self-view: shows our own outgoing camera (the external RTSP source)
 * by drawing the decoded I420 frames directly. The Meeting SDK renders our
 * external source as black in its own video units, so we render it ourselves.
 *
 * submit() is called on the decode/send thread with a reused buffer, so it
 * copies synchronously, then converts (downsampled) to a bitmap off-thread.
 */
class SelfPreviewView(context: Context) : View(context) {

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val radius = 16f * resources.displayMetrics.density
    private val dst = Rect()
    private val border = RectF()
    @Volatile private var bitmap: Bitmap? = null

    private val lock = Any()
    private var snap = ByteArray(0)
    @Volatile private var converting = false
    private var lastMs = 0L

    private val thread = HandlerThread("self-preview").apply { start() }
    private val work = Handler(thread.looper)

    init {
        setBackgroundColor(0xFF15181F.toInt()) // dark placeholder until first frame
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) =
                o.setRoundRect(0, 0, v.width, v.height, radius)
        }
        clipToOutline = true
    }

    /** Packed I420 frame (buffer is reused by the caller — copy now). */
    fun submit(buffer: ByteBuffer, w: Int, h: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < 66 || converting) return // ~15 fps, coalesce
        lastMs = now
        val size = w * h * 3 / 2
        synchronized(lock) {
            if (snap.size != size) snap = ByteArray(size)
            buffer.duplicate().apply { clear() }.get(snap, 0, size)
        }
        converting = true
        work.post {
            val bmp = synchronized(lock) { i420ToBitmap(snap, w, h) }
            bitmap = bmp
            converting = false
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(it, null, dst, bmpPaint)
        }
        val inset = borderPaint.strokeWidth / 2f
        border.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(border, radius, radius, borderPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        thread.quitSafely()
    }

    companion object {
        private const val TARGET_W = 320 // downsample target; preview is small

        private fun i420ToBitmap(d: ByteArray, w: Int, h: Int): Bitmap {
            val step = maxOf(1, w / TARGET_W)
            val ow = w / step
            val oh = h / step
            val px = IntArray(ow * oh)
            val cW = w / 2
            val uOff = w * h
            val vOff = uOff + cW * (h / 2)
            var o = 0
            var sy = 0
            while (sy < oh) {
                val y = sy * step
                val cRow = (y / 2) * cW
                var sx = 0
                while (sx < ow) {
                    val x = sx * step
                    val yy = d[y * w + x].toInt() and 0xFF
                    val uu = (d[uOff + cRow + x / 2].toInt() and 0xFF) - 128
                    val vv = (d[vOff + cRow + x / 2].toInt() and 0xFF) - 128
                    val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                    px[o++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    sx++
                }
                sy++
            }
            return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        }
    }
}
