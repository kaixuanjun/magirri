package com.magirri.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** 工作页：选视频 → 选色彩 → 导出。 */
class WorkPage(ctx: Context, p: Palette, private val cb: Callbacks) : Page(ctx, p) {

    interface Callbacks {
        fun onImportLatest()
        fun onPickVideo()
        fun onExport()
        fun onSaveAs()
        fun onCancel()
        fun onOpen()
        fun onShare()
    }

    private lateinit var sourceName: TextView
    private lateinit var sourceMeta: TextView
    private lateinit var sourceColor: TextView
    private lateinit var importBtn: TextView

    private lateinit var logHint: TextView
    private lateinit var lookSegment: Segment
    private lateinit var versionHint: TextView
    private lateinit var exportBtn: TextView
    private lateinit var saveAsLink: TextView
    private lateinit var cancelLink: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var openBtn: TextView
    private lateinit var shareBtn: TextView

    init {
        add(Ui.title(ctx, p, "调色"))
        add(
            Ui.body(ctx, p, "选一段手机拍的 Log 视频，套上还原 + 影片影调（已烘焙，不用手调）").apply {
                setPadding(0, dp(ctx, 6f), 0, 0)
            }
        )

        // ---- ① 输入 ----
        val input = Ui.card(ctx, p)
        input.addView(label(ctx, "①", "输入视频"))
        sourceName = Ui.text(ctx, "点这里选择 Log 视频", 15f, p.text, true).apply {
            setPadding(0, dp(ctx, 12f), 0, 0)
        }
        sourceMeta = Ui.text(ctx, "支持 mp4 / mov，手机上用原生相机录的 Log 片段", 12f, p.sub).apply {
            setPadding(0, dp(ctx, 5f), 0, 0)
        }
        sourceColor = Ui.text(ctx, "", 12f, p.sub).apply {
            setPadding(0, dp(ctx, 5f), 0, 0)
            visibility = View.GONE
        }
        input.addView(sourceName)
        input.addView(sourceMeta)
        input.addView(sourceColor)

        logHint = Ui.text(ctx, "", 12f, p.sub).apply {
            setPadding(0, dp(ctx, 6f), 0, 0)
            visibility = View.GONE
        }
        input.addView(logHint)

        // 录完不用翻相册，直接抓最新一条
        importBtn = Ui.text(ctx, "导入刚录的视频", 13f, p.accent, true).apply {
            setPadding(0, dp(ctx, 14f), 0, 0)
            setOnClickListener { cb.onImportLatest() }
        }
        input.addView(importBtn)
        input.setOnClickListener { cb.onPickVideo() }
        input.isClickable = true
        add(input, 18f)

        // ---- ② 色彩 ----
        val look = Ui.card(ctx, p)
        look.addView(label(ctx, "②", "色彩风格"))
        lookSegment = Segment(ctx, p, listOf("标准", "Classic"), 0) { refresh() }
        look.addView(
            lookSegment,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f) }
        )
        versionHint = Ui.text(ctx, "", 12f, p.sub).apply {
            setPadding(0, dp(ctx, 10f), 0, 0)
        }
        look.addView(versionHint)
        add(look, 14f)

        // ---- ③ 导出 ----
        val out = Ui.card(ctx, p)
        out.addView(label(ctx, "③", "导出"))
        exportBtn = Ui.primary(ctx, p, "开始导出")
        exportBtn.setOnClickListener { cb.onExport() }
        out.addView(
            exportBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 14f) }
        )
        out.addView(
            Ui.caption(ctx, p, "默认保存到 ${OutputStore.PRETTY_PATH}").apply {
                setPadding(0, dp(ctx, 10f), 0, 0)
            }
        )
        saveAsLink = Ui.link(ctx, p, "换个位置保存…").apply {
            setOnClickListener { cb.onSaveAs() }
        }
        out.addView(saveAsLink)
        cancelLink = Ui.link(ctx, p, "取消这次导出").apply {
            setOnClickListener { cb.onCancel() }
        }
        out.addView(cancelLink)

        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.line)
            visibility = View.GONE
        }
        out.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6f)
            ).apply { topMargin = dp(ctx, 14f) }
        )
        statusText = Ui.caption(ctx, p, "").apply { setPadding(0, dp(ctx, 10f), 0, 0) }
        out.addView(statusText)

        actionsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 12f), 0, 0)
        }
        openBtn = Ui.ghost(ctx, p, "打开").apply { setOnClickListener { cb.onOpen() } }
        shareBtn = Ui.ghost(ctx, p, "分享").apply { setOnClickListener { cb.onShare() } }
        actionsRow.addView(
            openBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8f) }
        )
        actionsRow.addView(
            shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8f) }
        )
        out.addView(actionsRow)
        add(out, 14f)

        resetForIdle()
    }

    private fun label(ctx: Context, index: String, title: String): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.text(ctx, index, 12f, p.accent, true).apply {
                background = Ui.round(p.accentSoft, 99f)
                setPadding(dp(ctx, 8f), dp(ctx, 3f), dp(ctx, 8f), dp(ctx, 3f))
            }
        )
        row.addView(
            Ui.text(ctx, title, 16f, p.text, true).apply {
                setPadding(dp(ctx, 10f), 0, 0, 0)
            }
        )
        return row
    }

    // ------------------------------------------------------------ 对外状态

    fun selectedLook(): String = if (lookSegment.selIndex() == 0) "standard" else "classic"

    fun setLogVersion(v: Int) {
        versionHint.text = "还原 LUT：${if (v == 2) "荣耀 v2" else "荣耀 v1"}（在设置里改）"
    }

    fun setSource(name: String?, meta: String?) {
        if (name == null) {
            sourceName.text = "点这里选择 Log 视频"
            sourceName.setTextColor(p.text)
            sourceMeta.text = "支持 mp4 / mov，手机上用原生相机录的 Log 片段"
        } else {
            sourceName.text = name
            sourceName.setTextColor(p.accent)
            sourceMeta.text = meta ?: ""
        }
        resetForIdle()
    }

    /**
     * 显示源素材的色彩信息。
     *
     * HDR/HLG 素材是"导出发灰"的头号原因：HLG 的信号按 SDR 解释会整体压平。
     * 这里显式告诉用户「已转 SDR」还是「本来就是 SDR」。
     */
    fun setSourceColor(transfer: Int, standard: Int, hlgConverted: Boolean) {
        val tx = when (transfer) {
            7 -> "HLG（HDR）"
            6 -> "PQ（HDR）"
            3 -> "SDR"
            else -> ""
        }
        val st = when (standard) {
            6 -> "BT.2020"
            1 -> "BT.709"
            2, 4 -> "BT.601"
            else -> ""
        }
        val detail = listOf(tx, st).filter { it.isNotEmpty() }.joinToString(" · ")
        val text = when {
            detail.isEmpty() -> ""
            hlgConverted -> "源：$detail → 已转 SDR 再套 LUT"
            transfer == 6 -> "源：$detail —— PQ 暂未做色调映射，导出可能偏暗"
            else -> "源：$detail"
        }
        sourceColor.text = text
        sourceColor.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        sourceColor.setTextColor(if (transfer == 7 || transfer == 6) 0xFFE08A00.toInt() else p.sub)
    }

    /**
     * 显示"这段是不是 Log"的判断结果。
     *
     * 普通视频（没开 Log）套 Log 还原 LUT 会很难看，所以宁可在这里拦住。
     */
    fun setLogVerdict(r: LogDetector.Result?) {
        if (r == null) {
            logHint.visibility = View.GONE
            return
        }
        logHint.visibility = View.VISIBLE
        when (r.verdict) {
            LogDetector.Verdict.LOG -> {
                logHint.text = "✓ 看起来是 Magic-Log 灰片，可以调"
                logHint.setTextColor(0xFF1E9E5A.toInt())
            }
            LogDetector.Verdict.NORMAL -> {
                logHint.text = "⚠ 这更像普通视频（没开 LOG）。相机 → 更多 → 电影模式，打开 LOG 再录一段"
                logHint.setTextColor(0xFFD9730D.toInt())
            }
            LogDetector.Verdict.UNKNOWN -> {
                logHint.text = "拿不准是不是 Log；如果导出画面很怪，去相机里确认 LOG 已开"
                logHint.setTextColor(p.sub)
            }
        }
    }
    fun setBusy(busy: Boolean) {
        exportBtn.isEnabled = !busy
        exportBtn.alpha = if (busy) 0.5f else 1f
        saveAsLink.visibility = if (busy) View.GONE else View.VISIBLE
        cancelLink.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            actionsRow.visibility = View.GONE
            progress.visibility = View.VISIBLE
            progress.progress = 0
        }
    }

    fun setProgress(fraction: Float, note: String) {
        progress.visibility = View.VISIBLE
        progress.progress = (fraction * 1000).toInt()
        statusText.text = note
        statusText.setTextColor(p.sub)
    }

    fun setDone(text: String) {
        progress.visibility = View.GONE
        statusText.text = text
        statusText.setTextColor(p.accent)
        actionsRow.visibility = View.VISIBLE
    }

    fun setError(text: String) {
        progress.visibility = View.GONE
        statusText.text = text
        statusText.setTextColor(0xFFE5484D.toInt())
        actionsRow.visibility = View.GONE
    }

    fun resetForIdle() {
        progress.visibility = View.GONE
        statusText.text = ""
        actionsRow.visibility = View.GONE
        cancelLink.visibility = View.GONE
        saveAsLink.visibility = View.VISIBLE
        exportBtn.isEnabled = true
        exportBtn.alpha = 1f
    }

    private fun refresh() {
        // 换风格时只更新提示，不需要重绘整页
    }
}