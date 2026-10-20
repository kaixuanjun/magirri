package com.magirri.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Magirri 外壳：三页（首页 / 调色 / 设置）+ 悬浮液态玻璃底栏。
 *
 * 玻璃由 AndroidLiquidGlassView 负责（见 GlassBar），这里只管页面切换、
 * 选片、后台转码与结果展示。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var pages: FrameLayout
    private lateinit var bar: GlassBar
    private lateinit var home: HomePage
    private lateinit var work: WorkPage
    private lateinit var settings: SettingsPage
    private lateinit var pageViews: List<View>

    private var current = 0
    private var inputUri: Uri? = null
    private var outputUri: Uri? = null
    private var working = false
    private var debugMode = 0
    private var forceBuffer = false
    private var rotMode = 2
    private var pendingExportAfterPermission = false
    private var pendingImport = false
    @Volatile private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        p = Ui.palette(this)
        buildUi()
        showPage(0, animate = false)
        // 意图放最后：无界面/外部打开会自己切到工作页，别被首页覆盖
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ 搭界面

    private fun buildUi() {
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility =
                if (p.night) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(p.bg)

        pages = FrameLayout(this)
        home = HomePage(this, p) { showPage(1) }
        work = WorkPage(this, p, object : WorkPage.Callbacks {
            override fun onImportLatest() = importLatestVideo()
            override fun onPickVideo() = pickVideo()
            override fun onExport() = exportToAlbum()
            override fun onSaveAs() = askSaveAs()
            override fun onCancel() {
                cancelRequested = true
                work.setProgress(0f, "正在取消…")
            }

            override fun onOpen() = openResult()
            override fun onShare() = shareResult()
        })
        settings = SettingsPage(this, p, prefs) { applyPrefs() }

        pages.addView(home, match())
        pages.addView(work, match())
        pages.addView(settings, match())
        pageViews = listOf(home, work, settings)
        pageViews.forEach { it.visibility = View.GONE }

        bar = GlassBar(this, p)
        bar.onSelect = { showPage(it) }
        bar.glass = glassEnabled()
        bar.selected = 0
        // 玻璃要透视的容器就是页面容器
        bar.bindBackdrop(pages)
        work.setLogVersion(prefs.logVersion)

        root.addView(pages, match())
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM }
        )
        setContentView(root)
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    // ------------------------------------------------------------------ 页面

    private fun showPage(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, 2)
        if (target == current && pageViews[target].visibility == View.VISIBLE) return
        val outgoing = pageViews[current]
        val incoming = pageViews[target]
        current = target
        bar.selected = target
        if (target == 2) settings.syncFromPrefs()
        if (target == 1) work.setLogVersion(prefs.logVersion)

        // 先取消上一轮残留的淡入淡出，否则 alpha 会被旧动画改成 0（整页空白）
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.visibility = View.VISIBLE
        if (animate) {
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(190).start()
            outgoing.animate().alpha(0f).setDuration(140).withEndAction {
                if (outgoing !== incoming) outgoing.visibility = View.GONE
            }.start()
        } else {
            incoming.alpha = 1f
            pageViews.forEach { if (it !== incoming) { it.visibility = View.GONE; it.alpha = 1f } }
        }
    }

    // ------------------------------------------------------------------ 底栏外观

    private fun glassEnabled(): Boolean {
        return when (prefs.glassMode) {
            2 -> false
            1 -> true
            else -> {
                val am = getSystemService(ActivityManager::class.java)
                am?.isLowRamDevice != true
            }
        }
    }

    private fun applyPrefs() {
        bar.glass = glassEnabled()
        work.setLogVersion(prefs.logVersion)
    }

    // ------------------------------------------------------------------ 意图

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { adoptInput(it) }
            ACTION_PROCESS -> {
                val v = intent.getStringExtra("video") ?: return
                debugMode = intent.getIntExtra("debug", 0)
                forceBuffer = intent.getIntExtra("buffer", 0) == 1
                rotMode = intent.getIntExtra("rotmode", 2)
                adoptInput(Uri.fromFile(java.io.File(v)))
                // 给了 out 就写那个路径；没给就走默认的 相册/Magirri
                val o = intent.getStringExtra("out")
                startExport(o?.let { Uri.fromFile(java.io.File(it)) }, headless = true)
            }
        }
    }

    // ------------------------------------------------------------------ 选素材

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_PICK)
    }

    /**
     * 拉起系统相机录像。
     *
     * 为什么不自己写相机：Magic-Log 是荣耀相机的私有能力，第三方 App 走 Camera2
     * 只能拿到普通视频，录出来是彩色的，套 Log 还原 LUT 反而更糟。所以用
     * ACTION_VIDEO_CAPTURE 借系统相机——Log 开关在那边，录完把文件带回 App。
     */

    /** 录完直接抓相册里最新一条视频，省得用户翻。 */
    private fun importLatestVideo() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            pendingImport = true
            requestPermissions(arrayOf(perm), REQ_PERM_IMPORT)
            return
        }
        val uri = newestVideo()
        if (uri == null) {
            toast("相册里没找到视频，手动选一段吧")
            pickVideo()
        } else {
            adoptInput(uri)
        }
    }

    private fun newestVideo(): Uri? {
        val cols = arrayOf(MediaStore.Video.Media._ID)
        return try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    private fun adoptInput(uri: Uri) {
        inputUri = uri
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val (tr, st, hlg) = sourceColorInfo(uri)
        work.setSourceColor(tr, st, hlg)
        work.setSource(displayName(uri) ?: "已选择的视频", describe(uri))
        work.setLogVerdict(null)
        Thread {
            val verdict = LogDetector.detect(this, uri)
            runOnUiThread { work.setLogVerdict(verdict) }
        }.start()
        if (current != 1) showPage(1)
    }

    /** 读源素材的 transfer / primaries：决定要不要先转 SDR（HLG 直接套 LUT 会发灰）。 */
    private fun sourceColorInfo(uri: Uri): Triple<Int, Int, Boolean> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(this, uri, emptyMap<String, String>())
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val tr = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                val st = if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                    f.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
                return Triple(tr, st, tr == MediaFormat.COLOR_TRANSFER_HLG)
            }
            Triple(0, 0, false)
        } catch (_: Exception) {
            Triple(0, 0, false)
        } finally {
            try {
                ex.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun describe(uri: Uri): String {
        val sb = StringBuilder()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            sb.append("$w×$h · ${"%.1f".format(dur / 1000.0)}s")
        } catch (_: Exception) {
            return "已选择"
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        val size = fileSize(uri)
        if (size > 0) sb.append(" · ").append(humanSize(size))
        return sb.toString()
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun fileSize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }

    // ------------------------------------------------------------------ 导出

    private fun exportToAlbum() {
        if (inputUri == null) {
            toast("先选一段视频")
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportAfterPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM
            )
            return
        }
        startExport(null, headless = false)
    }

    private fun askSaveAs() {
        val src = inputUri ?: return
        val base = (displayName(src) ?: "output").substringBeforeLast('.')
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, "${base}_Magirri.mp4")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_SAVE)
    }

    private fun startExport(target: Uri?, headless: Boolean) {
        if (working) return
        val src = inputUri ?: return
        working = true
        cancelRequested = false
        work.setBusy(true)
        work.setProgress(0f, "准备中…")

        val base = (displayName(src) ?: "output").substringBeforeLast('.')
        val name = "${base}_Magirri.mp4"
        val asset = "luts/honor_v${prefs.logVersion}_${work.selectedLook()}.cube"

        Thread {
            var error: String? = null
            var stats: VideoTranscoder.Stats? = null
            var out: Uri? = null
            try {
                out = target ?: OutputStore.create(this, name)
                val lut = assets.open(asset).bufferedReader().use { it.readText() }
                    .let { Lut3D.parse(it) }
                runOnUiThread { work.setProgress(0f, "色链就绪（${lut.size}³ LUT），开始转码…") }
                stats = VideoTranscoder(
                    context = this,
                    inputUri = src,
                    outputUri = out,
                    lut = lut,
                    onProgress = { frac, note ->
                        runOnUiThread { work.setProgress(frac, note) }
                    },
                    isCancelled = { cancelRequested },
                    debugMode = debugMode,
                    forceBuffer = forceBuffer,
                    rotMode = rotMode,
                    hdrToSdr = prefs.hdrToSdr,
                ).run()
                OutputStore.commit(this, out)
                outputUri = out
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val finalOut = out
            val st = stats
            val err = error
            runOnUiThread {
                working = false
                work.setBusy(false)
                when {
                    cancelRequested -> {
                        work.resetForIdle()
                        work.setError("已取消，没有生成文件")
                    }
                    err != null -> work.setError("导出失败：$err")
                    st != null -> {
                        work.setDone(
                            "已导出 ${st.width}×${st.height} · ${st.codec} · " +
                                    "${st.frames} 帧\n" +
                                    "保存在 ${OutputStore.describe(this, finalOut ?: src)}"
                        )
                        if (!headless) {
                            toast("已保存到 ${OutputStore.PRETTY_PATH}")
                        }
                    }
                }
            }
        }.start()
    }

    private fun openResult() {
        val uri = outputUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("没有可用的视频播放器")
        }
    }

    private fun shareResult() {
        val uri = outputUri ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "分享成片"))
    }

    // ------------------------------------------------------------------ 回调

    @Deprecated("保持零依赖，用 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK -> if (resultCode == RESULT_OK) adoptInput(uri)
            REQ_SAVE -> if (resultCode == RESULT_OK) startExport(uri, headless = false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM_IMPORT) {
            if (pendingImport) {
                pendingImport = false
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) importLatestVideo()
                else { toast("没有相册权限，改用手动选片"); pickVideo() }
            }
            return
        }
        if (requestCode == REQ_PERM && pendingExportAfterPermission) {
            pendingExportAfterPermission = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startExport(null, headless = false)
            } else {
                work.setError("没有存储权限，改用「换个位置保存…」")
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        cancelRequested = true
        super.onDestroy()
    }

    companion object {

        private const val TAG = "magirri"

        private const val REQ_PICK = 1001
        private const val REQ_PERM_IMPORT = 1005
        private const val REQ_SAVE = 1002
        private const val REQ_PERM = 1003
        const val ACTION_PROCESS = "com.magirri.app.PROCESS"
    }
}