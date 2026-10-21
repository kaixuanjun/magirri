package com.magirri.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 首页：说清楚这是什么、怎么用，再来一个入口按钮。 */
class HomePage(ctx: Context, p: Palette, private val onStart: () -> Unit) : Page(ctx, p) {

    init {
        // ---- 主视觉 ----
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(p.accent, p.accent2)
            ).apply { cornerRadius = dp(ctx, 24f).toFloat() }
            setPadding(dp(ctx, 22f), dp(ctx, 26f), dp(ctx, 22f), dp(ctx, 26f))
            elevation = dp(ctx, 6f).toFloat()
            clipToOutline = true
        }
        hero.addView(
            Ui.text(ctx, "MAGIRRI VEX", 32f, Color.WHITE, true).apply {
                letterSpacing = 0.06f
            }
        )
        hero.addView(
            Ui.text(
                ctx,
                "把手机拍的 Log 视频，一键变成电影感影调",
                14f, 0xF0FFFFFF.toInt()
            ).apply { setPadding(0, dp(ctx, 8f), 0, 0) }
        )
        hero.addView(
            Ui.text(
                ctx,
                "还原 + 影片影调，全部烘进 LUT · 全程本机",
                12f, 0xCCFFFFFF.toInt()
            ).apply { setPadding(0, dp(ctx, 14f), 0, 0) }
        )
        add(hero)

        // ---- 怎么用 ----
        val steps = Ui.card(ctx, p)
        steps.addView(Ui.headline(ctx, p, "怎么用"))
        steps.addView(
            Ui.caption(ctx, p, "三步，一次只处理一个视频").apply {
                setPadding(0, dp(ctx, 4f), 0, dp(ctx, 14f))
            }
        )
        steps.addView(step(ctx, "1", "选一段 Log 视频", "手机上用原生相机录的 Magic-Log 片段"))
        steps.addView(step(ctx, "2", "挑一个色彩", "标准 / Classic 两种影调，已调好"))
        steps.addView(step(ctx, "3", "导出", "成片默认存到 相册/Magirri"))
        add(steps, 16f)

        // ---- 拍 Log 的小贴士 ----
        val tips = Ui.card(ctx, p)
        tips.addView(Ui.headline(ctx, p, "拍 Log 的小贴士"))
        for ((i, t) in listOf(
            "相机 →「专业」→ 打开 LOG（部分机型在相机设置里）",
            "打开后取景框会明显变灰，这就是对的；没变灰说明 LOG 没开",
            "尽量向右曝光、但别让高光过曝（直方图贴右不满）",
            "拍摄时看到灰片是正常的，导出后才有颜色",
        ).withIndex()) {
            tips.addView(
                Ui.body(ctx, p, "· $t").apply {
                    setPadding(0, if (i == 0) dp(ctx, 10f) else dp(ctx, 8f), 0, 0)
                }
            )
        }
        add(tips, 16f)

        // ---- 入口 ----
        val cta = Ui.primary(ctx, p, "开始调色")
        cta.setOnClickListener { onStart() }
        add(cta, 22f)

        add(
            Ui.caption(ctx, p, "不上传任何画面；LUT 已随 App 离线打包").apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 14f), 0, 0)
            }
        )
    }

    private fun step(ctx: Context, n: String, title: String, desc: String): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(ctx, 9f), 0, 0)
        }
        val badge = TextView(ctx).apply {
            text = n
            textSize = 13f
            setTextColor(p.accent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Ui.round(p.accentSoft, 99f)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f)))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), 0, 0, 0)
        }
        col.addView(Ui.text(ctx, title, 14f, p.text, true))
        col.addView(Ui.text(ctx, desc, 12.5f, p.sub).apply { setPadding(0, dp(ctx, 3f), 0, 0) })
        row.addView(
            col,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }
}