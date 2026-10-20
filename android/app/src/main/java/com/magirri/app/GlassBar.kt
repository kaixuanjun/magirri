package com.magirri.app

import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 悬浮胶囊底栏。
 *
 * 玻璃层直接用 [LiquidGlassView]（AndroidLiquidGlassView，MIT）：
 * 它把背后的页面容器录进 RenderNode，再用 AGSL 做边缘折射 + 色散，是真正的液态玻璃。
 *
 * 它只在 API 33+ 生效（需要运行时着色器），33 以下由这里回退成不透明胶囊；
 * 设置里的「简洁」同样走回退分支。
 */
class GlassBar(ctx: Context, private val p: Palette) : FrameLayout(ctx) {

    var labels: List<String> = listOf("首页", "调色", "设置")
        set(v) {
            field = v
            tabs.labels = v
        }

    var onSelect: ((Int) -> Unit)? = null

    /** false = 走不透明回退（设置里选「简洁」，或系统版本不够） */
    var glass: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            rebuild()
        }

    var selected: Int
        get() = tabs.selected
        set(v) {
            tabs.selected = v
        }

    private val panel = FrameLayout(ctx)
    private val tabs = TabsView(ctx, p)
    private var liquid: LiquidGlassView? = null
    private var backdrop: ViewGroup? = null

    init {
        // 胶囊在整条槽里内缩，留出阴影和悬浮感
        addView(
            panel,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(dp(context, 18f), dp(context, 6f), dp(context, 18f), dp(context, 10f))
            }
        )
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        panel.elevation = dp(context, 12f).toFloat()

        tabs.onSelect = { index ->
            selected = index
            onSelect?.invoke(index)
        }
        rebuild()
    }

    /** 绑定「玻璃要透视的容器」（页面容器）。 */
    fun bindBackdrop(view: ViewGroup?) {
        backdrop = view
        rebuild()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定高度，但必须把子视图（胶囊面板）也量到，否则它会是 0 高
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(context, 92f), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 圆角/折射依赖实际高度，布局完再设一次
        applyShape()
    }

    private fun rebuild() {
        panel.removeAllViews()
        liquid = null

        val source = backdrop
        if (glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && source != null) {
            val lg = LiquidGlassView(context)
            // 真液态玻璃：几乎不模糊，靠折射和边缘高光撑起体积感
            lg.setBlurRadius(10f)
            lg.setDispersion(0.55f)
            lg.setTintColorRed(1f)
            lg.setTintColorGreen(1f)
            lg.setTintColorBlue(1f)
            lg.setTintAlpha(if (p.night) 0.20f else 0.10f)
            lg.bind(source)
            lg.addView(
                tabs,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            panel.addView(
                lg,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            panel.background = null
            liquid = lg
        } else {
            // 回退：不透明胶囊 + 一条细边，不做抓帧、不做模糊
            panel.background = Ui.round(
                if (p.night) 0xFF1A1D22.toInt() else 0xFFFFFFFF.toInt(), 99f, p.line
            )
            panel.addView(
                tabs,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        applyShape()
    }

    /** 圆角与折射参数依赖实际高度，尺寸变化后要重设一遍。 */
    private fun applyShape() {
        val lg = liquid ?: return
        val h = panel.height
        if (h <= 0) return
        lg.setCornerRadius(h / 2f)
        lg.setRefractionHeight(dp(context, 18f).toFloat())
        lg.setRefractionOffset(dp(context, 40f).toFloat())
    }
}
