package com.magirri.lume

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 波轮：横向圆柱面朝向用户的那种滚轮（像相机机顶的拨轮、鼠标滚轮）。
 *
 * 看见的是**带纹路的侧面**：纹路横向排布，拨动时上下滚动，上下边缘压暗模拟柱面曲率。
 * 中间一条固定的指示线标出当前值。
 *
 * 为什么不做成圆盘：圆盘正对用户，是旋钮（knob）；波轮是圆柱的侧面，两者操作与视觉都不一样。
 */
class WheelView(context: Context, caption: String) : View(context) {

    /** 0..1 */
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onChange: ((Float) -> Unit)? = null
    var format: (Float) -> String = { "" }

    var caption: String = caption
        set(v) {
            field = v
            invalidate()
        }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xE6FFFFFF.toInt()
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x33FFFFFF
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private var clip = Path()
    private var dragging = false
    private var dragStartY = 0f
    private var dragStartValue = 0f

    /** 拨动多少距离振一次（整个量程分成 24 段） */
    private val hapticStep = 1f / 24f
    private var lastHaptic = 0f

    /** 是否正在拖动（S 档自动 ISO 循环要靠它避让，不然会互相打架） */
    val isDragging: Boolean get() = dragging

    private fun tickHaptic(v: Float) {
        if (abs(v - lastHaptic) < hapticStep) return
        lastHaptic = v
        performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /** 纹路间距与整圈行程：拨满量程相当于滚过 40 条纹 */
    private val ribSpacing = dp(7f)
    private val ribTravel = ribSpacing * 40

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(120f).toInt(), widthMeasureSpec),
            resolveSize(dp(84f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(14f)
        rect.set(0f, 0f, w, h)
        clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clip)

        // 柱面明暗：细长竖条是「大滚轮的一段」，左右两侧压暗、中间亮
        bodyPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0xFF080808.toInt(), 0xFF303030.toInt(), 0xFF3E3E3E.toInt(),
                0xFF303030.toInt(), 0xFF080808.toInt()),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, bodyPaint)
        bodyPaint.shader = null

        // 纹路：横向棱，拨动时上下滚动
        val offset = (value * ribTravel) % ribSpacing
        var y = -ribSpacing + offset
        while (y < h + ribSpacing) {
            // 越靠上下端越淡，暗示轮子滚出视野
            val t = (y / h).coerceIn(0f, 1f)
            val edgeFade = when {
                t < 0.12f -> 0.1f + t * 6f
                t > 0.88f -> 0.1f + (1f - t) * 6f
                else -> 1f
            }
            val alpha = (120 * edgeFade).toInt().coerceIn(8, 140)
            ribPaint.color = (alpha shl 24) or 0xFFFFFF
            canvas.drawLine(dp(4f), y, w - dp(4f), y, ribPaint)
            y += ribSpacing
        }

        // 中间指示线
        canvas.drawLine(dp(6f), h / 2f, w - dp(6f), h / 2f, indexPaint)

        canvas.restore()
        canvas.drawRoundRect(rect, r, r, edgePaint)

        // 数值底衬：纹路别穿过数字
        platePaint.color = 0xB3000000.toInt()
        val pw = dp(58f)
        val ph = dp(20f)
        canvas.drawRoundRect(
            (w - pw) / 2f, h / 2f - ph / 2f + dp(1f),
            (w + pw) / 2f, h / 2f + ph / 2f + dp(1f),
            ph / 2f, ph / 2f, platePaint
        )

        // 数值 + 标题
        textPaint.textSize = dp(15f)
        canvas.drawText(format(value), w / 2f, h / 2f + dp(5f), textPaint)
        capPaint.textSize = dp(9f)
        canvas.drawText(caption, w / 2f, dp(13f), capPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartY = event.y
                dragStartValue = value
                lastHaptic = value
                parent?.requestDisallowInterceptTouchEvent(true)
                performClick()
            }

            MotionEvent.ACTION_MOVE -> if (dragging) {
                // 行程 = 控件高度的 1.6 倍走完量程：原来固定 70dp 太灵敏，轻轻一划就到底
                val travel = height * 1.6f
                val dv = (dragStartY - event.y) / travel
                val v = (dragStartValue + dv).coerceIn(0f, 1f)
                if (abs(v - value) > 0.0005f) {
                    value = v
                    onChange?.invoke(v)
                    tickHaptic(v)
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
