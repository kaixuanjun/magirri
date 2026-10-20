package com.magirri.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlin.math.abs

/**
 * 判断一段素材是不是 Magic-Log（灰片）。
 *
 * 为什么要它：第三方 App 拿不到荣耀的 Log 开关，用户很容易以为自己开了、
 * 其实录的是普通视频。灰片有三个稳定特征，直接量就行：
 *
 * 1. **黑位被抬高**——Log 曲线不会到 0，暗部码值停在 0.2 以上；
 * 2. **高光被压低**——峰值很少过 0.9；
 * 3. **饱和度低**——灰片整体发灰。
 *
 * 只看亮度分位数很容易被"雾天/逆光"骗到，所以三个一起看。
 */
object LogDetector {

    private const val TAG = "magirri"

    enum class Verdict {
        LOG,        // 像 Magic-Log
        NORMAL,     // 像普通 SDR 视频（没开 Log）
        UNKNOWN,    // 拿不准（比如极暗或极亮的场景）
    }

    data class Result(
        val verdict: Verdict,
        val black: Float,     // 1% 分位
        val white: Float,     // 99% 分位
        val saturation: Float,
    )

    fun detect(ctx: Context, uri: Uri, samples: Int = 3): Result? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (durMs <= 0) return null

            val lo = ArrayList<Float>()
            val hi = ArrayList<Float>()
            val sat = ArrayList<Float>()
            for (k in 1..samples) {
                val bmp = frame(mmr, durMs * 1000L * k / (samples + 1)) ?: continue
                collect(bmp, lo, sat)
                bmp.recycle()
            }
            if (lo.size < 128) return null
            lo.sort()
            sat.sort()
            val black = lo[(lo.size * 0.01).toInt().coerceIn(0, lo.size - 1)]
            val white = lo[(lo.size * 0.99).toInt().coerceIn(0, lo.size - 1)]
            val saturation = sat[sat.size / 2]

            // 黑位是最硬的指标：Log 曲线压不到 0，实测真机灰片黑位在 0.29 左右，
            // 普通视频在 0.09 左右，中间留一条"不确定"带，宁可说不知道也别误判。
            val verdict = when {
                black >= 0.20f && white <= 0.90f -> Verdict.LOG
                black <= 0.11f -> Verdict.NORMAL
                black >= 0.16f && saturation < 0.10f -> Verdict.LOG
                else -> Verdict.UNKNOWN
            }
            Log.i(TAG, "log-detect black=$black white=$white sat=$saturation -> $verdict")
            return Result(verdict, black, white, saturation)
        } catch (e: Throwable) {
            Log.w(TAG, "log-detect 失败: ${e.message}")
            return null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun frame(mmr: MediaMetadataRetriever, tUs: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
        } else {
            mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (e: Throwable) {
        null
    }

    /** 收集亮度与饱和度（每 4 像素取一个，够统计了）。 */
    private fun collect(bmp: Bitmap, luma: ArrayList<Float>, sat: ArrayList<Float>) {
        val w = bmp.width
        val h = bmp.height
        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val p = bmp.getPixel(x, y)
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                luma.add(0.2126f * r + 0.7152f * g + 0.0722f * b)
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                sat.add(if (mx <= 0f) 0f else (mx - mn) / mx)
            }
        }
    }
}