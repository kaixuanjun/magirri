package com.magirri.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 一套设计变量：浅色/深色两套，其余控件全部从这里取色。
 *
 * 风格对齐荣耀 MagicOS：大留白、纯白卡片、克制的蓝色主色、胶囊按钮。
 */
class Palette(
    val night: Boolean,
    val bg: Int,
    val card: Int,
    val text: Int,
    val sub: Int,
    val line: Int,
    val accent: Int,
    val accent2: Int,
    val accentSoft: Int,
    val onAccent: Int,
    val glassFill: Int,
    val glassEdge: Int,
)

fun dp(ctx: Context, v: Float): Int =
    (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

object Ui {

    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Palette(
                night = true,
                bg = 0xFF0E1013.toInt(),
                card = 0xFF1A1D22.toInt(),
                text = 0xFFFFFFFF.toInt(),
                sub = 0xFF98A0AA.toInt(),
                line = 0xFF262A31.toInt(),
                accent = 0xFF5A92D8.toInt(),
                accent2 = 0xFF8FB8E8.toInt(),
                accentSoft = 0xFF17283D.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                glassFill = 0xC2181B20.toInt(),
                glassEdge = 0x33FFFFFF,
            )
        } else {
            Palette(
                night = false,
                bg = 0xFFF4F5F7.toInt(),
                card = 0xFFFFFFFF.toInt(),
                text = 0xFF111318.toInt(),
                sub = 0xFF8A8F99.toInt(),
                line = 0xFFE9EDF3.toInt(),
                // 主题色：深海军蓝
                accent = 0xFF0F3A72.toInt(),
                accent2 = 0xFF2C6BA8.toInt(),
                accentSoft = 0xFFE7EEF7.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                glassFill = 0xC2FFFFFF.toInt(),
                glassEdge = 0x66FFFFFF,
            )
        }
    }

    // ---------------------------------------------------------------- 形状

    /**
     * 圆角形状。
     *
     * 注意：``radiusPx`` 是**像素**。之前这里叫 radiusDp 却直接当 px 用，
     * 导致卡片的圆角只有 20px（≈6.7dp），和首页蓝色卡的 24dp 对不上。
     */
    fun round(fill: Int, radiusPx: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx
            if (strokeColor != null) {
                // 描边是 1dp 级别的细线，不能拿圆角半径当线宽
                setStroke(2, strokeColor)
            }
        }

    private fun roundedDp(ctx: Context, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    // ---------------------------------------------------------------- 文本

    fun text(
        ctx: Context,
        s: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
        setLineSpacing(dp(ctx, 3f).toFloat(), 1f)
    }

    fun title(ctx: Context, p: Palette, s: CharSequence): TextView =
        text(ctx, s, 28f, p.text, true)

    fun headline(ctx: Context, p: Palette, s: CharSequence): TextView =
        text(ctx, s, 17f, p.text, true)

    fun body(ctx: Context, p: Palette, s: CharSequence): TextView =
        text(ctx, s, 14f, p.sub)

    fun caption(ctx: Context, p: Palette, s: CharSequence): TextView =
        text(ctx, s, 12f, p.sub)

    /** 卡片里的小标题（淡蓝底胶囊） */
    fun badge(ctx: Context, p: Palette, s: CharSequence): TextView =
        text(ctx, s, 11f, p.accent, true).apply {
            background = round(p.accentSoft, 99f)
            setPadding(dp(ctx, 9f), dp(ctx, 4f), dp(ctx, 9f), dp(ctx, 4f))
        }

    // ---------------------------------------------------------------- 容器

    /** 卡片圆角与首页蓝色主卡一致（24dp） */
    fun card(ctx: Context, p: Palette): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(p.card, dp(ctx, 24f).toFloat())
        setPadding(dp(ctx, 17f), dp(ctx, 16f), dp(ctx, 17f), dp(ctx, 16f))
        elevation = dp(ctx, 2f).toFloat()
        clipToOutline = true
    }

    fun gap(ctx: Context, h: Float): View = View(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, h))
    }

    fun marginTop(v: View, ctx: Context, top: Float): View {
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(ctx, top)
        return v
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // ---------------------------------------------------------------- 按钮

    /** 主按钮：主色渐变胶囊 + 水波纹 */
    fun primary(ctx: Context, p: Palette, label: CharSequence): TextView {
        val tv = text(ctx, label, 16f, p.onAccent, true)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(ctx, 16f), 0, dp(ctx, 16f))
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.accent, p.accent2)
        ).apply { cornerRadius = dp(ctx, 17f).toFloat() }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(ctx, 17f).toFloat()
        }
        tv.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, mask)
        tv.isClickable = true
        tv.isFocusable = true
        return tv
    }

    /** 次级按钮：描边胶囊 */
    fun ghost(ctx: Context, p: Palette, label: CharSequence): TextView {
        val tv = text(ctx, label, 14f, p.text, true)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(ctx, 18f), dp(ctx, 11f), dp(ctx, 18f), dp(ctx, 11f))
        val fill = round(p.card, 99f, p.line)
        val mask = roundedDp(ctx, 99f)
        tv.background = RippleDrawable(ColorStateList.valueOf(0x22000000), fill, mask)
        tv.isClickable = true
        return tv
    }

    /** 纯文字按钮 */
    fun link(ctx: Context, p: Palette, label: CharSequence): TextView =
        text(ctx, label, 13f, p.accent, true).apply {
            setPadding(dp(ctx, 2f), dp(ctx, 6f), dp(ctx, 2f), dp(ctx, 6f))
            isClickable = true
        }
}

/**
 * 分段选择器：一排胶囊，选中项填主色。
 *
 * 用它替代 RadioGroup / Spinner，视觉上更接近 MagicOS 的设置项。
 */
class Segment(
    private val ctx: Context,
    private val p: Palette,
    private val options: List<String>,
    selected: Int,
    private val onChange: (Int) -> Unit,
) : LinearLayout(ctx) {

    private var sel = selected

    init {
        orientation = HORIZONTAL
        background = Ui.round(if (p.night) 0xFF232830.toInt() else 0xFFEDF1F7.toInt(), 99f)
        setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
        render()
    }

    fun setSelected(index: Int) {
        if (index == sel) return
        sel = index
        render()
    }

    fun selIndex(): Int = sel

    private fun render() {
        removeAllViews()
        options.forEachIndexed { i, label ->
            val on = i == sel
            val tv = Ui.text(ctx, label, 13f, if (on) p.onAccent else p.sub, on)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(ctx, 9f), 0, dp(ctx, 9f))
            if (on) tv.background = Ui.round(p.accent, 99f)
            tv.isClickable = true
            tv.setOnClickListener { if (i != sel) { sel = i; render(); onChange(i) } }
            addView(tv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}

/** 页面基类：整屏滚动 + 底部给悬浮底栏留位置（内容会从底栏下方滚过，玻璃才有东西可糊）。 */
abstract class Page(ctx: Context, val p: Palette) : FrameLayout(ctx) {

    protected val scroller = ScrollView(ctx)
    protected val content = LinearLayout(ctx)

    /** 滚动时通知外层重算毛玻璃背景 */
    var onScrollChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(p.bg)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 20f), dp(ctx, 150f))
        scroller.isFillViewport = true
        scroller.isVerticalScrollBarEnabled = false
        scroller.clipToPadding = false
        scroller.addView(
            content,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> onScrollChanged?.invoke() }
        addView(scroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    protected fun add(v: View, topMargin: Float = 0f): View {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(context, topMargin)
        content.addView(v, lp)
        return v
    }
}
