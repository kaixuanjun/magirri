package com.magirri.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 底栏的"内容层"：选中水珠 + 三个图标 + 文字。
 *
 * 背景（玻璃）不在这里画——那层交给 AndroidLiquidGlassView，
 * 这里保持完全透明，只负责内容与点击。
 */
class TabsView(ctx: Context, private val p: Palette) : View(ctx) {

    var labels: List<String> = listOf("首页", "调色", "设置")
    var onSelect: ((Int) -> Unit)? = null

    var selected: Int = 0
        set(v) {
            val old = field
            field = v
            if (old != v) animatePill(old, v)
            invalidate()
        }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(context, 11f).toFloat()
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
    }

    private val pill = RectF()
    private val iconPaths = Array(3) { Path() }
    private val knobPaths = Array(3) { Path() }

    private var pillCenterX = 0f
    private var pillWidth = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pillWidth = w / 3f - dp(context, 12f)
        pillCenterX = tabCenter(selected)
        animator?.cancel()
    }

    private fun tabCenter(index: Int): Float = width * (index + 0.5f) / 3f

    private fun animatePill(from: Int, to: Int) {
        animator?.cancel()
        if (width <= 0) {
            pillCenterX = 0f
            return
        }
        animator = ValueAnimator.ofFloat(tabCenter(from), tabCenter(to)).apply {
            duration = 260
            addUpdateListener {
                pillCenterX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return

        // 选中水珠
        pill.set(
            pillCenterX - pillWidth / 2f,
            dp(context, 5f).toFloat(),
            pillCenterX + pillWidth / 2f,
            h - dp(context, 5f)
        )
        pillPaint.color = if (p.night) 0x33FFFFFF else 0x240F3A72
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, pillPaint)

        // 图标 + 文字
        val cy = h * 0.42f
        val labelY = h * 0.80f
        for (i in 0 until 3) {
            val cx = tabCenter(i)
            val on = i == selected
            val tint = if (on) p.accent else p.sub
            iconPaint.color = tint
            iconPaint.strokeWidth = dp(context, if (on) 2.1f else 1.8f).toFloat()
            buildIcon(i, cx, cy, dp(context, if (on) 9f else 8.5f).toFloat())
            canvas.drawPath(iconPaths[i], iconPaint)
            knobPaint.color = tint
            canvas.drawPath(knobPaths[i], knobPaint)
            labelPaint.color = tint
            canvas.drawText(labels.getOrElse(i) { "" }, cx, labelY, labelPaint)
        }
    }

    private fun buildIcon(index: Int, cx: Float, cy: Float, r: Float) {
        val path = iconPaths[index]
        path.reset()
        knobPaths[index].reset()
        when (index) {
            0 -> {
                path.moveTo(cx - r, cy - r * 0.15f)
                path.lineTo(cx, cy - r)
                path.lineTo(cx + r, cy - r * 0.15f)
                path.moveTo(cx - r * 0.66f, cy - r * 0.3f)
                path.lineTo(cx - r * 0.66f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.66f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.66f, cy - r * 0.3f)
            }
            1 -> {
                val y1 = cy - r * 0.5f
                val y2 = cy + r * 0.5f
                path.moveTo(cx - r, y1)
                path.lineTo(cx + r, y1)
                path.moveTo(cx - r, y2)
                path.lineTo(cx + r, y2)
                knobPaths[index].addCircle(cx - r * 0.34f, y1, r * 0.3f, Path.Direction.CW)
                knobPaths[index].addCircle(cx + r * 0.38f, y2, r * 0.3f, Path.Direction.CW)
            }
            else -> {
                path.addCircle(cx, cy, r * 0.62f, Path.Direction.CW)
                for (k in 0 until 8) {
                    val a = Math.PI * 2 * k / 8 + Math.PI / 8
                    val r1 = r * 0.72
                    val r2 = r * 0.97
                    path.moveTo(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat())
                    path.lineTo(cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat())
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val idx = (event.x / (width / 3f)).toInt().coerceIn(0, 2)
                if (idx != selected) onSelect?.invoke(idx)
            }
        }
        return true
    }
}
