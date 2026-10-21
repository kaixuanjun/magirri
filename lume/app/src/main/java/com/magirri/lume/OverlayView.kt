package com.magirri.lume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 取景器上的辅助叠加层：三分线、水平仪、直方图。
 * 斑马线和对焦峰值在 GL 着色器里做（需要逐像素），这里只画矢量图形。
 */
class OverlayView(context: Context) : View(context) {

    var gridOn = false
        set(v) { field = v; invalidate() }
    var levelOn = false
        set(v) { field = v; invalidate() }
    var histogramOn = false
        set(v) { field = v; invalidate() }

    /** -45..45 度 */
    @Volatile var rollDeg = 0f

    /** 16 档，0..1 归一化 */
    @Volatile var histogram: FloatArray = FloatArray(16)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC00E676.toInt()
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val histBg = Paint().apply { color = 0x66000000 }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // ---- 三分线 ----
        if (gridOn) {
            for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, line)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, line)
            }
        }

        // ---- 水平仪：跟着手机横滚转的一条线 + 中心刻度 ----
        if (levelOn) {
            val cx = w / 2f
            val cy = h / 2f
            val rad = Math.toRadians(rollDeg.toDouble())
            val len = w * 0.26f
            val dx = (cos(rad) * len).toFloat()
            val dy = (sin(rad) * len).toFloat()
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, levelPaint)
            // 中心小刻度
            val tick = dp(10f)
            canvas.drawLine(cx - tick, cy, cx + tick, cy, tickPaint)
            canvas.drawLine(cx, cy - tick, cx, cy + tick, tickPaint)
            // 接近水平时给个实心提示
            if (abs(rollDeg) < 1.2f) {
                val flat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF00E676.toInt()
                    strokeWidth = dp(3f)
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, flat)
            }
        }

        // ---- 直方图：左上角，允许压住画面 ----
        if (histogramOn) {
            val bw = dp(150f)
            val bh = dp(60f)
            val left = dp(14f)
            val top = dp(48f)
            canvas.drawRect(left, top, left + bw, top + bh, histBg)
            val bars = histogram
            if (bars.isNotEmpty()) {
                val step = bw / bars.size
                for (i in bars.indices) {
                    val v = bars[i].coerceIn(0f, 1f)
                    val barH = v * (bh - dp(4f))
                    canvas.drawRect(
                        left + i * step + dp(0.5f),
                        top + bh - barH,
                        left + (i + 1) * step - dp(0.5f),
                        top + bh,
                        histPaint
                    )
                }
            }
            canvas.drawRect(left, top, left + bw, top + bh, line)
        }
    }
}
