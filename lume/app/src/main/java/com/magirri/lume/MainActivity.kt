package com.magirri.lume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * MAGIRRI Lume：独立相机，直出 ARRI 色彩。
 *
 * 预览走 GL 实时查表；拍照走 YUV → CPU 查同一张 LUT → JPEG。
 * LUT 是 Magri 那条链把「荣耀还原」换成恒等后烘出来的，所以色彩响应和 Magri 一致。
 */
class MainActivity : Activity() {

    private lateinit var preview: GlPreview
    private lateinit var camera: CameraEngine
    private lateinit var lut: Lut3D
    private lateinit var shutter: View
    private lateinit var toggle: TextView
    private lateinit var status: TextView
    private lateinit var perf: TextView
    private lateinit var recordBtn: View
    private lateinit var zoomDial: WheelView
    private lateinit var evDial: WheelView
    private lateinit var modeBtn: TextView

    private lateinit var meterBtn: TextView

    private lateinit var ratioBtn: TextView
    private var splashLogo: ImageView? = null
    private lateinit var focusBtn: TextView
    private lateinit var mfBtn: TextView
    private lateinit var focusWheel: WheelView
    private var focusIndex = 1          // 默认 AF-C
    private var focusDiopters = 0f
    private lateinit var settingsBtn: TextView
    private lateinit var settingsPanel: View
    private lateinit var ratioValue: TextView
    private lateinit var resValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var focusRing: View
    private var resIndex = 0            // 0=4K 1=1080P 2=720P
    private var fpsIndex = 1            // 0=24 1=30 2=60
    private lateinit var clockText: TextView
    private lateinit var batteryText: TextView

    /** 当前画幅索引：0=3:2, 1=16:9, 2=2.35:1 */

    private var ratioIndex = 0

    /** 0 点测 / 1 中央重点 / 2 多区 */
    private var meterMode = 1

    /** 当前变焦倍率（GL 数字变焦，相机始终全画幅） */
    private var zoomLevel = 1f

    /** S 档上一次的 EV，用来算增量补偿 */
    private var lastEv = 0f

    private lateinit var zoomBtn: TextView

    private lateinit var evBtn: TextView

    private lateinit var shutterBtn: TextView

    private lateinit var isoBtn: TextView



    private lateinit var wheels: List<WheelView>

    private lateinit var wheelStrip: LinearLayout

    private var openWheelKind = -1

    private lateinit var shutterWheel: WheelView

    private lateinit var isoWheel: WheelView

    private lateinit var smallWheelRow: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var busy = false
    private var sensorOrientation = 90
    /** 设备握持方向（加速度计），0/90/180/270 */
    private var deviceDeg = 0
    private lateinit var orientation: OrientationTracker
    private lateinit var recorder: VideoRecorder
    private var recording = false
    private var recordStart = 0L
    private var previewSize: android.util.Size? = null

    /** 4K 录制流尺寸；null 表示这台机器没有独立录制流 */

    private var recordSize: android.util.Size? = null
    private var strength = 1f

    /** 录制时长走秒表 */
    /** 每秒刷一次性能读数，卡顿时看它就够了 */
    private val perfTicker = object : Runnable {
        override fun run() {
            val (fps, drawMs, encMs) = preview.takeStats()
            perf.text = if (recording) {
                "FPS %d · GL %.1fms · ENC %.1fms".format(fps, drawMs, encMs)
            } else {
                "FPS %d · GL %.1fms".format(fps, drawMs)
            }
            ui.postDelayed(this, 1000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (android.os.SystemClock.elapsedRealtime() - recordStart) / 1000
            status.text = "● 录制中 %02d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 隐藏系统通知栏（用自绘的那一条替代）。
        // 注意：不能在 setContentView 之前碰 window.insetsController —— 那时 decorView 还是 null，会 NPE。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        lut = Lut3D.parse(assets.open("luts/arri_cam_standard.cube").bufferedReader().use { it.readText() })
        camera = CameraEngine(this)
        recorder = VideoRecorder(this)
        // 先按系统显示方向初始化，传感器第一个事件到来后再校正——避免旋转重建后闪一下
        deviceDeg = when (display?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        orientation = OrientationTracker(this).apply {
            onChanged = { deg ->
                deviceDeg = deg
                applyRotation()
            }
        }
        buildUi()
        ui.postDelayed(perfTicker, 1500)

        ui.post(exposureLoop)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_CAMERA
            )
        } else {
            startCamera()
        }
    }

    // ---------- 拨盘取值映射 ----------

    /** 0..1 → 1..maxZoom，取对数：低倍率段手感更细 */
    private fun zoomFromValue(v: Float): Float {
        val max = camera.controls.maxZoom.coerceAtLeast(1f)
        return Math.pow(max.toDouble(), v.toDouble()).toFloat().coerceIn(1f, max)
    }

    /** 0..1 → 曝光补偿档位 index */
    private fun evIndexFromValue(v: Float): Int {
        val c = camera.controls
        if (!c.hasEv) return 0
        return Math.round(c.evMin + v * (c.evMax - c.evMin)).toInt().coerceIn(c.evMin, c.evMax)
    }

    private fun evFromValue(v: Float): Float {
        val c = camera.controls
        return evIndexFromValue(v) * (if (c.evStep > 0f) c.evStep else 0f)
    }

    /** 手持能用的快门范围：1/15s ~ 1/2000s，和机身范围取交集 */
    private fun shutterSlowestNs(): Long =
        maxOf(camera.controls.shutterMinNs, 1_000_000_000L / 15)

    private fun shutterFastestNs(): Long =
        minOf(camera.controls.shutterMaxNs, 1_000_000_000L / 2000)

    /** 0..1 → 快门（对数刻度，右边越快） */
    private fun shutterNsFromValue(v: Float): Long {
        val fast = shutterFastestNs().toDouble()
        val slow = shutterSlowestNs().toDouble()
        return (slow * Math.pow(fast / slow, v.toDouble())).toLong()
    }

    private fun shutterValueFromNs(ns: Long): Float {
        val fast = shutterFastestNs().toDouble()
        val slow = shutterSlowestNs().toDouble()
        if (ns <= 0) return 0f
        val v = Math.log(ns / slow) / Math.log(fast / slow)
        return v.toFloat().coerceIn(0f, 1f)
    }

    /** 0..1 → ISO（对数刻度） */
    private fun isoFromValue(v: Float): Int {
        val c = camera.controls
        val lo = c.isoMin.toDouble().coerceAtLeast(1.0)
        val hi = c.isoMax.toDouble().coerceAtLeast(lo + 1)
        return (lo * Math.pow(hi / lo, v.toDouble())).toInt().coerceIn(c.isoMin, c.isoMax)
    }

    private fun isoValueFromIso(iso: Int): Float {
        val c = camera.controls
        val lo = c.isoMin.toDouble().coerceAtLeast(1.0)
        val hi = c.isoMax.toDouble().coerceAtLeast(lo + 1)
        if (iso <= 0) return 0f
        return (Math.log(iso / lo) / Math.log(hi / lo)).toFloat().coerceIn(0f, 1f)
    }

    /** 快门显示成 1/125、1/60 这种 */
    private fun shutterText(ns: Long): String {
        if (ns <= 0) return "--"
        return if (ns >= 1_000_000_000L) {
            String.format("%.0fs", ns / 1_000_000_000.0)
        } else {
            "1/" + Math.round(1_000_000_000.0 / ns)
        }
    }

    private fun buildUi() {
        // 系统通知栏已隐藏，这里自绘一条很窄的：左时间、右电量
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 屏幕有 R 角，左右各留 44dp 安全区，电量才不会被吃掉
            setPadding(dp(44), 0, dp(44), 0)
            setBackgroundColor(Color.BLACK)
        }
        clockText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            isSingleLine = true
        }
        batteryText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            isSingleLine = true
        }
        topBar.addView(clockText)
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(batteryText)
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // ---------- 左：取景 ----------
        val previewBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = GlPreview(this, lut).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        camera.onPreviewSize = { size ->
            preview.cameraAspect = size.width.toFloat() / size.height
        }
        camera.onError = { msg ->
            ui.post {
                status.text = msg
                status.visibility = View.VISIBLE
            }
        }
        previewBox.addView(preview)

        // 相机出画之前先在取景位置放 logo：黑底白字，和黑取景区连成一片
        splashLogo = ImageView(this).apply {
            setImageResource(R.drawable.logo_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewBox.addView(
            splashLogo,
            FrameLayout.LayoutParams(dp(320), dp(320), Gravity.CENTER)
        )


        status = TextView(this).apply {
            text = "ARRI 直出 · 就绪"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        previewBox.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12)
                leftMargin = dp(12)
            }
        )

        // 点击对焦的视觉反馈
        focusRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), 0xCCFFD400.toInt())
            }
            visibility = View.INVISIBLE
        }
        previewBox.addView(
            focusRing,
            FrameLayout.LayoutParams(dp(64), dp(64))
        )

        // 点取景器对焦
        preview.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_UP -> {
                    val uv = previewPointToUv(e.x, e.y)
                    if (uv != null && camera.focusMode != CameraEngine.Focus.MF) {
                        camera.focusAt(uv.first, uv.second)
                        showFocusRing(e.x, e.y)
                        v.performClick()
                    } else if (uv == null) {
                        status.text = "取景器还没就绪"
                    }
                }
                android.view.MotionEvent.ACTION_DOWN -> v.performClick()
            }
            true
        }

        perf = TextView(this).apply {
            text = "FPS --"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        previewBox.addView(
            perf,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(10)
                leftMargin = dp(8)
            }
        )

        row.addView(
            previewBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f)
        )

        // ---------- 右：面板（左边一条细长波轮位，右边一列按钮） ----------
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(10), dp(10))
            // 面板底是纯黑：按钮各自带灰色胶囊，底上不再画任何灰块
            setBackgroundColor(Color.BLACK)
        }

        // 波轮位：平时宽度为 0，点按钮才展开（就地展开，不遮画面）
        wheelStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        zoomDial = WheelView(this, "变焦").apply {
            format = { v -> String.format("%.1f×", zoomFromValue(v)) }
            onChange = { v ->
                // 只推给 GL，不同步相机：省掉每 60ms 一次的重复请求
                zoomLevel = zoomFromValue(v)
                preview.targetZoom = zoomLevel
                preview.requestRender()
                updateWheelButtons()
            }
        }
        evDial = WheelView(this, "EV").apply {
            format = { v -> String.format("%+.1f", evFromValue(v)) }
            onChange = { v ->
                if (camera.mode == CameraEngine.Mode.S) {
                    // 同上：EV 每变一档，ISO 立刻 ×2 —— 亮度变化要跟手，不要匀速爬
                    val ev = evFromValue(v)
                    val d = Math.pow(2.0, (ev - lastEv).toDouble())
                    if (d.isFinite() && d > 0) {
                        camera.setSModeIso((camera.sModeIso * d).toInt())
                    }
                    lastEv = ev
                }
                onExposureDial(v)
                updateWheelButtons()
            }
        }
        shutterWheel = WheelView(this, "快门").apply {
            format = { v -> shutterText(shutterNsFromValue(v)) }
            onChange = { v ->
                val newNs = shutterNsFromValue(v)
                val oldNs = camera.manualShutterNs
                if (camera.mode == CameraEngine.Mode.S && oldNs > 0 && newNs > 0) {
                    // S 档：曝光 ∝ ISO × 快门，所以快门变多少、ISO 就反比补多少。
                    // 这样是**瞬间守恒**，不用等测光闭环一档一档爬——之前画面亮度要慢慢挪，
                    // 看着就是卡。
                    val iso = (camera.sModeIso * (oldNs.toDouble() / newNs)).toInt()
                    camera.setSModeIso(iso)
                }
                camera.setShutterNs(newNs)
                updateWheelButtons()
            }
        }
        isoWheel = WheelView(this, "ISO").apply {
            format = { v -> isoFromValue(v).toString() }
            onChange = { v ->
                camera.setIso(isoFromValue(v))
                updateWheelButtons()
            }
        }
        focusWheel = WheelView(this, "对焦").apply {
            format = { v -> focusText(focusDioptersFromValue(v)) }
            onChange = { v ->
                focusDiopters = focusDioptersFromValue(v)
                camera.setFocusDiopters(focusDiopters)
                updateWheelButtons()
            }
        }
        wheels = listOf(zoomDial, evDial, shutterWheel, isoWheel, focusWheel)
        wheels.forEach { w ->
            w.visibility = View.GONE
            wheelStrip.addView(
                w,
                LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = dp(6) }
            )
        }
        panel.addView(
            wheelStrip,
            LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 横向两栏：左边数值按钮，右边快门/录像/模式键
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(3), 0x66FFFFFF)
            }
            setOnClickListener { takePhoto() }
        }
        // 右栏：快门 → 录像 → 模式键，都贴右上角
        val camCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(3), 0x66FFFFFF)
            }
            setOnClickListener { takePhoto() }
        }
        camCol.addView(
            shutter,
            LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { bottomMargin = dp(10) }
        )

        recordBtn = View(this).apply {
            background = recordIcon(false)
            setOnClickListener { toggleRecording() }
        }
        camCol.addView(
            recordBtn,
            LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { bottomMargin = dp(12) }
        )

        modeBtn = TextView(this).apply {
            text = "AUTO"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x33FFFFFF)
            setOnClickListener { cycleMode() }
        }
        camCol.addView(
            modeBtn,
            LinearLayout.LayoutParams(dp(66), dp(36))
        )

        // 测光模式：对应 ARRI 曝光表的测光区域（点测 / 中央重点 / 多区）
        meterBtn = TextView(this).apply {
            text = METER_NAMES[1]
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x33FFFFFF)
            setOnClickListener {
                meterMode = (meterMode + 1) % METER_NAMES.size
                preview.meterMode = meterMode
                meterBtn.text = METER_NAMES[meterMode]
            }
        }
        camCol.addView(
            meterBtn,
            LinearLayout.LayoutParams(dp(66), dp(36)).apply { topMargin = dp(6) }
        )

        // 对焦模式：AF-S / AF-C / MF
        focusBtn = TextView(this).apply {
            text = FOCUS_NAMES[focusIndex]
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x33FFFFFF)
            setOnClickListener {
                focusIndex = (focusIndex + 1) % FOCUS_MODES.size
                applyFocusMode()
            }
        }
        camCol.addView(
            focusBtn,
            LinearLayout.LayoutParams(dp(66), dp(36)).apply { topMargin = dp(6) }
        )

        // 设置：和上面几个按钮同款胶囊，排在对焦模式下面
        settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            background = pill(0x33FFFFFF)
            setOnClickListener { openSettings() }
        }
        camCol.addView(
            settingsBtn,
            LinearLayout.LayoutParams(dp(66), dp(36)).apply { topMargin = dp(6) }
        )

        // 画幅：3:2 / 16:9 / 2.35:1，循环切换
        ratioBtn = TextView(this).apply {
            text = RATIO_NAMES[ratioIndex]
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x33FFFFFF)
            setOnClickListener {
                if (recording) return@setOnClickListener   // 录制中不允许改画幅
                ratioIndex = (ratioIndex + 1) % RATIOS.size
                ratioBtn.text = RATIO_NAMES[ratioIndex]
                applyFrameRatio()
            }
        }
        // 画幅挪到设置页里了，面板上不再显示
        ratioBtn.visibility = View.GONE

        // 左栏：四个数值按钮，排在快门/录像左侧，竖向排列不超出屏幕
        val valueCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        mfBtn = wheelButton("对焦", "∞", MF).apply {
            textSize = 11f
            setPadding(dp(9), dp(8), dp(9), dp(8))
        }
        mfBtn.visibility = View.GONE          // 只有 MF 档才出现
        valueCol.addView(
            mfBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        zoomBtn = wheelButton("变焦", "1.0×", ZOOM)
        evBtn = wheelButton("EV", "+0.0", EV)
        shutterBtn = wheelButton("快门", "1/125", SHUTTER)
        isoBtn = wheelButton("ISO", "400", ISO)
        for (b in listOf(zoomBtn, evBtn, shutterBtn, isoBtn)) {
            valueCol.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        rightCol.addView(
            valueCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        )
        rightCol.addView(
            camCol,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.TOP }
        )

        panel.addView(
            rightCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )

        row.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        applyMode(CameraEngine.Mode.AUTO, force = true)

        setContentView(root)
        hideSystemBars()
        startStatusTicker()
        waitForFirstFrame()
    }

    /** 一个「数值按钮」：点开对应波轮（再点一次收起） */
    private fun wheelButton(title: String, value: String, kind: Int): TextView =
        TextView(this).apply {
            text = "$title $value"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            isSingleLine = true          // 「对焦 21cm」这类长文案不折行
            setPadding(dp(10), dp(7), dp(10), dp(7))
            // 按钮自带灰色圆角胶囊；面板底是纯黑，两者区分开
            background = pill(0x2EFFFFFF)
            setOnClickListener { if (isEnabled) toggleWheel(kind) }
        }

    private fun toggleWheel(kind: Int) {
        if (openWheelKind == kind) {
            closeWheel()
            return
        }
        openWheel(kind)
    }

    private fun openWheel(kind: Int) {
        val w = when (kind) {
            ZOOM -> zoomDial
            EV -> evDial
            SHUTTER -> shutterWheel
            ISO -> isoWheel
            else -> focusWheel
        }
        if (!w.isEnabled) return
        wheels.forEach { it.visibility = if (it === w) View.VISIBLE else View.GONE }
        openWheelKind = kind
        updateWheelButtons()
    }

    private fun closeWheel() {
        wheels.forEach { it.visibility = View.GONE }
        openWheelKind = -1
        updateWheelButtons()
    }

    /** 刷新四个入口按钮上的数值，并标出当前展开的那个 */
    private fun updateWheelButtons() {
        zoomBtn.text = String.format("变焦 %.1f×", zoomLevel)
        evBtn.text = if (camera.mode == CameraEngine.Mode.M) {
            String.format("曝光 %+.1f", evDial.value * 4f - 2f)
        } else {
            String.format("EV %+.1f", evFromValue(evDial.value))
        }
        shutterBtn.text = "快门 " + shutterText(camera.manualShutterNs)
        isoBtn.text = "ISO " + if (camera.mode == CameraEngine.Mode.S) {
            camera.sModeIso
        } else {
            camera.manualIso
        }
        mfBtn.text = "对焦 " + focusText(focusDiopters)

        for ((i, b) in listOf(zoomBtn, evBtn, shutterBtn, isoBtn).withIndex()) {
            b.background = pill(if (i == openWheelKind) 0x59FFFFFF else 0x2EFFFFFF)
        }
        // 对焦按钮也是波轮入口，展开时同样要高亮
        mfBtn.background = pill(if (openWheelKind == MF) 0x59FFFFFF else 0x2EFFFFFF)
    }

    /** setContentView 之后才能真正操作 insets */
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                // 不要 setDecorFitsSystemWindows(false)：那样内容会伸到导航栏底下，
                // 横屏时右侧按钮会被裁掉
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars())
                    it.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 自绘状态条：时间 + 电量，20 秒刷一次 */
    private fun startStatusTicker() {
        val tick = object : Runnable {
            override fun run() {
                clockText.text = java.text.SimpleDateFormat(
                    "HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date())
                batteryText.text = batteryLabel()
                ui.postDelayed(this, 20_000)
            }
        }
        ui.postDelayed(tick, 0)
    }

    private fun batteryLabel(): String {
        val i = registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ""
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return ""
        val pct = level * 100 / scale
        val charging = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ==
                android.os.BatteryManager.BATTERY_STATUS_CHARGING
        return if (charging) "$pct% ⚡" else "$pct%"
    }

    /** 等取景器真正出第一帧，再把启动 logo 淡出 */
    private fun waitForFirstFrame() {
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val drawn = preview.framesDrawn > 0
                val timeout = System.currentTimeMillis() - start > 6000
                if (drawn || timeout) {
                    splashLogo?.animate()?.alpha(0f)?.setDuration(220)?.withEndAction {
                        splashLogo?.visibility = View.GONE
                    }?.start()
                } else {
                    ui.postDelayed(this, 80)
                }
            }
        }
        ui.postDelayed(tick, 80)
    }

    /**
     * 应用当前画幅：传感器是 4:3，画幅更宽时在纹理坐标上裁掉上下，
     * 预览、照片、录像都用同一套裁切，所见即所得。
     */
    private fun applyFrameRatio() {
        val target = RATIOS[ratioIndex]
        val sensor = 4f / 3f
        // 保留的高度比例 = 传感器画幅 / 目标画幅（2.35:1 时只保留 57% 的高度）。
        // 之前写成 target/sensor 再 coerceAtMost(1) 会被夹成 1 —— 等于没裁，
        // 而四边形按宽画幅摆放，画面就被压扁了。
        val k = (sensor / target).coerceIn(0.15f, 1f)
        val sideways = previewRotation() == 90 || previewRotation() == 270
        if (sideways) {
            preview.setFrameCrop(k, 1f)
        } else {
            preview.setFrameCrop(1f, k)
        }
        // 画面有效宽高比 = target，交给取景器做等比适配
        preview.cameraAspect = if (sideways) 1f / target else target
        preview.requestRender()
    }

    /** 设置页开着时，返回键先关设置页，不要直接退应用 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::settingsPanel.isInitialized && settingsPanel.visibility == View.VISIBLE) {
            settingsPanel.visibility = View.GONE
            return
        }
        super.onBackPressed()
    }

    // ================= 设置页 =================

    private fun openSettings() {
        if (::settingsPanel.isInitialized && settingsPanel.visibility == View.VISIBLE) {
            settingsPanel.visibility = View.GONE
            return
        }
        if (!::settingsPanel.isInitialized) buildSettingsPanel()
        settingsPanel.visibility = View.VISIBLE
        refreshSettings()
    }

    private fun refreshSettings() {
        ratioValue.text = RATIO_NAMES[ratioIndex]
        resValue.text = RES_NAMES[resIndex]
        fpsValue.text = FPS_NAMES[fpsIndex]
    }

    private fun buildSettingsPanel() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xF2000000.toInt())
            isClickable = true
            setOnClickListener { openSettings() }     // 点空白关闭
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF101010.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            isClickable = true
        }

        box.addView(TextView(this).apply {
            text = "设置"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(2), dp(2), dp(2), dp(12))
        })

        fun row(label: String): TextView {
            val v = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = pill(0x2EFFFFFF)
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            line.addView(TextView(this).apply {
                text = label
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(v)
            box.addView(line)
            return v
        }

        ratioValue = row("画幅")
        ratioValue.setOnClickListener {
            ratioIndex = (ratioIndex + 1) % RATIOS.size
            applyFrameRatio()
            refreshSettings()
        }

        resValue = row("分辨率")
        resValue.setOnClickListener {
            resIndex = (resIndex + 1) % RES_NAMES.size
            camera.recordPreference = resIndex
            restartCamera()
            refreshSettings()
        }

        fpsValue = row("帧率")
        fpsValue.setOnClickListener {
            fpsIndex = (fpsIndex + 1) % FPS_NAMES.size
            camera.targetFps = FPS_VALUES[fpsIndex]
            restartCamera()
            refreshSettings()
        }

        box.addView(TextView(this).apply {
            text = "分辨率 / 帧率改完会重启相机"
            setTextColor(0x66FFFFFF)
            textSize = 11f
            setPadding(dp(2), dp(10), dp(2), 0)
        })

        overlay.addView(
            box,
            FrameLayout.LayoutParams(
                dp(280),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        (window.decorView as FrameLayout).addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        settingsPanel = overlay
    }

    /** 改分辨率 / 帧率要重开会话 */
    private fun restartCamera() {
        ui.postDelayed({
            camera.close()
            preview.reemitSurfaces()
        }, 120)
    }

    /** 屏幕坐标 → 相机纹理 UV（和着色器同一套变换的逆运算） */
    private fun previewPointToUv(x: Float, y: Float): Pair<Float, Float>? {
        val vw = preview.width.toFloat()
        val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val qx = preview.quadScaleX
        val qy = preview.quadScaleY
        if (qx <= 0f || qy <= 0f) return null
        val ndcX = (x / vw) * 2f - 1f
        val ndcY = 1f - (y / vh) * 2f
        // 点在黑边上时钳到画面边缘，而不是直接丢弃——
        // 16:9 / 2.35 下黑边很大，「点了没反应」多半是这里被 return null 了
        val ax = ((ndcX - preview.quadOffsetX) / qx).coerceIn(-1f, 1f)
        val ay = (ndcY / qy).coerceIn(-1f, 1f)

        val px = ax / 2f
        val py = ay / 2f
        val th = Math.toRadians(-preview.rotationDeg.toDouble())
        val c = Math.cos(th).toFloat()
        val sn = Math.sin(th).toFloat()
        val rx = px * c - py * sn
        val ry = px * sn + py * c
        val z = preview.currentZoom.coerceAtLeast(1f)
        val u = rx * (preview.frameCropX / z) + 0.5f
        val v = ry * (preview.frameCropY / z) + 0.5f
        if (u !in 0f..1f || v !in 0f..1f) return null
        return u to v
    }

    /** 点完闪一下黄圈 */
    private fun showFocusRing(x: Float, y: Float) {
        val size = dp(64)
        val lp = focusRing.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (x - size / 2).toInt()
        lp.topMargin = (y - size / 2).toInt()
        focusRing.layoutParams = lp
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.25f
        focusRing.scaleY = 1.25f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        ui.postDelayed({
            focusRing.animate().alpha(0f).setDuration(320).withEndAction {
                focusRing.visibility = View.INVISIBLE
            }.start()
        }, 900)
    }

    /** 切对焦模式：相机侧改 AF 模式，UI 侧决定要不要露出「对焦」按钮 */
    private fun applyFocusMode() {
        // 不支持的机器跳过 MF
        if (FOCUS_MODES[focusIndex] == CameraEngine.Focus.MF &&
            !camera.controls.hasManualFocus
        ) {
            focusIndex = 0
        }
        val f = FOCUS_MODES[focusIndex]
        camera.setFocusMode(f)
        focusBtn.text = FOCUS_NAMES[focusIndex]
        mfBtn.visibility = if (f == CameraEngine.Focus.MF) View.VISIBLE else View.GONE
        if (openWheelKind == MF && f != CameraEngine.Focus.MF) closeWheel()
        updateWheelButtons()
    }

    /** 0..1 → 屈光度（0 = 无穷远，越大越近） */
    private fun focusDioptersFromValue(v: Float): Float =
        v * camera.controls.focusMaxDiopters

    /** 屈光度 → 人看的距离 */
    private fun focusText(d: Float): String {
        if (d < 0.02f) return "∞"
        val m = 1f / d
        return if (m >= 1f) String.format("%.1fm", m) else String.format("%.0fcm", m * 100)
    }

    /** 曝光波轮在 AUTO/S 档调的是 EV，M 档是只读指示器，不接这个回调 */
    private fun onExposureDial(v: Float) {
        if (camera.mode == CameraEngine.Mode.M) return
        camera.setEv(evIndexFromValue(v))
    }

    /** 单击循环：S → AUTO → M → S … */
    private fun cycleMode() {
        val next = when (camera.mode) {
            CameraEngine.Mode.S -> CameraEngine.Mode.AUTO
            CameraEngine.Mode.AUTO -> CameraEngine.Mode.M
            CameraEngine.Mode.M -> CameraEngine.Mode.S
        }
        applyMode(next)
    }

    /** 切换模式：决定哪几个入口按钮出现、哪些只读 */
    private fun applyMode(mode: CameraEngine.Mode, force: Boolean = false) {
        if (!force && camera.mode == mode) return
        camera.setMode(mode)
        modeBtn.text = when (mode) {
            CameraEngine.Mode.S -> "S"
            CameraEngine.Mode.AUTO -> "AUTO"
            CameraEngine.Mode.M -> "M"
        }
        val c = camera.controls

        zoomBtn.visibility = View.VISIBLE
        zoomBtn.alpha = if (c.hasZoom) 1f else 0.35f
        zoomDial.isEnabled = c.hasZoom

        when (mode) {
            CameraEngine.Mode.AUTO -> {
                shutterBtn.visibility = View.GONE
                isoBtn.visibility = View.GONE
                evBtn.visibility = View.VISIBLE
                evBtn.alpha = 1f
                evBtn.isEnabled = true
                evDial.isEnabled = c.hasEv
                evDial.caption = "EV"
                evDial.format = { v -> String.format("%+.1f", evFromValue(v)) }
                evDial.value = if (c.hasEv && c.evMax > c.evMin) {
                    ((camera.evIndex - c.evMin).toFloat() / (c.evMax - c.evMin)).coerceIn(0f, 1f)
                } else 0.5f
            }

            CameraEngine.Mode.S -> {
                shutterBtn.visibility = View.VISIBLE
                isoBtn.visibility = View.VISIBLE
                isoBtn.alpha = 0.35f          // S 档 ISO 只显示，按钮置灰点不开
                isoBtn.isEnabled = false
                isoWheel.isEnabled = false
                shutterWheel.isEnabled = true
                shutterWheel.value = shutterValueFromNs(camera.manualShutterNs)
                isoWheel.value = isoValueFromIso(camera.sModeIso)

                evBtn.visibility = View.VISIBLE
                evBtn.alpha = 1f
                evBtn.isEnabled = true
                evDial.isEnabled = c.hasEv
                evDial.caption = "EV"
                evDial.format = { v -> String.format("%+.1f", evFromValue(v)) }
                lastEv = evFromValue(evDial.value)
            }

            CameraEngine.Mode.M -> {
                shutterBtn.visibility = View.VISIBLE
                isoBtn.visibility = View.VISIBLE
                isoBtn.alpha = 1f
                isoBtn.isEnabled = true
                isoWheel.isEnabled = true
                shutterWheel.isEnabled = true
                shutterWheel.value = shutterValueFromNs(camera.manualShutterNs)
                isoWheel.value = isoValueFromIso(camera.manualIso)

                evBtn.visibility = View.VISIBLE
                evBtn.alpha = 0.35f           // M 档 EV 只是指示器，按钮置灰
                evBtn.isEnabled = false
                evDial.isEnabled = false
                evDial.caption = "曝光"
                evDial.format = { v -> String.format("%+.1f", v * 4f - 2f) }
            }
        }

        // 切档后如果正展开的那个波轮变成了只读（M 档的 EV、S 档的 ISO），自动收起
        val openWheel = when (openWheelKind) {
            ZOOM -> zoomDial
            EV -> evDial
            SHUTTER -> shutterWheel
            ISO -> isoWheel
            MF -> focusWheel
            else -> null
        }
        if (openWheel != null && !openWheel.isEnabled) closeWheel()

        updateWheelButtons()
    }

    /**
     * S 档的软件自动 ISO，兼 M 档的曝光指示。
     *
     * 每 600ms 取一次取景器中心亮度，只在明显偏离目标时才动，每步最多 1/3 档：
     * 之前 300ms 无条件重算，ISO 在相邻档之间来回跳，画面就是一顿一顿的。
     */
    private val exposureLoop = object : Runnable {
        override fun run() {
            // 用户正在拨轮子时先让开：一边拖一边改 ISO 会互相打架，画面看着就是卡
            if (wheels.any { it.isDragging }) {
                ui.postDelayed(this, 250)
                return
            }
            val c = camera.controls
            when (camera.mode) {
                CameraEngine.Mode.S -> {
                    preview.requestLuma()
                    val luma = preview.lastLuma
                    if (luma > 0.02f && c.hasManual) {
                        val ev = evFromValue(evDial.value)
                        val target = (0.45f * Math.pow(2.0, ev.toDouble())).toFloat()
                            .coerceIn(0.10f, 0.90f)
                        val ratio = target / luma
                        if (ratio < 0.88f || ratio > 1.14f) {
                            val gain = Math.pow(ratio.toDouble(), 0.45).toFloat()
                                .coerceIn(0.8f, 1.25f)
                            val cur = camera.sModeIso
                            val next = (cur * gain).toInt().coerceIn(c.isoMin, c.isoMax)
                            if (next != cur) {
                                camera.setSModeIso(next)
                                updateWheelButtons()
                            }
                        }
                    }
                }

                CameraEngine.Mode.M -> {
                    preview.requestLuma()
                    val luma = preview.lastLuma
                    if (luma > 0.02f) {
                        val dev = (Math.log(luma / 0.45) / Math.log(2.0)).toFloat()
                            .coerceIn(-2f, 2f)
                        evDial.value = ((dev + 2f) / 4f).coerceIn(0f, 1f)
                        updateWheelButtons()
                    }
                }

                CameraEngine.Mode.AUTO -> Unit
            }
            ui.postDelayed(this, 800)
        }
    }

    /** 相机就绪后，按这台机器支持的能力初始化拨盘 */
    private fun setupDials(c: CameraEngine.Controls) {
        ui.post {
            zoomDial.isEnabled = c.hasZoom
            zoomDial.alpha = if (c.hasZoom) 1f else 0.35f
            zoomDial.value = 0f                       // 1.0×
            applyMode(camera.mode, force = true)
        }
    }

    private fun startCamera() {
        // GlPreview 会在 GL 上下文就绪后回调 onCameraSurfaceReady → camera.open()
        preview.onCameraSurfaceReady = { tex, recTex ->
            val cfg = camera.open(tex, recTex)
            if (cfg != null) {
                sensorOrientation = cfg.sensorOrientation
                previewSize = cfg.previewSize
                recordSize = cfg.recordSize
                preview.recordStreamActive = cfg.recordSize != null
                preview.cameraAspect = cfg.previewSize.width.toFloat() / cfg.previewSize.height
                setupDials(cfg.controls)

                applyFrameRatio()
                applyRotation()
                ui.post {
                    status.text = "ARRI 直出 · ${cfg.stillSize.width}×${cfg.stillSize.height}"
                }
            }
        }
        camera.onStill = { image ->
            if (!busy) {
                runCatching { image.close() }
            } else {
                processStill(image)
            }
        }
    }

    /** 系统显示方向（窗口朝向），0/90/180/270 */
    private fun displayRotation(): Int =
        when (display?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }

    /**
     * 取景器角度 = 传感器方向 − 内容该转多少。
     *
     * 注意「窗口方向」既不是 `Display.rotation` 也不是 `screenWidthDp`：Activity 锁横屏时，
     * 设备哪怕物理竖持，这两个值可能仍是竖屏的，直接拿来减就会把画面转成竖条。
     * 所以用**窗口真实像素尺寸**判断横竖；只有系统真的旋转过（rotation 非 0）才采信它——
     * 那样才能区分横屏正向和反向。
     */
    private fun previewRotation(): Int {
        val b = windowBounds()
        val landscapeWindow = b.width() > b.height()
        val windowDeg = when (val dr = displayRotation()) {
            0 -> if (landscapeWindow) 90 else 0
            else -> dr
        }
        var rot = ((sensorOrientation - windowDeg) % 360 + 360) % 360
        // 安全网：横屏界面里转 90/270 只会得到一条竖画面（方向信息不同步时就会这样），
        // 直接拉回 0/180。真实机型正确算出来的本来就是 0 或 180，不受影响。
        if (landscapeWindow && (rot == 90 || rot == 270)) rot = 0
        return rot
    }

    /** 当前 Activity 窗口的像素尺寸 */
    private fun windowBounds(): android.graphics.Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getSize(p)
            android.graphics.Rect(0, 0, p.x, p.y)
        }
    }

    /**
     * 成片角度跟**握持方向**走（传感器方向 − 加速度计）。
     *
     * 这样即使系统关了自动旋转、窗口一直是竖的，横着拍出来的照片也是横的。
     */
    private fun photoRotation(): Int =
        ((sensorOrientation - deviceDeg) % 360 + 360) % 360

    private fun applyRotation() {
        preview.rotationDeg = previewRotation()
        preview.requestRender()
    }

    private fun takePhoto() {
        if (busy) return
        busy = true
        shutter.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        camera.capture()
    }

    /** 录像按钮外观：空闲是红圆点，录制中是红方块 */
    /**
     * 录像键：外圈永远是圆形，录制中在正中间加一块白色圆角矩形做区分。
     * （原先是整颗键变成方块，形状跳一下很丑。）
     */
    private fun recordIcon(recording: Boolean): Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE5484D.toInt())
            setStroke(dp(3), 0xCCFFFFFF.toInt())
        }
        if (!recording) return ring

        val mark = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(5).toFloat()   // 圆角正方形
            setColor(Color.WHITE)
        }
        // 圆角正方形（之前是长方形，看着像一条横杠）
        val whole = BUTTON_SIZE
        val w = dp(20)
        val h = dp(20)
        val inner = InsetDrawable(
            mark,
            (whole - w) / 2, (whole - h) / 2,
            (whole - w) / 2, (whole - h) / 2,
        )
        return LayerDrawable(arrayOf(ring, inner))
    }

    private fun toggleRecording() {
        if (!recording) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val p = previewSize
        if (p == null) {
            status.text = "相机还没就绪"
            return
        }
        // 编码尺寸：有独立 4K 录制流就用它（横竖跟着握持方向转），否则退回预览流并压到 720 短边
        val rot = photoRotation()
        val sideways = rot == 90 || rot == 270
        val rec = camera.currentRecordSize

        preview.recordStreamActive = rec != null
        var w: Int
        var h: Int
        // 画幅：4:3 的录制流按比例裁成目标画幅（高度按比例缩小）
        val ratio = RATIOS[ratioIndex]
        val sensorRatio = 4f / 3f
        if (rec != null) {
            w = if (sideways) rec.height else rec.width
            h = if (sideways) rec.width else rec.height
            // 三种画幅都比 4:3 宽，所以裁的是高度，宽度不变
            if (Math.abs(ratio - sensorRatio) > 0.01f) {
                h = (w / ratio).toInt()
            }
        } else {
            w = if (sideways) p.height else p.width
            h = if (sideways) p.width else p.height
            val shortSide = minOf(w, h)
            if (shortSide > VIDEO_SHORT_SIDE) {
                val k = VIDEO_SHORT_SIDE.toFloat() / shortSide
                w = (w * k).toInt()
                h = (h * k).toInt()
            }
        }
        // H.264 要求偶数尺寸
        w = (w / 2) * 2
        h = (h / 2) * 2

        val surface = recorder.start(w, h, FPS_VALUES[fpsIndex])
        if (surface == null) {
            status.text = "录像启动失败（编码器不可用）"
            return
        }
        preview.setEncoderTarget(surface, w, h, rot)
        recording = true
        recordStart = android.os.SystemClock.elapsedRealtime()
        recordBtn.background = recordIcon(true)
        // 录制中不能改画幅：编码尺寸已经定死了，改了会写出尺寸不一致的流
        ratioBtn.isEnabled = false
        ratioBtn.alpha = 0.3f
        status.text = "● 录制中 00:00"
        ui.post(ticker)
        // 编码器的 EGL 表面是在 GL 线程上建的，几百毫秒后才知道成没成；
        // 失败就直接停掉并说清原因，别让用户录了半天才发现没录上
        ui.postDelayed({
            val err = preview.lastEglError
            if (recording && err != null) {
                stopRecording()
                status.text = "录像不可用：$err"
            }
        }, 700)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        ui.removeCallbacks(ticker)
        recordBtn.background = recordIcon(false)
        ratioBtn.isEnabled = true
        ratioBtn.alpha = 1f
        status.text = "正在保存录像…"
        // 顺序很重要：先让 GL 线程停止写编码器（并等它确认），再收编码器。
        // 之前直接 stop，GL 线程可能还在 swap，muxer 收到废数据 → 保存失败。
        work.execute {
            preview.stopEncoderAndWait(1500)
            val uri = recorder.stop()
            ui.post {
                status.text = if (uri != null) {
                    "已保存录像到 相册/Movies/MAGIRRI Lume"
                } else {
                    "录像保存失败：${recorder.lastError ?: "未知原因"}"
                }
            }
        }
    }

    private fun processStill(image: Image) {
        work.execute {
            try {
                val t0 = System.currentTimeMillis()
                val (argb, w, h) = PhotoProcessor.yuvToArgb(image, strength, lut)
                image.close()
                val (rotated0, rw0, rh0) = PhotoProcessor.rotate(argb, w, h, photoRotation())
                // 按当前画幅居中裁切，和取景器看到的构图一致
                val (crop0, cw0, ch0) = PhotoProcessor.cropToAspect(
                    rotated0, rw0, rh0, RATIOS[ratioIndex]
                )
                // 再按当前变焦倍率裁一刀（预览是 GL 数字变焦，照片要跟着裁才对得上）
                val (rotated, rw, rh) = PhotoProcessor.cropZoom(crop0, cw0, ch0, zoomLevel)
                val uri = PhotoProcessor.saveJpeg(this, rotated, rw, rh)
                val ms = System.currentTimeMillis() - t0
                ui.post {
                    busy = false
                    status.text = if (uri != null) {
                        "已保存 ${rw}×${rh} · ${ms}ms"
                    } else {
                        "保存失败"
                    }
                }
            } catch (t: Throwable) {
                runCatching { image.close() }
                Log.w(TAG, "处理失败: ${t.message}")
                ui.post {
                    busy = false
                    status.text = "处理失败: ${t.message}"
                    Toast.makeText(this, "处理失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                status.text = "没有相机权限"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        orientation.stop()
        if (recording) stopRecording()
        preview.onPause()
        camera.close()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
        preview.onResume()
        // GL 表面会在 onResume 后重建，届时 onCameraSurfaceReady 会再回调一次 → 重新开相机
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun pill(argb: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(99).toFloat()
        setColor(argb)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()



    /** 快门与录像按钮同尺寸 */

    private val BUTTON_SIZE: Int get() = dp(64)

    companion object {
        private const val TAG = "arccam"
        private const val REQ_CAMERA = 21





        /** ARRI 曝光表的测光区域：Spot / Center / Multi */

        private val METER_NAMES = listOf("点测", "中央", "多区")



        /** 画幅：3:2 / 16:9 / 2.35:1 */

        private val RATIOS = listOf(3f / 2f, 16f / 9f, 2.35f)

        private val RATIO_NAMES = listOf("3:2", "16:9", "2.35:1")

        /** 对焦模式：AF-S 单次 / AF-C 连续 / MF 手动 */
        private val FOCUS_MODES = listOf(
            CameraEngine.Focus.AF_S,
            CameraEngine.Focus.AF_C,
            CameraEngine.Focus.MF,
        )
        private val FOCUS_NAMES = listOf("AF-S", "AF-C", "MF")

        /** 设置页：分辨率与帧率 */
        private val RES_NAMES = listOf("4K", "1080P", "720P")
        private val FPS_NAMES = listOf("24", "30", "60")
        private val FPS_VALUES = listOf(24, 30, 60)



        private const val ZOOM = 0

        private const val EV = 1

        private const val SHUTTER = 2

        private const val ISO = 3
        private const val MF = 4



        /** 录像短边上限：720 最稳；1080 在部分机型上编码器跟不上，会拖慢取景器 */
        private const val VIDEO_SHORT_SIDE = 720
    }
}