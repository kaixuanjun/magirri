package com.magirri.lume

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * Camera2 的最小封装：预览一帧不落地，拍照走 YUV_420_888（要自己转 RGB 再套 LUT）。
 *
 * 不用 JPEG 输出是因为 JPEG 已经压过了，套 LUT 还得解码再编码，多一次损失；
 * YUV 直接转 RGB 可以一次成片。
 */
class CameraEngine(private val context: Context) {

    data class Config(
        val previewSize: Size,
        val stillSize: Size,
        val sensorOrientation: Int,
        val controls: Controls,
        /** 4K 录制流尺寸；null 表示这台机器给不了独立录制流 */
        val recordSize: Size? = null,
    )

    /** 这台机器支持哪些可变参数 */
    data class Controls(
        val maxZoom: Float = 1f,
        val evMin: Int = 0,
        val evMax: Int = 0,
        val evStep: Float = 0f,
        val isoMin: Int = 100,
        val isoMax: Int = 3200,
        val shutterMinNs: Long = 1_000_000_000L / 8000,
        val shutterMaxNs: Long = 1_000_000_000L / 8,
        /** 最近对焦距离（屈光度），0 = 只能对无穷远 */
        val focusMaxDiopters: Float = 0f,
    ) {
        val hasZoom: Boolean get() = maxZoom > 1.01f
        val hasEv: Boolean get() = evMax > evMin
        val hasManual: Boolean get() = isoMax > isoMin
        val hasManualFocus: Boolean get() = focusMaxDiopters > 0.05f
    }

    /** 对焦模式：单次 / 连续 / 手动 */
    enum class Focus { AF_S, AF_C, MF }

    /** 曝光模式：S 快门优先 / AUTO 全自动 / M 全手动 */
    enum class Mode { S, AUTO, M }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var recordTextureRef: SurfaceTexture? = null

    /** 当前生效的录制流尺寸；会话配置失败会退回 null */

    var currentRecordSize: Size? = null

        private set



    /** 录制流是否在跑（决定 GL 那边要不要把 4K 纹理画进编码器） */
    val hasRecordStream: Boolean get() = recordSurface != null
    private var activeArray: android.graphics.Rect? = null

    /** 当前变焦倍数（1.0 = 不裁） */
    var zoom = 1f
        private set
    private var pendingZoom = 1f

    /** 录制分辨率偏好：0 = 4K，1 = 1080P，2 = 720P（下次开会话生效） */
    var recordPreference = 0

    /** 一颗物理镜头 */
    data class Lens(val id: String, val ratio: Float, val focal: Float)

    /** 所有后摄镜头（按焦距排）；ratio 是相对主摄的倍率 */
    var lenses: List<Lens> = emptyList()
        private set

    /** 当前正在用的镜头 id */
    var activeLensId: String = ""
        private set

    /** 换镜头后回调（MainActivity 用来重刷控件与画幅） */
    var onLensSwitched: (() -> Unit)? = null

    private var lastTexture: SurfaceTexture? = null
    private var lastRecordTexture: SurfaceTexture? = null

    /** 目标帧率（同时约束 AE 的帧率范围与编码器） */
    var targetFps = 30

    /** AE 帧率区间（按 targetFps 选出来的） */
    private var fpsRange: android.util.Range<Int>? = null

    /**
     * 从手动档切回自动时，AE 会从"上一个手动曝光"慢慢爬。发一次 PRECAPTURE 让它快速收敛，
     * AE_STATE 到 CONVERGED 后复位。
     */
    @Volatile private var aePrecapture = false
    private var aePrecaptureAt = 0L

    /** AF 触发位：点一下置 START，对焦完成后复位 IDLE */
    @Volatile private var afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE

    /** 点击对焦的区域（sensor 坐标），null = 全画面 */
    private var afRegion: android.graphics.Rect? = null

    /** 对焦模式与手动焦距（屈光度，0 = 无穷远） */
    var focusMode = Focus.AF_C
        private set
    var focusDiopters = 0f
        private set

    /** 当前曝光补偿档位（单位是 evStep） */
    var evIndex = 0
        private set

    var mode = Mode.AUTO
        private set

    /** S 档由软件按目标 EV 自动收敛出来的 ISO */
    var sModeIso = 400
        private set

    /** M 档手动 ISO */
    var manualIso = 400
        private set

    /** S 档手动快门（ns） */
    var manualShutterNs = 1_000_000_000L / 125
        private set

    /** 进入 S/M 档时从 AE 结果里采到的值，用来把手动档的「另一半」定住 */
    private var sampledIso = 400
    private var sampledShutterNs = 1_000_000_000L / 125

    var controls = Controls()
        private set

    /** AE 实际用的 ISO / 快门，S、M 档进档时取它当另一半 */
    @Volatile var lastIso = 0
        private set
    @Volatile var lastShutterNs = 0L
        private set

    private val aeSampler = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            // 读回相机实际生效的变焦：GL 用它做补偿，才不会被"发了请求但还没生效"坑
            val az = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f
            } else {
                val crop = result.get(CaptureResult.SCALER_CROP_REGION)
                val full = activeArray
                if (crop != null && full != null && crop.width() > 0) {
                    full.width().toFloat() / crop.width()
                } else 1f
            }
            if (Math.abs(az - appliedZoom) > 0.004f) {
                appliedZoom = az
                Log.i(TAG, "相机变焦生效 %.2fx（请求 %.2fx）".format(az, zoom))
                onAppliedZoom?.invoke(az)
            }

            // 连续对焦掉焦太久（>1.2s 没 PASSIVE_FOCUSED）就补一次触发
            if (focusMode == Focus.AF_C) {
                val st2 = result.get(CaptureResult.CONTROL_AF_STATE)
                val focused = st2 == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    st2 == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                if (focused) {
                    afLostSince = 0L
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (afLostSince == 0L) afLostSince = now
                    else if (now - afLostSince > 1200L && afTrigger == CaptureRequest.CONTROL_AF_TRIGGER_IDLE) {
                        afLostSince = now
                        afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
                        reapply()
                    }
                }
            }
            // 一次 AF 扫描结束后把触发位复位，否则重复请求会一直重启对焦
            if (afTrigger == CaptureRequest.CONTROL_AF_TRIGGER_START) {
                val st = result.get(CaptureResult.CONTROL_AF_STATE)
                val done = st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    st == CaptureResult.CONTROL_AF_STATE_INACTIVE ||
                    st == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                    st == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    st == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
                if (done) {
                    afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    reapply()
                }
            }
            // AE 预拍收敛完成就复位，别一直挂着
            if (aePrecapture) {
                val now = android.os.SystemClock.uptimeMillis()
                val age = now - aePrecaptureAt
                // 关键：刚置位时，上一帧的 AE_STATE 往往还是 CONVERGED，
                // 立刻清掉等于预拍没发生 —— 至少挂 300ms 再接受 CONVERGED。
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val converged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                if (converged && age >= 300L) {
                    aePrecapture = false
                    aePrecaptureAt = 0L
                    Log.i(TAG, "AE 预拍完成，用时 ${age}ms")
                    reapply()
                }
            }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                lastIso = it
                sampledIso = it
                aeIso = it
            }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                lastShutterNs = it
                sampledShutterNs = it
                aeShutterNs = it
            }
        }
    }

    /** 拿到一张静止帧（处理完必须 close） */
    var onStill: ((Image) -> Unit)? = null

    var onPreviewSize: ((Size) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun open(texture: SurfaceTexture, recordTexture: SurfaceTexture? = null): Config? {
        if (device != null) return null   // 已经在跑了，别重复打开
        lastTexture = texture
        lastRecordTexture = recordTexture
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // 枚举后摄镜头：用焦距判断谁是超广角 / 主摄 / 长焦
        if (lenses.isEmpty()) lenses = scanLenses(manager)
        val id = activeLensId.takeIf { it.isNotEmpty() && it in lenses.map { l -> l.id } }
            ?: lenses.firstOrNull { it.ratio <= 1.01f }?.id
            ?: lenses.firstOrNull()?.id
            ?: manager.cameraIdList.firstOrNull() ?: run {
                onError?.invoke("没有可用相机")
                return null
            }
        activeLensId = id
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run {
                onError?.invoke("相机不支持输出配置")
                return null
            }
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // 变焦：API 30 起有 CONTROL_ZOOM_RATIO_RANGE，更早只能用 SCALER_CROP_REGION 自己裁
        val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        } else {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        // 曝光补偿：范围以「档」为单位，步长单独给
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        // 帧率：从可用区间里挑上界等于目标帧率的，优先低端也等于目标（恒定帧率）
        fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.let { ranges ->
                ranges.firstOrNull { it.upper == targetFps && it.lower == targetFps }
                    ?: ranges.filter { it.upper == targetFps }.minByOrNull { it.lower }
                    ?: ranges.minByOrNull { Math.abs(it.upper - targetFps) }
            }
        // 手动对焦：LENS_INFO_MINIMUM_FOCUS_DISTANCE 是最近合焦距离（屈光度），
        // 0 表示固定焦点镜头（只能对无穷远）
        val focusMax = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        // 手动曝光用的范围（S 档拨快门、M 档拨 ISO）
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        controls = Controls(
            maxZoom = maxZoom.coerceAtLeast(1f),
            evMin = evRange?.lower ?: 0,
            evMax = evRange?.upper ?: 0,
            evStep = evStep,
            isoMin = isoRange?.lower ?: 100,
            isoMax = isoRange?.upper ?: 3200,
            shutterMinNs = shutterRange?.lower ?: (1_000_000_000L / 8000),
            shutterMaxNs = shutterRange?.upper ?: (1_000_000_000L / 8),
            focusMaxDiopters = focusMax,
        )
        Log.i(
            TAG,
            "控制能力 zoom=${controls.maxZoom} ev=${controls.evMin}..${controls.evMax} " +
                    "iso=${controls.isoMin}..${controls.isoMax} " +
                    "shutter=${controls.shutterMinNs}..${controls.shutterMaxNs}ns " +
                    "focus=0..${controls.focusMaxDiopters}D fps=$fpsRange"
        )

        val previewSize = pickSize(
            map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(),
            1_300_000, 4.0 / 3.0   // 上限按**像素数**算，1280x960 ≈ 1.23M
        ) ?: run {
            onError?.invoke("找不到合适的预览尺寸")
            return null
        }
        // 8MP 以内：再大 ARGB 位图会吃掉 30MB+，中端机容易 OOM
        val stillSize = pickSize(
            map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty(), 8_000_000, 4.0 / 3.0
        ) ?: previewSize

        // 录制流：优先 4K（3840x2160），没有就退到 1080p；都没有就返回 null
        val recordSize = pickRecordSize(map, recordTexture != null)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        recordSize?.let { recordTexture?.setDefaultBufferSize(it.width, it.height) }
        onPreviewSize?.invoke(previewSize)
        Log.i(TAG, "预览 ${previewSize.width}x${previewSize.height} 录制流 ${recordSize}")

        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)

        reader = ImageReader.newInstance(
            stillSize.width, stillSize.height, ImageFormat.YUV_420_888, 2
        ).also { r ->
            r.setOnImageAvailableListener({ ir ->
                val img = try {
                    ir.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                img?.let { onStill?.invoke(it) }
            }, handler)
        }

        val previewSurface = Surface(texture)
        recordSurface = recordSize?.let { size ->
            recordTexture?.let { rt ->
                recordTextureRef = rt
                Surface(rt)
            }
        }
        try {
            @Suppress("DEPRECATION")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    startSession(cam, previewSurface)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    device = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    device = null
                    onError?.invoke("相机打开失败: $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            onError?.invoke("没有相机权限")
            return null
        } catch (e: Exception) {
            onError?.invoke("相机打开异常: ${e.message}")
            return null
        }

        currentRecordSize = recordSize

        return Config(previewSize, stillSize, orientation, controls, recordSize)
    }

    private fun startSession(cam: CameraDevice, previewSurface: Surface) {
        val readerSurface = reader?.surface ?: return
        this.previewSurface = previewSurface
        // 会话输出：预览 + 录制流 + 静态帧（录制流可能不存在）
        val targets = mutableListOf(previewSurface, readerSurface)
        recordSurface?.let { targets.add(it) }
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            s.setRepeatingRequest(previewRequest(cam, previewSurface), aeSampler, handler)
                        } catch (e: Exception) {
                            Log.w(TAG, "预览请求失败: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        // 预览 + 4K + 静态帧三路可能超出机型的流组合上限：
                        // 去掉录制流重试一次，别让整个相机起不来
                        if (recordSurface != null) {
                            Log.w(TAG, "三路输出超出限制，退回预览+静态帧两路")
                            recordSurface = null
                            currentRecordSize = null
                            onError?.invoke("4K 录制流不可用，已退回预览分辨率")
                            startSession(cam, previewSurface)
                        } else {
                            onError?.invoke("相机会话配置失败")
                        }
                    }
                },
                handler
            )
        } catch (e: Exception) {
            onError?.invoke("创建会话失败: ${e.message}")
        }
    }

    /** 预览请求：带上当前的变焦和曝光补偿 */
    private fun previewRequest(cam: CameraDevice, target: Surface): CaptureRequest =
        cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(target)
            // 录制流和预览同一条请求：一次采集喂两个 surface
            recordSurface?.let { addTarget(it) }
            applyFocus(this)
            applyZoomAndEv(this)
        }.build()

    /** 扫出所有后摄镜头，按焦距算出相对主摄的倍率 */
    private fun scanLenses(manager: CameraManager): List<Lens> {
        val infos = mutableListOf<Triple<String, Float, Long>>()
        for (cid in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(cid)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val area = (size.width * size.height).toLong()
                infos += Triple(cid, focal, area)
            } catch (_: Exception) {
            }
        }
        if (infos.isEmpty()) return emptyList()
        // 传感器最大的那颗当主摄（1.0×）
        val main = infos.maxByOrNull { it.third }!!
        val mainFocal = main.second
        val list = infos.map { Lens(it.first, it.second / mainFocal, it.second) }
            .sortedBy { it.ratio }
        Log.i(TAG, "镜头: " + list.joinToString { "${it.id}@%.2fx".format(it.ratio) })
        return list
    }

    /** 切到第 index 颗镜头（重开会话） */
    fun switchToLens(index: Int) {
        val lens = lenses.getOrNull(index) ?: return
        if (lens.id == activeLensId) return
        val tex = lastTexture ?: return
        val rec = lastRecordTexture
        activeLensId = lens.id
        zoom = 1f
        targetZoom = 1f
        zoomTicking = false
        close()
        open(tex, rec)
        onLensSwitched?.invoke()
    }

    /** AF-C 掉焦计时：太久没合焦就补一次触发 */
    private var afLostSince = 0L

    /** 对象移动/场景变化后重新触发一次连续对焦 */
    fun nudgeAf() {
        if (focusMode != Focus.AF_C) return
        afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
        reapply(immediate = true)
    }

    /** 当前镜头在列表里的下标 */
    fun lensIndex(): Int = lenses.indexOfFirst { it.id == activeLensId }.coerceAtLeast(0)

    /**
     * 测光模式：0 点测 / 1 中央重点 / 2 多区。
     *
     * Camera2 没有"测光模式"这个开关，测光范围只有 [CaptureRequest.CONTROL_AE_REGIONS]：
     * 给一个矩形就是按这块算 AE，给 null 就交回全画面。所以三种模式就是三种矩形：
     *  - 点测：AF 落下的小区域（没点过就用中心一小块）
     *  - 中央：中心一大块
     *  - 多区：null，让 HAL 全画面评估
     */
    @Volatile var meteringMode = 1

    /**
     * 自动测光闭环算出来的曝光偏移（单位：EV 档），叠加在用户设定的 EV 之上。
     * 单独放一个量，是为了让 EV 拨盘始终显示"用户设定的值"，不被闭环改来改去。
     */
    @Volatile private var aeOffsetStops = 0f
    @Volatile private var lastAeOffsetApplied = Float.NaN

    /** 当前闭环偏移（给 UI 看） */
    val aeOffsetNow: Float get() = aeOffsetStops

    fun setAeOffset(stops: Float) {
        val v = stops.coerceIn(-4f, 4f)
        aeOffsetStops = v
        if (lastAeOffsetApplied.isNaN() || Math.abs(v - lastAeOffsetApplied) >= 0.02f) {
            lastAeOffsetApplied = v
            reapply()
        }
    }
    private var lastMeterRect = -1

    /** S 档：相机 AE 当前给出的 ISO / 快门（AUTO 模式下一只在更新） */
    @Volatile var aeIso = 400
        private set
    @Volatile var aeShutterNs = 1_000_000_000L / 60
        private set

    /**
     * S 档工作方式，和 AUTO 一样优先用相机 AE：
     *  - false（默认）：AE 全权负责，画面顺滑，只有 AE 选的速度比用户设定慢时
     *    才由我们接管（见 enterSManual）
     *  - true：固定用户设定的快门 + 手动 ISO
     */
    @Volatile var sManualTakeover = false
        private set

    /** S 档接管：按 AE 当前的 ISO 换算到用户设定的快门，曝光量保持不变 */
    fun enterSManual() {
        if (sManualTakeover) return
        val target = manualShutterNs.coerceIn(controls.shutterMinNs, controls.shutterMaxNs)
        val iso = (aeIso.toDouble() * aeShutterNs / target).toInt()
            .coerceIn(controls.isoMin, controls.isoMax)
        Log.i(TAG, "S 档接管：AE ${aeIso}/${aeShutterNs / 1000}us -> 固定 ${target / 1000}us ISO $iso")
        manualIso = iso
        sManualTakeover = true
        reapply(immediate = true)
    }

    /** 交回相机 AE（亮起来之后） */
    fun exitSManual() {
        if (!sManualTakeover) return
        Log.i(TAG, "S 档交回 AE")
        sManualTakeover = false
        reapply(immediate = true)
    }

    /** 曝光锁定（AE-L） */
    @Volatile var aeLocked = false

    fun setAeLock(on: Boolean) {
        if (on == aeLocked) return
        aeLocked = on
        Log.i(TAG, if (on) "曝光锁定 ON" else "曝光锁定 OFF")
        aePrecapture = !on && mode == Mode.AUTO
        aePrecaptureAt = android.os.SystemClock.uptimeMillis()
        reapply(immediate = true)
    }

    fun setMetering(mode: Int) {
        if (mode == meteringMode) return
        meteringMode = mode
        val name = when (mode) {
            0 -> "点测"
            1 -> "中央重点"
            else -> "多区"
        }
        Log.i(TAG, "测光模式 -> $name")
        // 只改 AE 区域 HAL 未必会重新收敛，要踢一次 PRECAPTURE 让它重新测光，
        // 否则表现就是"有时候有效有时候无效"
        aePrecapture = true
        aePrecaptureAt = android.os.SystemClock.uptimeMillis()
        reapply(immediate = true)
    }

    private fun applyMetering(builder: CaptureRequest.Builder) {
        val a = activeArray ?: return
        if (meteringMode == 2) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            return
        }
        val rect = if (meteringMode == 0) {
            afRegion ?: run {
                val h = (Math.min(a.width(), a.height()) * 0.06f).toInt().coerceAtLeast(8)
                android.graphics.Rect(a.centerX() - h, a.centerY() - h, a.centerX() + h, a.centerY() + h)
            }
        } else {
            val h = (Math.min(a.width(), a.height()) * 0.22f).toInt().coerceAtLeast(8)
            android.graphics.Rect(a.centerX() - h, a.centerY() - h, a.centerX() + h, a.centerY() + h)
        }
        if (rect.width() != lastMeterRect) {
            lastMeterRect = rect.width()
            Log.i(TAG, "AE 测光区域 ${rect.width()}×${rect.height()}（模式 $meteringMode）")
        }
        builder.set(
            CaptureRequest.CONTROL_AE_REGIONS,
            arrayOf(
                android.hardware.camera2.params.MeteringRectangle(
                    rect, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
                )
            )
        )
    }

    /** 对焦模式：AF-S 单次、AF-C 连续、MF 手动锁焦 */
    private fun applyFocus(builder: CaptureRequest.Builder) {
        // 点击对焦落下的区域：AF-S / AF-C 都生效，MF 忽略
        if (focusMode != Focus.MF) {
            afRegion?.let {
                builder.set(
                    CaptureRequest.CONTROL_AF_REGIONS,
                    arrayOf(
                        android.hardware.camera2.params.MeteringRectangle(
                            it,
                            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
                        )
                    )
                )
            }
            // 触发位跟着 afTrigger 走：置 START 的那一条请求会真正触发一次扫描
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        }
        when (focusMode) {
            Focus.AF_S -> {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                )
            }
            Focus.AF_C -> {
                // CONTINUOUS_PICTURE 是为拍照优化的连续对焦，反应偏慢；
                // CONTINUOUS_VIDEO 才是为"持续追焦"调的，跟焦更快更稳
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }
            Focus.MF -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (controls.hasManualFocus) {
                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focusDiopters.coerceIn(0f, controls.focusMaxDiopters)
                    )
                }
            }
        }
    }

    /**
     * 点击对焦：u/v 是相机纹理坐标（0..1），换算成 sensor 的 metering 矩形。
     * 单次触发一次 AF 扫描，然后恢复重复请求。
     */
    fun focusAt(u: Float, v: Float) {
        if (focusMode == Focus.MF) return
        val a = activeArray ?: return
        if (session == null) return

        val cx = (u.coerceIn(0f, 1f) * a.width()).toInt() + a.left
        val cy = (v.coerceIn(0f, 1f) * a.height()).toInt() + a.top
        // 对焦框边长：短边的 12%
        val r = (Math.min(a.width(), a.height()) * 0.06f).toInt().coerceAtLeast(8)
        afRegion = android.graphics.Rect(
            (cx - r).coerceIn(a.left, a.right),
            (cy - r).coerceIn(a.top, a.bottom),
            (cx + r).coerceIn(a.left, a.right),
            (cy + r).coerceIn(a.top, a.bottom),
        )
        // 置 START 后由重复请求带下去；对焦结束在 aeSampler 里复位。
        // 之前是「单次 capture 触发 + 立刻恢复重复请求」，那条重复请求经常把触发冲掉，
        // 表现就是点十次对三次。
        afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
        if (meteringMode == 0) {
            aePrecapture = true                       // 点测：换测光点后重新测
            aePrecaptureAt = android.os.SystemClock.uptimeMillis()
        }
        reapply(immediate = true)
    }

    /** 切对焦模式 */
    fun setFocusMode(f: Focus) {
        if (f == focusMode) return
        focusMode = f
        reapply()
    }

    /** 手动对焦：屈光度，0 = 无穷远，越大越近 */
    fun setFocusDiopters(d: Float) {
        focusDiopters = d.coerceIn(0f, controls.focusMaxDiopters)
        if (focusMode == Focus.MF) reapply()
    }

    /** 把变焦 / 曝光（含 S/M 手动档）写进任意请求 */
    private fun applyZoomAndEv(builder: CaptureRequest.Builder) {
        applyMetering(builder)
        // 目标帧率：约束 AE 的帧率区间，曝光时间就不会超过 1/fps
        fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        when (mode) {
            Mode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    if (aePrecapture) CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    else CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                // 预拍位挂太久有的 HAL 会一直卡在 PRECAPTURE，加个超时兜底
                if (aePrecapture) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (aePrecaptureAt == 0L) aePrecaptureAt = now
                    else if (now - aePrecaptureAt > 900L) {
                        aePrecapture = false
                        aePrecaptureAt = 0L
                        Log.i(TAG, "AE 预拍超时，复位")
                    }
                }
                if (controls.hasEv) {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        evIndex.coerceIn(controls.evMin, controls.evMax)
                    )
                }
            }

            Mode.S -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )
                if (!sManualTakeover) {
                    // 默认和 AUTO 完全一样：交给相机 AE 连续调，画面才顺滑不卡
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                    if (controls.hasEv) {
                        builder.set(
                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            evIndex.coerceIn(controls.evMin, controls.evMax)
                        )
                    }
                } else {
                    // AE 给出的速度比用户设定更慢 → 接管：固定快门 + 手动 ISO
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
                    builder.set(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        manualIso.coerceIn(controls.isoMin, controls.isoMax)
                    )
                }
            }

            Mode.M -> {
                // 全手动：快门沿用采到的值，ISO 由拨盘定
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            }
        }

        if (!controls.hasZoom) return
        val z = zoom.coerceIn(1f, controls.maxZoom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            // 老接口没有 ZOOM_RATIO，按倍数裁 SCALER_CROP_REGION
            val full = activeArray ?: return
            val w = (full.width() / z).toInt().coerceAtLeast(2)
            val h = (full.height() / z).toInt().coerceAtLeast(2)
            val cx = full.centerX()
            val cy = full.centerY()
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            )
        }
    }

    /** 切换曝光模式 */
    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        // 进 S/M 档时先把「另一半」对齐到 AE 当前的实际值，否则一进档画面就跳曝
        // （固定 ISO 400 那次就是这样：AE 当时才 ISO 100，直接过曝两档）
        when (m) {
            Mode.S -> {
                if (sampledShutterNs in controls.shutterMinNs..controls.shutterMaxNs) {
                    manualShutterNs = sampledShutterNs
                }
                if (sampledIso in controls.isoMin..controls.isoMax) {
                    sModeIso = sampledIso
                }
            }
            Mode.M -> if (sampledIso in controls.isoMin..controls.isoMax) {
                manualIso = sampledIso
            }
            Mode.AUTO -> Unit
        }
        // 从手动档切回自动：发一次 AE 预拍，让它快速收敛（否则会从手动曝光的
        // 那个点慢慢爬，体感要等好几秒）
        aeOffsetStops = 0f
        lastAeOffsetApplied = Float.NaN
        if (m == Mode.AUTO) {
            aePrecapture = true
            aePrecaptureAt = android.os.SystemClock.uptimeMillis()
        }
        reapply(immediate = true)
    }

    /** S 档拨快门 */
    fun setShutterNs(ns: Long) {
        val v = ns.coerceIn(controls.shutterMinNs, controls.shutterMaxNs)
        if (Math.abs(v - manualShutterNs) < 1000) return
        manualShutterNs = v
        if (mode == Mode.S || mode == Mode.M) reapply()
    }

    /** S 档软件自动 ISO 写入（由亮度收敛循环调用） */
    fun setSModeIso(v: Int) {
        val i = v.coerceIn(controls.isoMin, controls.isoMax)
        if (i == sModeIso) return
        sModeIso = i
        if (mode == Mode.S) reapply()
    }

    /** M 档拨 ISO */
    fun setIso(v: Int) {
        val i = v.coerceIn(controls.isoMin, controls.isoMax)
        if (i == manualIso) return
        manualIso = i
        if (mode == Mode.M) reapply()
    }

    /** 拖动变焦滑条时调用 */
    /**
     * 变焦：目标值先记下来，再**分小步**追过去（每步最多 1.25×，间隔 180ms）。
     * 一次跨好几个档会让 HAL 直接跳（还可能顺手换镜头），画面就"瞬间放大缩小"。
     * 真正生效的倍数不靠猜，而是从 CaptureResult 里读回来告诉 UI。
     */
    /**
     * 变焦：拨轮只给目标值，真正的 zoom 由 30Hz 的插值线程一点点推过去。
     *
     * 直接把目标值甩给相机会有两个问题：
     *  1. 拨轮回调是像素级的，忽快忽慢，相机的 zoom 就跟着一顿一顿；
     *  2. 每次 `setRepeatingRequest` 到生效有几帧延迟，步子大了就成了跳变。
     * 现在每 33ms 只走剩余距离的 28%（约 150ms 收敛），并且这一步**不走 60ms 合并**，
     * 保证相机拿到的是细密的小步。
     */
    fun setZoom(ratio: Float) {
        targetZoom = ratio.coerceIn(1f, controls.maxZoom)
        if (!zoomTicking) {
            zoomTicking = true
            handler?.post(zoomTicker)
        }
    }

    private var targetZoom = 1f
    private var zoomTicking = false

    private val zoomTicker = object : Runnable {
        override fun run() {
            val d = targetZoom - zoom
            if (Math.abs(d) < 0.004f) {
                zoom = targetZoom
                zoomTicking = false
                reapply(immediate = true)   // 收尾，确保最终值落地
                return
            }
            zoom = (zoom + d * 0.28f).coerceIn(1f, controls.maxZoom)
            reapply(immediate = true)       // 小步直发，不走合并
            handler?.postDelayed(this, 33)
        }
    }

    /** 相机实际生效的变焦（含 HAL 内部换镜头造成的跳变） */
    @Volatile var appliedZoom = 1f
        private set
    var onAppliedZoom: ((Float) -> Unit)? = null

    /** 拖动曝光滑条时调用（单位是 evStep 档） */
    fun setEv(index: Int) {
        val i = index.coerceIn(controls.evMin, controls.evMax)
        if (i == evIndex) return
        evIndex = i
        reapply()
    }

    private var reapplyPending = false
    private val reapplyTask = Runnable {
        reapplyPending = false
        doReapply()
    }

    /**
     * 用当前参数重新下发预览请求。
     *
     * 必须合并：拖波轮时回调是每个像素级的，直接每次都 setRepeatingRequest 会把相机
     * 管线灌满，画面就开始一顿一顿（S 档调参数卡顿的主因）。
     */
    private fun reapply(immediate: Boolean = false) {
        val h = handler ?: return
        if (immediate) {
            h.removeCallbacks(reapplyTask)
            reapplyPending = false
            doReapply()
            return
        }
        if (reapplyPending) return
        reapplyPending = true
        h.postDelayed(reapplyTask, 60)
    }

    private fun doReapply() {
        val cam = device ?: return
        val s = session ?: return
        val target = previewSurface ?: return
        try {
            s.setRepeatingRequest(previewRequest(cam, target), aeSampler, handler)
        } catch (e: Exception) {
            Log.w(TAG, "更新请求失败: ${e.message}")
        }
    }

    /** 拍一张，结果从 [onStill] 出来 */
    fun capture() {
        val cam = device ?: return
        val s = session ?: return
        val readerSurface = reader?.surface ?: return
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                applyFocus(this)
                applyZoomAndEv(this)
            }.build()
            s.capture(req, null, handler)
        } catch (e: Exception) {
            onError?.invoke("拍照失败: ${e.message}")
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** 录制流：优先 4K，其次 1080p，再次 720p；16:9 优先 */
    private fun pickRecordSize(map: StreamConfigurationMap, hasTexture: Boolean): Size? {
        if (!hasTexture) return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val wanted = when (recordPreference) {
            2 -> listOf(1280 to 720, 1920 to 1080)
            1 -> listOf(1920 to 1080, 1280 to 720)
            else -> listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
        }
        for ((w, h) in wanted) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        // 没有标准尺寸就取不超过 4K 的最大 16:9
        return sizes
            .filter { it.width.toLong() * it.height <= 3840L * 2160L }
            .filter { Math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.08 }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    private fun pickSize(sizes: List<Size>, maxPixels: Int, targetAspect: Double): Size? {
        if (sizes.isEmpty()) return null
        return sizes
            .filter { it.width.toLong() * it.height <= maxPixels }
            .filter { abs(it.width.toDouble() / it.height - targetAspect) < 0.06 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    companion object {
        private const val TAG = "arccam"
    }
}