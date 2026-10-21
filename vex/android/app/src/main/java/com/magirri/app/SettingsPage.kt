package com.magirri.app

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 设置页：Log 版本、输出位置、底栏外观、关于。 */
class SettingsPage(
    ctx: Context,
    p: Palette,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) : Page(ctx, p) {

    private lateinit var versionSegment: Segment
    private lateinit var glassSegment: Segment
    private lateinit var versionNote: TextView

    init {
        add(Ui.title(ctx, p, "设置"))
        add(
            Ui.body(ctx, p, "Log 版本会影响整条色彩链，选错不是偏一点而是整体错").apply {
                setPadding(0, dp(ctx, 6f), 0, 0)
            }
        )

        // ---- Log 版本 ----
        val ver = Ui.card(ctx, p)
        ver.addView(Ui.headline(ctx, p, "Log 版本"))
        versionSegment = Segment(
            ctx, p, listOf("荣耀 v1", "荣耀 v2"), prefs.logVersion - 1
        ) { idx ->
            prefs.logVersion = idx + 1
            refreshVersionNote()
            onChanged()
        }
        ver.addView(
            versionSegment,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f) }
        )
        versionNote = Ui.caption(ctx, p, "").apply { setPadding(0, dp(ctx, 12f), 0, 0) }
        ver.addView(versionNote)
        add(ver, 18f)

        // ---- 输出 ----
        val out = Ui.card(ctx, p)
        out.addView(Ui.headline(ctx, p, "导出位置"))
        out.addView(
            Ui.text(ctx, OutputStore.PRETTY_PATH, 14f, p.accent, true).apply {
                setPadding(0, dp(ctx, 10f), 0, 0)
            }
        )
        out.addView(
            Ui.caption(
                ctx, p,
                "成片直接进系统相册，不需要手动选目录。\n导出页也留了「换个位置保存」可以另存。"
            ).apply { setPadding(0, dp(ctx, 6f), 0, 0) }
        )
        add(out, 14f)

        // ---- 界面 ----
        val ui = Ui.card(ctx, p)
        ui.addView(Ui.headline(ctx, p, "底栏外观"))
        glassSegment = Segment(ctx, p, listOf("自动", "液态玻璃", "简洁"), prefs.glassMode) { idx ->
            prefs.glassMode = idx
            onChanged()
        }
        ui.addView(
            glassSegment,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f) }
        )
        ui.addView(
            Ui.caption(
                ctx, p,
                "液态玻璃 = AndroidLiquidGlassView：把底栏背后的画面实时折射（iOS 26 那种）；" +
                        "自动 = 低端机或系统低于 Android 13 时退回简洁样式。"
            ).apply { setPadding(0, dp(ctx, 12f), 0, 0) }
        )
        add(ui, 14f)

        // ---- HDR ----
        val hdr = Ui.card(ctx, p)
        hdr.addView(Ui.headline(ctx, p, "HDR 素材"))
        val hdrSeg = Segment(
            ctx, p, listOf("先转 SDR（推荐）", "直接当 SDR"), if (prefs.hdrToSdr) 0 else 1
        ) { idx ->
            prefs.hdrToSdr = idx == 0
            onChanged()
        }
        hdr.addView(
            hdrSeg,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f) }
        )
        hdr.addView(
            Ui.caption(
                ctx, p,
                "HLG/HDR 素材如果直接套 Log LUT，会整片发灰、对比度起不来。" +
                        "默认先按 BT.2100 逆 OETF 转成 SDR 再套。若你的素材本来就是普通 SDR、只是被打了 HDR 标，可以关掉对比看。"
            ).apply { setPadding(0, dp(ctx, 12f), 0, 0) }
        )
        add(hdr, 14f)

        // ---- 关于 ----
        val about = Ui.card(ctx, p)
        about.addView(Ui.headline(ctx, p, "关于"))
        about.addView(
            Ui.text(ctx, "Magirri 1.0", 14f, p.text, true).apply {
                setPadding(0, dp(ctx, 10f), 0, 0)
            }
        )
        about.addView(
            Ui.caption(
                ctx, p,
                "色彩链：Magic-Log → 还原 LUT → Rec.709 → LogC3(EI800) → LogC3→709 → 成品影调\n" +
                "成品影调是固定的五条常数，烘进 LUT，所以同素材每次导出都一样、不随素材漂。\n" +
                "整条链在桌面端烘成 33³ LUT 打进 App，手机只做一次查表。\n" +
                "影调曲线为自制，仅限本机使用。"
            ).apply { setPadding(0, dp(ctx, 8f), 0, 0) }
        )
        add(about, 14f)

        refreshVersionNote()
    }

    private fun refreshVersionNote() {
        versionNote.text = if (prefs.logVersion == 2) {
            "荣耀 v2：Magic4 Pro+ / Magic5 Pro+ / Magic6–Magic8 / Magic V3 / V5"
        } else {
            "荣耀 v1：Magic3 / Magic V / Magic4 / Magic5 / Magic Vs / Magic V2"
        }
    }

    fun syncFromPrefs() {
        versionSegment.setSelected(prefs.logVersion - 1)
        glassSegment.setSelected(prefs.glassMode)
        refreshVersionNote()
    }
}