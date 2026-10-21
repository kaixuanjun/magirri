package com.magirri.lume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 拟真拨盘：外圈有滚花刻度和凹槽，中间显示数值。
 *
 * 手感按「上下拖」算，不按角度算——按角度算在手指挡住中心时几乎没法微调。
 * 拖动时数值是连续的，松手后才吸附（由调用方决定吸附精度）。
 */
class DialView(context: Context, label: String) : View(context) {

    /** 0..1 */
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onChange: ((Float) -> Unit)? = null

    /** 中间大字（数值） */
    var format: (Float) -> String = { "" }

    /** 中间小字（标题：变焦 / 快门 / ISO …） */
    var caption: String = label
        set(v) {
            field = v
            invalidate()
        }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFF3C3C3C.toInt()
    }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF101010.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8AFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private var dragging = false
    private var dragStartY = 0f
    private var dragStartValue = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 刻度扫过的角度：270°，从 -135° 到 +135° */
    private val sweep = 270f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(108f).toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - dp(2f)

        // 盘体：径向渐变模拟金属凹陷
        bodyPaint.shader = RadialGradient(
            cx, cy * 0.85f, r,
            intArrayOf(0xFF343434.toInt(), 0xFF1B1B1B.toInt(), 0xFF0E0E0E.toInt()),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, bodyPaint)
        bodyPaint.shader = null
        canvas.drawCircle(cx, cy, r - dp(0.8f), rimPaint)

        // 凹槽
        groovePaint.strokeWidth = dp(8f)
        canvas.drawCircle(cx, cy, r * 0.78f, groovePaint)

        // 刻度
        val ticks = 21
        tickPaint.strokeWidth = dp(1.6f)
        for (i in 0 until ticks) {
            val t = i / (ticks - 1f)
            val angle = -sweep / 2 + t * sweep
            val rad = Math.toRadians(angle.toDouble())
            val major = i % 5 == 0
            val r1 = r * if (major) 0.84f else 0.87f
            val r2 = r * 0.93f
            tickPaint.color = if (major) 0x99FFFFFF.toInt() else 0x4DFFFFFF
            canvas.drawLine(
                cx + (cos(rad) * r1).toFloat(), cy + (sin(rad) * r1).toFloat(),
                cx + (cos(rad) * r2).toFloat(), cy + (sin(rad) * r2).toFloat(),
                tickPaint
            )
        }

        // 指示点：落在凹槽上
        val angle = -sweep / 2 + value * sweep
        val rad = Math.toRadians(angle.toDouble())
        val px = cx + (cos(rad) * r * 0.78f).toFloat()
        val py = cy + (sin(rad) * r * 0.78f).toFloat()
        canvas.drawCircle(px, py, dp(6f), pointerPaint)

        // 中间文字
        textPaint.textSize = dp(15f)
        canvas.drawText(format(value), cx, cy + dp(6f), textPaint)
        capPaint.textSize = dp(9f)
        canvas.drawText(caption, cx, cy - dp(12f), capPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartY = event.y
                dragStartValue = value
                parent?.requestDisallowInterceptTouchEvent(true)
                performClick()
            }

            MotionEvent.ACTION_MOVE -> if (dragging) {
                // 竖直行程约等于控件高度的 1.2 倍 = 走完整个量程
                val travel = height * 1.2f
                val dv = (dragStartY - event.y) / travel
                val v = (dragStartValue + dv).coerceIn(0f, 1f)
                if (abs(v - value) > 0.0005f) {
                    value = v
                    onChange?.invoke(v)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
