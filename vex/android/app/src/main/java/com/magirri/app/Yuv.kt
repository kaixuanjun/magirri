package com.magirri.app

import java.nio.ByteBuffer

/**
 * RGBA → YUV420 的转换（BT.709 有限范围）。
 *
 * 为什么需要它：`COLOR_FormatSurface` 编码是快路径，但要求 gralloc 能分配
 * `YCBCR_420_888 + HW_VIDEO_ENCODER` 的图形缓冲。部分机型（和所有模拟器）不支持，
 * 这时就退化成"自己把像素转成 YUV 再喂给编码器"的缓冲模式。
 *
 * 系数是 BT.709 的：Y = 16 + 219/255 * (0.2126R + 0.7152G + 0.0722B)，
 * U/V 同理用 224/255 的色度缩放。写成 16.16 定点，整数运算跑得快。
 */
object Yuv {

    // BT.709 有限范围的 16.16 定点系数
    const val YR = 11965      // 0.18258 * 65536
    const val YG = 40255
    const val YB = 4064
    const val UR = -6597
    const val UG = -22187
    const val UB = 28784
    const val VR = 28784
    const val VG = -26145
    const val VB = -2637

    /** 把一行 RGBA 转成 Y（写进 dst 的 yOff 起）。 */
    private fun lumaRow(
        src: ByteArray, srcOff: Int, width: Int,
        dst: ByteArray, dstOff: Int,
    ) {
        var s = srcOff
        var d = dstOff
        for (x in 0 until width) {
            val r = src[s].toInt() and 0xFF
            val g = src[s + 1].toInt() and 0xFF
            val b = src[s + 2].toInt() and 0xFF
            val y = 16 + ((YR * r + YG * g + YB * b) shr 16)
            dst[d] = y.coerceIn(16, 235).toByte()
            s += 4
            d++
        }
    }

    /**
     * 转成 **NV12**（Y 平面 + 交错的 UV），Android 编码器最常见的布局。
     *
     * @param src RGBA 数据（已按 rowStride 去掉行尾填充）
     * @param dst 目标缓冲，长度需 >= dstStride*dstSlice*3/2
     */
    fun rgbaToNv12(
        src: ByteArray, width: Int, height: Int,
        dst: ByteArray, dstStride: Int, dstSlice: Int,
    ) {
        val usableH = minOf(height, dstSlice)
        for (y in 0 until usableH) {
            lumaRow(src, y * width * 4, width, dst, y * dstStride)
        }
        // UV 平面：每 2x2 取一个色度样本（不做平均，省一半算力，画质差异 <1 码值）
        val uvBase = dstStride * dstSlice
        var row = 0
        while (row < usableH) {
            val yRow = row * width * 4
            val yRow2 = minOf(row + 1, usableH - 1) * width * 4
            var d = uvBase + (row / 2) * dstStride
            var x = 0
            while (x < width) {
                val x2 = minOf(x + 1, width - 1)
                val i = yRow + x * 4
                val i2 = yRow + x2 * 4
                val j = yRow2 + x * 4
                val j2 = yRow2 + x2 * 4
                // 2x2 四点的平均，够准又不慢
                val r = ((src[i].toInt() and 0xFF) + (src[i2].toInt() and 0xFF) +
                        (src[j].toInt() and 0xFF) + (src[j2].toInt() and 0xFF)) shr 2
                val g = ((src[i + 1].toInt() and 0xFF) + (src[i2 + 1].toInt() and 0xFF) +
                        (src[j + 1].toInt() and 0xFF) + (src[j2 + 1].toInt() and 0xFF)) shr 2
                val b = ((src[i + 2].toInt() and 0xFF) + (src[i2 + 2].toInt() and 0xFF) +
                        (src[j + 2].toInt() and 0xFF) + (src[j2 + 2].toInt() and 0xFF)) shr 2
                val u = 128 + ((UR * r + UG * g + UB * b) shr 16)
                val v = 128 + ((VR * r + VG * g + VB * b) shr 16)
                dst[d] = u.coerceIn(16, 240).toByte()
                dst[d + 1] = v.coerceIn(16, 240).toByte()
                d += 2
                x += 2
            }
            row += 2
        }
    }

    /** 转成 **I420**（Y/U/V 三个平面）。 */
    fun rgbaToI420(
        src: ByteArray, width: Int, height: Int,
        dst: ByteArray, dstStride: Int, dstSlice: Int,
    ) {
        val usableH = minOf(height, dstSlice)
        for (y in 0 until usableH) {
            lumaRow(src, y * width * 4, width, dst, y * dstStride)
        }
        val ySize = dstStride * dstSlice
        val uSize = (dstStride / 2) * (dstSlice / 2)
        val uBase = ySize
        val vBase = ySize + uSize
        var row = 0
        while (row < usableH) {
            val yRow = row * width * 4
            val yRow2 = minOf(row + 1, usableH - 1) * width * 4
            var du = uBase + (row / 2) * (dstStride / 2)
            var dv = vBase + (row / 2) * (dstStride / 2)
            var x = 0
            while (x < width) {
                val x2 = minOf(x + 1, width - 1)
                val i = yRow + x * 4
                val i2 = yRow + x2 * 4
                val j = yRow2 + x * 4
                val j2 = yRow2 + x2 * 4
                val r = ((src[i].toInt() and 0xFF) + (src[i2].toInt() and 0xFF) +
                        (src[j].toInt() and 0xFF) + (src[j2].toInt() and 0xFF)) shr 2
                val g = ((src[i + 1].toInt() and 0xFF) + (src[i2 + 1].toInt() and 0xFF) +
                        (src[j + 1].toInt() and 0xFF) + (src[j2 + 1].toInt() and 0xFF)) shr 2
                val b = ((src[i + 2].toInt() and 0xFF) + (src[i2 + 2].toInt() and 0xFF) +
                        (src[j + 2].toInt() and 0xFF) + (src[j2 + 2].toInt() and 0xFF)) shr 2
                dst[du] = (128 + ((UR * r + UG * g + UB * b) shr 16)).coerceIn(16, 240).toByte()
                dst[dv] = (128 + ((VR * r + VG * g + VB * b) shr 16)).coerceIn(16, 240).toByte()
                du++
                dv++
                x += 2
            }
            row += 2
        }
    }

    /** 把 ImageReader 的一个 RGBA plane 压成无行填充的连续数组。 */
    fun extractRgba(
        plane: ByteBuffer, rowStride: Int, pixelStride: Int,
        width: Int, height: Int, out: ByteArray,
    ) {
        var dst = 0
        if (pixelStride == 4 && rowStride == width * 4) {
            plane.position(0)
            plane.get(out, 0, width * height * 4)
            return
        }
        val rowBytes = width * 4
        val tmp = ByteArray(maxOf(rowBytes, rowStride))
        for (y in 0 until height) {
            plane.position(y * rowStride)
            val n = minOf(rowBytes, plane.remaining())
            plane.get(tmp, 0, n)
            if (pixelStride == 4) {
                System.arraycopy(tmp, 0, out, dst, rowBytes)
            } else {
                var s = 0
                var d = dst
                for (x in 0 until width) {
                    out[d] = tmp[s]
                    out[d + 1] = tmp[s + 1]
                    out[d + 2] = tmp[s + 2]
                    out[d + 3] = 0xFF.toByte()
                    s += pixelStride
                    d += 4
                }
            }
            dst += rowBytes
        }
    }
}
