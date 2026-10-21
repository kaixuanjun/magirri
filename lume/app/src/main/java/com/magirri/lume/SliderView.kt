package com.magirri.lume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 竖向滑条：变焦 / 曝光用。
 *
 * 触摸按「整条轨道 + 上下各留一点」算，拖出边界也继续跟随，手感更接近相机上的实体环。
 */
class SliderView(context: Context, private val label: String) : View(context) {

    /** 0..1，底部为 0 */
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onChange: ((Float) -> Unit)? = null

    /** 把 0..1 变成显示文字 */
    var format: (Float) -> String = { "" }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val track = RectF()
    private var dragging = false

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(46f).toInt(), widthMeasureSpec),
            resolveSize(dp(150f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackW = dp(12f)
        val top = dp(18f)
        val bottom = h - dp(18f)
        val cx = w / 2f

        track.set(cx - trackW / 2, top, cx + trackW / 2, bottom)
        canvas.drawRoundRect(track, trackW / 2, trackW / 2, trackPaint)

        val knobY = bottom - (bottom - top) * value
        fillPaint.color = 0xE6FFFFFF.toInt()
        canvas.drawRoundRect(
            RectF(track.left, knobY, track.right, bottom),
            trackW / 2, trackW / 2, fillPaint
        )
        canvas.drawCircle(cx, knobY, dp(11f), knobPaint)

        // 数值显示在滑条上方
        canvas.drawText(format(value), cx, top - dp(6f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.y)
            }

            MotionEvent.ACTION_MOVE -> if (dragging) update(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(y: Float) {
        val top = dp(18f)
        val bottom = height - dp(18f)
        val span = bottom - top
        if (span <= 0) return
        val v = ((bottom - y) / span).coerceIn(0f, 1f)
        if (abs(v - value) < 0.001f) return
        value = v
        onChange?.invoke(v)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    fun label(): String = label
}
