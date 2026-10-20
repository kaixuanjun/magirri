package com.magirri.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Iridas `.cube` 的 3D LUT。
 *
 * 布局：与 OpenGL 期望的完全一致——`((b * N + g) * N + r) * 3 + c`，
 * 也就是 **r 变化最快**（文件里的行序天然就是这个顺序），
 * 可以直接 `glTexImage3D` 上传，不需要 CPU 侧转置。
 */
class Lut3D(
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val title: String,
) {
    init {
        require(size >= 2) { "LUT 尺寸至少 2" }
        require(data.size == size * size * size * 3) {
            "LUT 数据长度不匹配: ${data.size} != ${size}^3*3"
        }
    }

    fun sampleAt(r: Float, g: Float, b: Float): FloatArray {
        val n = size
        fun axis(v: Float, c: Int): Pair<Int, Float> {
            val lo = domainMin[c]
            val hi = domainMax[c]
            val span = if (hi - lo == 0f) 1f else hi - lo
            val q = ((v - lo) / span).coerceIn(0f, 1f) * (n - 1)
            val i = q.toInt().coerceIn(0, n - 2)
            return i to (q - i)
        }
        val (ir, fr) = axis(r, 0)
        val (ig, fg) = axis(g, 1)
        val (ib, fb) = axis(b, 2)
        val out = FloatArray(3)
        for (c in 0 until 3) {
            fun at(rr: Int, gg: Int, bb: Int) = data[((bb * n + gg) * n + rr) * 3 + c]
            val c00 = at(ir, ig, ib) * (1 - fr) + at(ir + 1, ig, ib) * fr
            val c10 = at(ir, ig + 1, ib) * (1 - fr) + at(ir + 1, ig + 1, ib) * fr
            val c01 = at(ir, ig, ib + 1) * (1 - fr) + at(ir + 1, ig, ib + 1) * fr
            val c11 = at(ir, ig + 1, ib + 1) * (1 - fr) + at(ir + 1, ig + 1, ib + 1) * fr
            val c0 = c00 * (1 - fg) + c10 * fg
            val c1 = c01 * (1 - fg) + c11 * fg
            out[c] = c0 * (1 - fb) + c1 * fb
        }
        return out
    }

    /** 灰轴（中性色）在输入码值 0..1 上的响应，用于自检。 */
    fun grayAxis(steps: Int = 33): FloatArray {
        val v = FloatArray(steps)
        for (i in 0 until steps) {
            val x = i / (steps - 1f)
            v[i] = sampleAt(x, x, x)[0]
        }
        return v
    }

    fun toDirectBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(data)
        buf.rewind()
        return buf
    }

    /**
     * 打包成 **2D 平铺** 纹理：宽 `n*n`、高 `n`。
     *
     * 像素 (x, y) 对应 (r = x % n, g = y, b = x / n)。
     *
     * 为什么不用 3D 纹理：`GL_TEXTURE_3D` 在模拟器的 SwiftShader 上能创建成功、
     * 采样却恒为 0（试过 RGB16F / RGBA16F，都一样）。2D 平铺是 GLES2 时代就有的
     * 老办法，任何驱动都能跑；着色器里取相邻两片再插值，精度与 3D 三线性等价。
     */
    fun toTiled2dFloat(): ByteBuffer {
        val n = size
        val w = n * n
        val buf = ByteBuffer.allocateDirect(w * n * 4 * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (g in 0 until n) {
            for (x in 0 until w) {
                val b = x / n
                val r = x % n
                fb.put(data[((b * n + g) * n + r) * 3])
                fb.put(data[((b * n + g) * n + r) * 3 + 1])
                fb.put(data[((b * n + g) * n + r) * 3 + 2])
                fb.put(1f)
            }
        }
        fb.rewind()
        buf.rewind()
        return buf
    }

    /** 8-bit 兜底版的 2D 平铺。 */
    fun toTiled2dByte(): ByteBuffer {
        val n = size
        val w = n * n
        val buf = ByteBuffer.allocateDirect(w * n * 4).order(ByteOrder.nativeOrder())
        for (g in 0 until n) {
            for (x in 0 until w) {
                val b = x / n
                val r = x % n
                for (c in 0 until 3) {
                    val v = (data[((b * n + g) * n + r) * 3 + c].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    buf.put(v.toByte())
                }
                buf.put(255.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    /**
     * 打包成 RGBA float（alpha 填 1）。
     *
     * 用 RGBA 而不是 RGB 上传：`GL_RGB16F` 的 3D 纹理在部分驱动（含模拟器的
     * SwiftShader）上能创建成功、采样却返回全黑，换成 RGBA16F 才正常。
     */
    fun toRgbaBuffer(): ByteBuffer {
        val pix = data.size / 3
        val buf = ByteBuffer.allocateDirect(pix * 4 * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (i in 0 until pix) {
            fb.put(data[i * 3])
            fb.put(data[i * 3 + 1])
            fb.put(data[i * 3 + 2])
            fb.put(1f)
        }
        fb.rewind()
        buf.rewind()
        return buf
    }

    /** 8-bit 兜底版本（RGBA8 3D 纹理几乎所有设备都支持，代价是 LUT 精度降到 8-bit）。 */
    fun toRgba8Buffer(): ByteBuffer {
        val pix = data.size / 3
        val buf = ByteBuffer.allocateDirect(pix * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until pix) {
            for (c in 0 until 3) {
                val v = (data[i * 3 + c].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                buf.put(v.toByte())
            }
            buf.put(255.toByte())
        }
        buf.rewind()
        return buf
    }

    companion object {
        /**
         * 解析 `.cube`。
         *
         * 注意 `LUT_3D_SIZE` 里含有数字 "3"，必须只取**关键字之后**的数值，
         * 否则 33³ 会被读成 3³ —— 这是自写解析器最常见的坑。
         */
        fun parse(text: String): Lut3D {
            var size = 0
            var title = ""
            var dmin: FloatArray? = null
            var dmax: FloatArray? = null
            val values = ArrayList<Float>(1024)

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val head = line.substringBefore(' ').uppercase()
                when (head) {
                    "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                    "LUT_3D_SIZE" -> size = line.substringAfter(' ').trim().split(' ')[0].toInt()
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("不支持 1D LUT，请提供 3D LUT")
                    "DOMAIN_MIN" -> dmin = line.substringAfter(' ').trim()
                        .split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
                    "DOMAIN_MAX" -> dmax = line.substringAfter(' ').trim()
                        .split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
                    else -> {
                        // 数据行：可能是 "r g b"，也可能一行多个（容错）
                        val parts = line.split(Regex("\\s+"))
                        for (p in parts) {
                            values.add(p.toFloatOrNull() ?: throw IllegalArgumentException("无法解析: $line"))
                        }
                    }
                }
            }
            require(size >= 2) { "缺少 LUT_3D_SIZE" }
            val expect = size * size * size * 3
            require(values.size == expect) { "数据量不匹配: 期望 $expect, 实际 ${values.size}" }
            val data = FloatArray(expect)
            for (i in 0 until expect) data[i] = values[i]
            return Lut3D(
                size = size,
                data = data,
                domainMin = dmin ?: floatArrayOf(0f, 0f, 0f),
                domainMax = dmax ?: floatArrayOf(1f, 1f, 1f),
                title = title,
            )
        }
    }
}
