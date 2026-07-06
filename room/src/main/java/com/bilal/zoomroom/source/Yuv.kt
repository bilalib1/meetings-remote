package com.bilal.zoomroom.source

import java.nio.ByteBuffer

/**
 * YUV plane repacking: MediaCodec flexible-YUV output (arbitrary row/pixel
 * strides, NV12 or planar) -> packed I420 as the Zoom SDK expects.
 * Pure JVM code so it unit-tests without a device.
 */
object Yuv {

    fun i420Size(width: Int, height: Int): Int =
        width * height + 2 * chromaWidth(width) * chromaHeight(height)

    fun chromaWidth(width: Int) = (width + 1) / 2
    fun chromaHeight(height: Int) = (height + 1) / 2

    /**
     * Copies one plane out of a strided source into [dst] starting at
     * [dstOff], tightly packed. Returns the offset after the copied plane.
     */
    fun copyPlane(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
        dstOff: Int,
    ): Int {
        var o = dstOff
        if (pixelStride == 1) {
            val row = ByteArray(width)
            for (y in 0 until height) {
                val s = src.duplicate()
                s.position(y * rowStride)
                s.get(row, 0, width)
                System.arraycopy(row, 0, dst, o, width)
                o += width
            }
        } else {
            for (y in 0 until height) {
                val base = y * rowStride
                for (x in 0 until width) {
                    dst[o++] = src.get(base + x * pixelStride)
                }
            }
        }
        return o
    }

    /**
     * Repacks three flexible-YUV planes (Y, U, V order, as from
     * Image.getPlanes()) into one packed I420 array of i420Size() bytes.
     */
    fun toI420(
        width: Int,
        height: Int,
        planes: Array<ByteBuffer>,
        rowStrides: IntArray,
        pixelStrides: IntArray,
        dst: ByteArray,
    ) {
        var o = copyPlane(planes[0], rowStrides[0], pixelStrides[0], width, height, dst, 0)
        val cw = chromaWidth(width)
        val ch = chromaHeight(height)
        o = copyPlane(planes[1], rowStrides[1], pixelStrides[1], cw, ch, dst, o)
        copyPlane(planes[2], rowStrides[2], pixelStrides[2], cw, ch, dst, o)
    }
}
