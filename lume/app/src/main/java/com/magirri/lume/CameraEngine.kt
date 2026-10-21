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
    ) {
        val hasZoom: Boolean get() = maxZoom > 1.01f
        val hasEv: Boolean get() = evMax > evMin
        val hasManual: Boolean get() = isoMax > isoMin
    }

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
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                lastIso = it
                sampledIso = it
            }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                lastShutterNs = it
                sampledShutterNs = it
            }
        }
    }

    /** 拿到一张静止帧（处理完必须 close） */
    var onStill: ((Image) -> Unit)? = null

    var onPreviewSize: ((Size) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun open(texture: SurfaceTexture, recordTexture: SurfaceTexture? = null): Config? {
        if (device != null) return null   // 已经在跑了，别重复打开
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { cid ->
            manager.getCameraCharacteristics(cid)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onError?.invoke("没有可用相机")
            return null
        }
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
        )
        Log.i(
            TAG,
            "控制能力 zoom=${controls.maxZoom} ev=${controls.evMin}..${controls.evMax} " +
                    "iso=${controls.isoMin}..${controls.isoMax} " +
                    "shutter=${controls.shutterMinNs}..${controls.shutterMaxNs}ns"
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
            set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            applyZoomAndEv(this)
        }.build()

    /** 把变焦 / 曝光（含 S/M 手动档）写进任意请求 */
    private fun applyZoomAndEv(builder: CaptureRequest.Builder) {
        when (mode) {
            Mode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (controls.hasEv) {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        evIndex.coerceIn(controls.evMin, controls.evMax)
                    )
                }
            }

            Mode.S -> {
                // 快门优先：快门由拨盘定，ISO 用进档时从 AE 采到的值
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
                // ISO 由软件按 EV 目标算（MainActivity 的收敛循环写 sModeIso）
                builder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    sModeIso.coerceIn(controls.isoMin, controls.isoMax)
                )
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
        reapply()
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
    fun setZoom(ratio: Float) {
        val z = ratio.coerceIn(1f, controls.maxZoom)
        if (Math.abs(z - zoom) < 0.002f) return
        zoom = z
        reapply()
    }

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
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
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
        val wanted = listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
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