package com.magirri.lume

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把相机的一张 YUV 帧变成套好 LUT 的 JPEG。
 *
 * 全流程只用两块 IntArray：YUV→ARGB 一块、旋转+LUT 输出一块（8MP 时各 ~32MB），
 * 中间不产生额外 Bitmap 拷贝。旋转在写目标数组时一并做掉，省掉 Matrix 那一次复制。
 */
object PhotoProcessor {

    /** BT.709 有限范围 → 0..1 的 RGB */
    fun yuvToArgb(image: Image, strength: Float, lut: Lut3D): Triple<IntArray, Int, Int> {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val out = IntArray(w * h)
        val rgb = FloatArray(3)
        val tmp = FloatArray(3)

        for (y in 0 until h) {
            val yBase = y * yRow
            val uvBase = (y / 2)
            for (x in 0 until w) {
                val yv = (yBuf.get(yBase + x * yPix).toInt() and 0xFF).toFloat()
                val ui = (uvBase * uRow) + (x / 2) * uPix
                val vi = (uvBase * vRow) + (x / 2) * vPix
                val uv = (uBuf.get(ui).toInt() and 0xFF).toFloat()
                val vv = (vBuf.get(vi).toInt() and 0xFF).toFloat()

                // 有限范围：Y 16..235，UV 16..240
                val yy = ((yv - 16f) / 219f).coerceIn(0f, 1f)
                val uu = ((uv - 128f) / 224f)
                val ww = ((vv - 128f) / 224f)

                var r = yy + 1.5748f * ww
                var g = yy - 0.1873f * uu - 0.4681f * ww
                var b = yy + 1.8556f * uu
                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                if (strength >= 0.999f) {
                    lut.sampleInto(r, g, b, rgb)
                } else {
                    lut.sampleInto(r, g, b, tmp)
                    rgb[0] = r + (tmp[0] - r) * strength
                    rgb[1] = g + (tmp[1] - g) * strength
                    rgb[2] = b + (tmp[2] - b) * strength
                }
                out[y * w + x] = 0xFF shl 24 or
                        ((rgb[0] * 255f).toInt().coerceIn(0, 255) shl 16) or
                        ((rgb[1] * 255f).toInt().coerceIn(0, 255) shl 8) or
                        (rgb[2] * 255f).toInt().coerceIn(0, 255)
            }
        }
        return Triple(out, w, h)
    }

    /** 按 90/180/270 旋转 ARGB 数组（返回新数组，尺寸可能对调） */
    fun rotate(argb: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return Triple(argb, w, h)
        return if (d == 180) {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                out[(h - 1 - y) * w + (w - 1 - x)] = argb[y * w + x]
            }
            Triple(out, w, h)
        } else {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = argb[y * w + x]
                if (d == 90) {
                    // 顺时针 90：目标 (x', y') = (h-1-y, x)，dst 宽 = h
                    out[x * h + (h - 1 - y)] = p
                } else {
                    // 逆时针 90
                    out[(w - 1 - x) * h + y] = p
                }
            }
            Triple(out, h, w)
        }
    }

    /** 按目标画幅居中裁切（不缩放） */
    fun cropToAspect(
        argb: IntArray, w: Int, h: Int, target: Float,
    ): Triple<IntArray, Int, Int> {
        val cur = w.toFloat() / h
        if (Math.abs(cur - target) < 0.01f) return Triple(argb, w, h)
        val nw: Int
        val nh: Int
        if (cur > target) {
            nh = h
            nw = (h * target).toInt().coerceAtMost(w)
        } else {
            nw = w
            nh = (w / target).toInt().coerceAtMost(h)
        }
        val ox = (w - nw) / 2
        val oy = (h - nh) / 2
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            System.arraycopy(argb, (y + oy) * w + ox, out, y * nw, nw)
        }
        return Triple(out, nw, nh)
    }

    fun saveJpeg(context: Context, argb: IntArray, w: Int, h: Int, quality: Int = 95): Uri? {
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "ARRI_$stamp.jpg"
        val resolver = context.contentResolver

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bitmap.recycle()
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ArcCam"
            ).apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }
            bitmap.recycle()
            Uri.fromFile(file)
        }
    }
}
