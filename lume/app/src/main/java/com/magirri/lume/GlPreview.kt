package com.magirri.lume

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 相机预览 + 实时 LUT。
 *
 * 相机把帧写进 [cameraTexture]（SurfaceTexture），这里每来一帧就画一次全屏四边形，
 * 片元里用 2D 平铺的 LUT 做三线性采样。2D 平铺而不是 GL_TEXTURE_3D：
 * GLES2 上 3D 纹理不保证可用，平铺是所有设备都能跑的写法。
 */
class GlPreview(
    context: Context,
    private val lut: Lut3D,
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    /** 相机输出用的 SurfaceTexture；GL 上下文就绪后才有值 */
    var cameraTexture: SurfaceTexture? = null
        private set

    var onCameraSurfaceReady: ((preview: SurfaceTexture, record: SurfaceTexture) -> Unit)? = null

    /** 改过分辨率/帧率后重启相机：把现有的 surface 再交一次，不用重建 GL 纹理 */
    fun reemitSurfaces() {
        val st = cameraTexture ?: return
        val rec = recordTexture ?: return
        post { onCameraSurfaceReady?.invoke(st, rec) }
    }

    // ---- 给点击对焦用的几何量（和着色器里那套一致） ----
    val quadScaleX: Float get() = scaleX
    val quadScaleY: Float get() = scaleY
    val quadOffsetX: Float get() = if (rightAlign) (1f - scaleX) else 0f
    val frameCropX: Float get() = cropX
    val frameCropY: Float get() = cropY
    val currentZoom: Float get() = zoomNow

    /** 传感器方向（预览按它旋转）；改了必须重算等比缩放，否则还是按旧角度出的条 */
    var rotationDeg = 90
        set(v) {
            field = v
            computeScale()
        }

    /** 画面靠右贴面板 */

    var rightAlign = true

    /**
     * 变焦全部在 GL 里做：只改采样 UV 的范围，不碰相机。
     * 之前每次拨动都 setRepeatingRequest 改裁剪区域，管线不停重新收敛，
     * 画面就是一顿一顿的。targetZoom 由 UI 设，zoomNow 每帧向它靠近。
     */
    @Volatile var targetZoom = 1f
    private var zoomNow = 1f

    /**
     * 相机端已经做掉的变焦倍数（含换镜头）。
     * 真正的总变焦 = 相机变焦 × GL 变焦，所以这里要把相机那部分除掉，
     * 否则相机的 zoom 一变，画面就跟着跳。
     */
    @Volatile var cameraZoom = 1f



    /** 设置画幅裁切：cropY = 目标画幅 / 传感器画幅（1 = 不裁） */

    fun setFrameCrop(cx: Float, cy: Float) {

        cropX = cx

        cropY = cy

        requestRender()

    }



    /** 相机画面的宽高比（宽/高），用于居中裁剪 */
    var cameraAspect = 4f / 3f
        set(v) {
            field = v
            computeScale()
            requestRender()
        }

    private var texId = 0
    /** 4K 录制流的纹理（编码器那一遍从它取画面） */
    private var recTexId = 0
    var recordTexture: SurfaceTexture? = null
        private set
    /** 相机是否真的在喂 4K 流 */
    @Volatile var recordStreamActive = false
    private var lutTexId = 0
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uLut = 0
    private var uLutSize = 0
    private var uRotation = 0
    private var uTexScale = 0
    private var uQuadScale = 0

    private var uQuadOffset = 0
    private var uStrength = 0
    private var uExposure = 0
    private var uZebra = 0
    private var uPeaking = 0
    private var uTexel = 0

    /** 辅助显示开关（由 UI 设置） */
    /** 显示端曝光补偿：传感器 ISO 跳变时用来把画面亮度接上，再缓动回 1.0 */
    @Volatile var exposureComp = 1f

    @Volatile var zebraOn = false
    @Volatile var peakingOn = false

    /** 0..1：方便对比"未调色 / 调色" */
    var strength = 1f
        set(v) {
            field = v
            requestRender()
        }

    /** GL 只吃 native order 的 direct buffer，heap 的 FloatBuffer 会抛
     *  "Must use a native order direct Buffer" */
    private fun directFloats(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(v)
                position(0)
            }

    private val quad = directFloats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

    private val uv = directFloats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    private val frameListener = SurfaceTexture.OnFrameAvailableListener {
        requestRender()
    }

    init {
        setEGLContextClientVersion(2)
        // 关键：MediaCodec 的输入 Surface 只认带 EGL_RECORDABLE_ANDROID 的 config。
        // 用默认 config 时 eglCreateWindowSurface 会返回 EGL_NO_SURFACE（不抛异常），
        // 结果就是一帧都进不了编码器 →"编码器没有输出有效帧"。
        setEGLConfigChooser(RecordableConfigChooser())
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = false
    }

    /** 选一个既能上屏、又能被 MediaCodec 录制的 RGBA8888 config */
    private class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay,
        ): javax.microedition.khronos.egl.EGLConfig {
            val base = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            )
            val withRecordable = base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE)
            val plain = base + intArrayOf(EGL10.EGL_NONE)
            for (attribs in listOf(withRecordable, plain)) {
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) &&
                    num[0] > 0 && configs[0] != null
                ) {
                    return configs[0]!!
                }
            }
            throw IllegalStateException("找不到可用的 EGL config")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)

        createMeterTarget()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")
        uRotation = GLES20.glGetUniformLocation(program, "uRotation")
        uTexScale = GLES20.glGetUniformLocation(program, "uTexScale")
        uQuadScale = GLES20.glGetUniformLocation(program, "uQuadScale")

        uQuadOffset = GLES20.glGetUniformLocation(program, "uQuadOffset")
        uStrength = GLES20.glGetUniformLocation(program, "uStrength")
        uExposure = GLES20.glGetUniformLocation(program, "uExposure")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uPeaking = GLES20.glGetUniformLocation(program, "uPeaking")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")

        // 外部纹理（相机）
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // LUT：2D 平铺，宽 n*n、高 n
        val lutIds = IntArray(1)
        GLES20.glGenTextures(1, lutIds, 0)
        lutTexId = lutIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        val n = lut.size
        val buf = lut.toTiled2dByte()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )

        // 第二张纹理：给 4K 录制流用（编码器那一遍从它取画面）
        val recIds = IntArray(1)
        GLES20.glGenTextures(1, recIds, 0)
        recTexId = recIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, recTexId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        val recSt = SurfaceTexture(recTexId)
        recSt.setOnFrameAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        recordTexture = recSt

        val st = SurfaceTexture(texId)
        // 必须显式给 Handler：SurfaceTexture 默认投递到创建它的线程，而 GL 线程没有 Looper，
        // 回调会永远不触发（预览只画一帧就黑屏的元凶）
        st.setOnFrameAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        cameraTexture = st
        post { onCameraSurfaceReady?.invoke(st, recSt) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        computeScale()
    }

    private var viewWidth = 1
    private var viewHeight = 1

    /**
     * 等比缩放四边形，整幅画面都看得见（四周补黑边）。
     *
     * 注意是缩**顶点**、不是缩 UV：缩 UV 会改变采样范围，画面会被拉伸。
     * 目标：四边形像素尺寸的比例 = 画面比例。
     */
    private fun computeScale() {
        if (viewWidth <= 1 || viewHeight <= 1) return
        val viewAspect = viewWidth.toFloat() / viewHeight
        val sideways = rotationDeg == 90 || rotationDeg == 270
        val contentAspect = if (sideways) 1f / cameraAspect else cameraAspect
        if (contentAspect > viewAspect) {
            // 画面比视口宽 → 宽度顶满，上下留黑边
            scaleX = 1f
            scaleY = viewAspect / contentAspect
        } else {
            // 画面比视口高 → 高度顶满，左右留黑边
            scaleX = contentAspect / viewAspect
            scaleY = 1f
        }
        requestRender()
    }

    private var scaleX = 1f
    private var scaleY = 1f

    /** 画幅裁切（纹理坐标）：1 = 不裁 */
    private var cropX = 1f
    private var cropY = 1f

    // ---- 录像目标（编码器输入 Surface） ----
    private var encEglSurface: android.opengl.EGLSurface? = null
    private var encWidth = 0
    private var encHeight = 0
    private var encRotation = 0f
    @Volatile private var encSurfaceWanted: Surface? = null
    /** 时间戳基准：SurfaceTexture 给的是 nanoTime 绝对值，必须减去起点，否则 muxer 时长会爆炸 */
    private var encBaseNs = 0L
    /** 编码帧间隔（30fps 留一点余量） */
    private var encMinIntervalNs = 33_000_000L
    private var encLastNs = 0L
    private var encSkipped = 0
    // ---- 性能统计（每秒清零一次） ----
    private var statFrames = 0
    private var statDrawNs = 0L
    private var statEncNs = 0L
    private var statEncFrames = 0
    private var statStart = System.nanoTime()

    /** 取一次读数并清零：返回 (帧数, 平均绘制ms, 平均编码swap ms) */
    fun takeStats(): Triple<Int, Float, Float> {
        val now = System.nanoTime()
        val dt = (now - statStart).coerceAtLeast(1L)
        val f = statFrames
        val d = if (f > 0) statDrawNs / 1_000_000f / f else 0f
        val e = if (statEncFrames > 0) statEncNs / 1_000_000f / statEncFrames else 0f
        statFrames = 0; statDrawNs = 0; statEncNs = 0; statEncFrames = 0; statStart = now
        return Triple((f * 1_000_000_000L / dt).toInt(), d, e)
    }

    /** 渲染器型号（判断是不是掉到软件渲染） */
    fun rendererName(): String = android.opengl.GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"

    /** 最近一次 swap 的耗时，用来判断编码器是不是在拖后腿 */
    /** 录像 EGL 初始化失败原因，给 UI 显示 */
    var lastEglError: String? = null
        private set
    var encSwapNs = 0L
        private set

    /**
     * 指定录像输出目标。传 null 表示停止。
     *
     * EGL 表面必须在 GL 线程上创建，所以这里 queueEvent 过去。
     */
    fun setEncoderTarget(surface: Surface?, width: Int, height: Int, rotationDeg: Int) {
        encSurfaceWanted = surface
        queueEvent {
            releaseEncoderEgl()
            val target = encSurfaceWanted ?: return@queueEvent
            encWidth = width
            encHeight = height
            encRotation = rotationDeg.toFloat()
            encBaseNs = System.nanoTime()
            try {
                val d = EGL14.eglGetCurrentDisplay()
                val c = EGL14.eglGetCurrentContext()
                // EGL 没有"直接取当前 config"的接口：先问出 config id，再在 config 列表里找回对象。
                // 必须复用同一个 config——context 与 window surface 的 config 不一致时，部分驱动会直接失败。
                val idArr = IntArray(1)
                EGL14.eglQueryContext(d, c, EGL14.EGL_CONFIG_ID, idArr, 0)
                val count = IntArray(1)
                EGL14.eglGetConfigs(d, null, 0, 0, count, 0)
                val all = arrayOfNulls<android.opengl.EGLConfig>(count[0])
                EGL14.eglGetConfigs(d, all, 0, count[0], count, 0)
                var chosen: android.opengl.EGLConfig? = null
                val v = IntArray(1)
                for (cfg in all) {
                    if (cfg == null) continue
                    EGL14.eglGetConfigAttrib(d, cfg, EGL14.EGL_CONFIG_ID, v, 0)
                    if (v[0] == idArr[0]) {
                        chosen = cfg
                        break
                    }
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                val s = EGL14.eglCreateWindowSurface(d, chosen, target, attrs, 0)
                if (s == null || s == EGL14.EGL_NO_SURFACE) {
                    // 返回值必须查：失败时 EGL 只返回 EGL_NO_SURFACE，不会抛异常
                    val err = EGL14.eglGetError()
                    lastEglError = "无法创建编码器 EGL 表面（err=0x${Integer.toHexString(err)}）"
                    android.util.Log.e("arccam", lastEglError!!)
                    encEglSurface = null
                } else {
                    encEglSurface = s
                    encLastNs = 0L
                    encSkipped = 0
                    encMinIntervalNs = 33_000_000L
                }
            } catch (e: Exception) {
                lastEglError = "创建编码器 EGL 表面失败: ${e.message}"
                android.util.Log.e("arccam", lastEglError!!)
                encEglSurface = null
            }
        }
        requestRender()
    }

    private fun releaseEncoderEgl() {
        val s = encEglSurface ?: return
        try {
            EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), s)
        } catch (_: Exception) {
        }
        encEglSurface = null
    }

    /**
     * 停止往编码器写，并**等 GL 线程真的停下来**。
     *
     * 不等的话，编码器 stop() 之后 GL 线程可能还在 swap 那一帧，muxer 收到的是废数据，
     * 表现就是"录完了保存失败"。
     */
    fun stopEncoderAndWait(timeoutMs: Long = 1500): Boolean {
        encSurfaceWanted = null
        val latch = java.util.concurrent.CountDownLatch(1)
        queueEvent {
            releaseEncoderEgl()
            encWidth = 0
            encHeight = 0
            try {
                latch.countDown()
            } catch (_: Exception) {
            }
        }
        return try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = cameraTexture ?: return
        // 变焦插值：每帧向目标靠 28%，拨动再快也是滑过去的
        if (zoomNow != targetZoom) {
            val diff = targetZoom - zoomNow
            zoomNow = if (Math.abs(diff) < 0.002f) targetZoom else zoomNow + diff * 0.28f
        }
        val tStart = System.nanoTime()
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // 1) 画到屏幕
        drawFrame(-rotationDeg * Math.PI.toFloat() / 180f, scaleX, scaleY, viewWidth, viewHeight)
        framesDrawn++
        statFrames++
        statDrawNs += System.nanoTime() - tStart

        // 有人要亮度就顺手读一小块，必须在这里读：swap 之后后备缓冲内容就不保证有效了
        if (lumaWanted) {
            lumaWanted = false
            lastLuma = measureLuma()
        }

        // 2) 需要录像时，把同一帧再画进编码器的 Surface
        val es = encEglSurface
        if (es != null && encWidth > 0 && encHeight > 0) {
            // 相机预览可能是 60fps，而编码只想要 30fps：多余的帧直接跳过，
            // 否则每帧画两遍会把 GL 线程压满，预览先卡。
            val now = st.timestamp
            if (encLastNs == 0L || now - encLastNs >= encMinIntervalNs) {
                val d = EGL14.eglGetCurrentDisplay()
                val c = EGL14.eglGetCurrentContext()
                val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(d, es, es, c)) {
                    drawFrame(
                        -encRotation * Math.PI.toFloat() / 180f, 1f, 1f, encWidth, encHeight,
                        if (recordStreamActive) recTexId else texId,
                        assist = false,       // 辅助图形不能进成片
                    )
                    // pts 必须和 encBaseNs 同一个时钟域。相机给的 st.timestamp 是
                    // CLOCK_BOOTTIME（含休眠），System.nanoTime() 是 CLOCK_MONOTONIC——
                    // 手机睡过一晚两者能差几十小时，pts 直接变成天文数字，成片时长就成了
                    // 几千分钟。改用 nanoTime 差值：既同域，也天然按真实时间走（丢帧也不变速）。
                    val pts = (System.nanoTime() - encBaseNs).coerceAtLeast(0L)
                    EGLExt.eglPresentationTimeANDROID(d, es, pts)
                    val t0 = System.nanoTime()
                    // 编码器队列满时 swap 会阻塞，这里只能被动等；所以码率/分辨率要留余量
                    EGL14.eglSwapBuffers(d, es)
                    encSwapNs = System.nanoTime() - t0
                    statEncNs += encSwapNs
                    statEncFrames++
                    EGL14.eglMakeCurrent(d, prevDraw, prevRead, c)
                    encLastNs = now
                    // swap 慢说明编码器在积压：自动降编码帧率，先保住取景器
                    if (encSwapNs > 15_000_000L) {
                        encMinIntervalNs = (encMinIntervalNs * 2).coerceAtMost(100_000_000L)
                    } else if (encSwapNs < 6_000_000L && encMinIntervalNs > 33_000_000L) {
                        encMinIntervalNs = (encMinIntervalNs - 10_000_000L).coerceAtLeast(33_000_000L)
                    }
                } else {
                    encSkipped++
                }
            }
        }
    }

    // ---- 取景亮度：给 S 档的软件自动 ISO 用 ----
    @Volatile private var lumaWanted = false

    /** 16 档亮度直方图（0..1），每次测光顺带算一份 */
    @Volatile var histogram: FloatArray = FloatArray(16)
        private set
    @Volatile var lastLuma = -1f

    /** 点测光采样位置：**视图归一化坐标**（0..1，左上原点），默认中心 */
    @Volatile private var meterU = 0.5f
    @Volatile private var meterV = 0.5f

    fun setMeterPoint(u: Float, v: Float) {
        meterU = u.coerceIn(0f, 1f)
        meterV = v.coerceIn(0f, 1f)
    }

    /** 已经真正画出过多少帧：MainActivity 靠它决定什么时候撤掉启动 logo */
    @Volatile var framesDrawn = 0L
        private set

    /** 请求下一帧顺带读一次亮度（便宜：只读中心 16x16） */
    fun requestLuma() {
        // 只打个标记，等下一帧正常渲染时顺手读：
        // 这里再 requestRender() 会多渲染一帧，S 档闭环每秒三次的话就是可见的卡顿
        lumaWanted = true
    }

    /** 测光模式：0 = 点测，1 = 中央重点，2 = 多区（参照电影机曝光表的测光区域） */
    @Volatile var meterMode = 1

    /** 测光用的小 FBO：把整幅画面渲到 64x48，再按区域加权重算亮度 */
    private var meterFbo = 0
    private var meterTex = 0
    private val MW = 64
    private val MH = 48

    private fun createMeterTarget() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        meterTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meterTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, MW, MH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        meterFbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meterFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, meterTex, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** 把当前帧渲进小 FBO 并按测光模式取样 */
    private fun measureLuma(): Float {
        if (meterFbo == 0) return -1f
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meterFbo)
        drawFrame(-rotationDeg * Math.PI.toFloat() / 180f, 1f, 1f, MW, MH, assist = false)
        val buf = java.nio.ByteBuffer.allocateDirect(MW * MH * 4)
        GLES20.glReadPixels(0, 0, MW, MH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 顺手统计直方图（3072 个样本，够画个概览）
        val bins = FloatArray(16)
        for (i in 0 until MW * MH) {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
            bins[(l * 15f).toInt().coerceIn(0, 15)] += 1f
        }
        var maxBin = 1f
        for (v in bins) if (v > maxBin) maxBin = v
        for (i in bins.indices) bins[i] /= maxBin
        histogram = bins

        fun lumaAt(i: Int): Float {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            return (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
        }

        // 点测模式：采样点跟着手指（meterU/meterV 是纹理坐标，注意读回的图像是上下翻转的）
        val cx = if (meterMode == 0) (meterU * MW).coerceIn(2f, MW - 3f) else MW / 2f
        val cy = if (meterMode == 0) ((1f - meterV) * MH).coerceIn(2f, MH - 3f) else MH / 2f
        return when (meterMode) {
            // 点测：只取一小块（约画幅的 6%），就是点测的含义
            0 -> {
                var sum = 0f
                var n = 0
                for (yy in (cy.toInt() - 2)..(cy.toInt() + 1)) {
                    for (xx in (cx.toInt() - 2)..(cx.toInt() + 1)) {
                        if (xx in 0 until MW && yy in 0 until MH) {
                            sum += lumaAt(yy * MW + xx)
                            n++
                        }
                    }
                }
                if (n > 0) sum / n else -1f
            }

            // 中央重点：高斯权重，越靠中心权重越高
            1 -> {
                var sum = 0f
                var wsum = 0f
                for (yy in 0 until MH) {
                    for (xx in 0 until MW) {
                        val dx = (xx - cx) / cx
                        val dy = (yy - cy) / cy
                        val w = Math.exp(-(dx * dx + dy * dy) / (2 * 0.42 * 0.42)).toFloat()
                        sum += lumaAt(yy * MW + xx) * w
                        wsum += w
                    }
                }
                if (wsum > 0f) sum / wsum else -1f
            }

            // 多区：全画面平均
            else -> {
                var sum = 0f
                for (i in 0 until MW * MH) sum += lumaAt(i)
                sum / (MW * MH)
            }
        }
    }

    /** 一次完整的绘制：旋转 + 顶点缩放 + LUT 采样 */
    /**
     * @param assist 是否叠加辅助图形（斑马线 / 对焦峰值）。录视频和测光都必须传 false，
     * 否则红线会被烘进成片、也会污染直方图。
     */
    private fun drawFrame(
        rotationRad: Float, qx: Float, qy: Float, vw: Int, vh: Int,
        sourceTex: Int = -1,
        assist: Boolean = true,
    ) {
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glUniform1i(uLut, 1)
        GLES20.glUniform1f(uLutSize, lut.size.toFloat())

        // 旋转 UV 等于画面反向旋转，所以传负角度，画面才是顺时针转
        GLES20.glUniform1f(uRotation, rotationRad)
        GLES20.glUniform1f(uStrength, strength)
        GLES20.glUniform1f(uExposure, exposureComp)
        GLES20.glUniform1i(uZebra, if (assist && zebraOn) 1 else 0)
        GLES20.glUniform1i(uPeaking, if (assist && peakingOn) 1 else 0)
        GLES20.glUniform2f(uTexel, 1f / 1280f, 1f / 720f)
        // 变焦只由相机做（CONTROL_ZOOM_RATIO）。两级变焦不可能同帧生效，
        // 每次相机跳一下 GL 就要补一下，画面就是一闪一闪的。这里只做画幅裁切。
        GLES20.glUniform2f(uTexScale, cropX, cropY)
        GLES20.glUniform2f(uQuadScale, qx, qy)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        uv.position(0)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("链接着色器失败: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("编译着色器失败: $log")
        }
        return s
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform float uRotation;
            uniform vec2 uTexScale;
            uniform vec2 uQuadScale;

            uniform vec2 uQuadOffset;
            varying vec2 vUv;
            void main() {
                // 旋转 UV（相机传感器方向），再按视口做居中裁剪
                float c = cos(uRotation);
                float s = sin(uRotation);
                vec2 p = aUv - 0.5;
                vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
                vUv = (r * uTexScale) + 0.5;
                // 顶点整体缩放：整幅画面可见，四周黑边
                gl_Position = vec4(aPos * uQuadScale + uQuadOffset, 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTex;
            uniform sampler2D uLut;
            uniform float uLutSize;
            uniform float uStrength;
            uniform float uExposure;
            uniform int uZebra;
            uniform int uPeaking;
            uniform vec2 uTexel;
            varying vec2 vUv;

            float lumOf(vec2 uv) {
                vec3 c = texture2D(uTex, uv).rgb;
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec3 raw = texture2D(uTex, vUv).rgb;

                // 2D 平铺 LUT：宽 n*n、高 n；r,g 交给硬件线性，b 取两片自己插。
                //
                // 三个通道都必须先夹到 [0,1]。过曝的地方相机给的是「超白」
                // （YUV 255 → 展开后 r/g 可能到 1.1），如果不夹，u 坐标会越出当前
                // 这片 b 切片、串到下一片的 r=0 那一列，红通道瞬间掉到 0.4 ——
                // 表现就是「取景器里过曝的地方发蓝」，而照片走 CPU 路径夹了所以没事。
                float n = uLutSize;
                vec3 src = clamp(raw, 0.0, 1.0);
                float bs = src.b * (n - 1.0);
                float b0 = floor(bs);
                float b1 = min(b0 + 1.0, n - 1.0);
                float fbv = bs - b0;
                vec2 t0 = vec2((src.r * (n - 1.0) + b0 * n + 0.5) / (n * n),
                               (src.g * (n - 1.0) + 0.5) / n);
                vec2 t1 = vec2((src.r * (n - 1.0) + b1 * n + 0.5) / (n * n),
                               t0.y);
                vec3 c0 = texture2D(uLut, t0).rgb;
                vec3 c1 = texture2D(uLut, t1).rgb;
                vec3 graded = mix(c0, c1, fbv);

                vec3 col = mix(src, graded, uStrength);

                // 对焦峰值：四邻域亮度差够大就是合焦边缘，染红
                if (uPeaking == 1) {
                    float l = dot(col, vec3(0.299, 0.587, 0.114));
                    float lx = lumOf(vUv + vec2(uTexel.x * 2.0, 0.0));
                    float rx = lumOf(vUv - vec2(uTexel.x * 2.0, 0.0));
                    float uy = lumOf(vUv + vec2(0.0, uTexel.y * 2.0));
                    float dy = lumOf(vUv - vec2(0.0, uTexel.y * 2.0));
                    // 用「两侧之和」判局部极值：缓变/整体偏亮的区域两侧差会互相抵消，
                    // 只有真正的边缘才两侧都差。
                    float eh = abs(l - lx) + abs(l - rx);
                    float ev = abs(l - uy) + abs(l - dy);
                    if (max(eh, ev) > 0.28) col = mix(col, vec3(1.0, 0.1, 0.1), 0.8);
                }

                // 斑马线：过曝区叠斜纹
                if (uZebra == 1) {
                    float l2 = dot(col, vec3(0.299, 0.587, 0.114));
                    if (l2 > 0.86) {
                        float st = step(0.5, fract((vUv.x * 1.6 + vUv.y) * 90.0));
                        col = mix(col, vec3(0.0, 0.0, 0.0), st * 0.6);
                    }
                }

                // 曝光过渡：传感器 ISO 一步跳变时，这里从"抵消值"缓动回 1.0，
                // 画面亮度就是连续的
                col *= uExposure;
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}