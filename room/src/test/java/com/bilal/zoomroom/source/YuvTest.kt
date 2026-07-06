package com.bilal.zoomroom.source

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class YuvTest {

    @Test
    fun i420SizeHandlesOddResolutions() {
        assertEquals(640 * 360 * 3 / 2, Yuv.i420Size(640, 360))
        // 641x361: chroma planes round up to 321x181.
        assertEquals(641 * 361 + 2 * 321 * 181, Yuv.i420Size(641, 361))
    }

    @Test
    fun copyPlaneStripsRowPadding() {
        val w = 4
        val h = 2
        val rowStride = 6 // 2 bytes padding per row
        val src = ByteBuffer.allocate(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            src.put(y * rowStride + x, (10 * y + x).toByte())
        }
        val dst = ByteArray(w * h)
        val end = Yuv.copyPlane(src, rowStride, 1, w, h, dst, 0)
        assertEquals(w * h, end)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13), dst)
    }

    @Test
    fun copyPlaneDeinterleavesPixelStride2() {
        // NV12-style interleaved chroma: U at even indices, V at odd.
        val w = 3
        val h = 2
        val rowStride = 8
        val src = ByteBuffer.allocate(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            src.put(y * rowStride + x * 2, (100 + 10 * y + x).toByte())
        }
        val dst = ByteArray(w * h)
        Yuv.copyPlane(src, rowStride, 2, w, h, dst, 0)
        assertArrayEquals(
            byteArrayOf(100, 101, 102, 110, 111, 112),
            dst,
        )
    }

    @Test
    fun toI420PacksAllThreePlanes() {
        val w = 4
        val h = 4
        val cw = 2
        val ch = 2
        val y = ByteBuffer.allocate(w * h)
        for (i in 0 until w * h) y.put(i, i.toByte())
        // Interleaved U/V sharing one buffer view each (NV12 flexible output).
        val u = ByteBuffer.allocate(cw * ch * 2)
        val v = ByteBuffer.allocate(cw * ch * 2)
        for (i in 0 until cw * ch) {
            u.put(i * 2, (50 + i).toByte())
            v.put(i * 2, (60 + i).toByte())
        }
        val dst = ByteArray(Yuv.i420Size(w, h))
        Yuv.toI420(
            w, h,
            arrayOf(y, u, v),
            intArrayOf(w, cw * 2, cw * 2),
            intArrayOf(1, 2, 2),
            dst,
        )
        for (i in 0 until w * h) assertEquals(i.toByte(), dst[i])
        for (i in 0 until cw * ch) {
            assertEquals((50 + i).toByte(), dst[w * h + i])
            assertEquals((60 + i).toByte(), dst[w * h + cw * ch + i])
        }
    }
}
