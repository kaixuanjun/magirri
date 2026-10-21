package com.magirri.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "magirri"

/**
 * 把解码器的输出画面套上 LUT，画进编码器的输入 Surface。
 *
 * 只做一件事：一个全屏 quad + 一次 3D LUT 查表。
 * 用硬件三线性（GL_TEXTURE_3D + LINEAR）而不是四面体插值——手机上
 * 8 次 texelFetch 在 4K 下不划算，而三线性与四面体的差距实测只有 ~0.1 ΔE。
 */
class GlRenderer(
    private val lut: Lut3D,
    private val debugMode: Int = 0,
    /** true = 离屏渲染到 FBO，再用 glReadPixels 取回（缓冲模式用） */
    private val offscreen: Boolean = false,
    private val outWidth: Int = 0,
    private val outHeight: Int = 0,
    /** 旋转策略：0=完全信 SurfaceTexture 的矩阵，1=再叠 +rot，2=再叠 -rot */
    private val rotMode: Int = 2,
    /** 源是 HLG/BT.2020 时，先转成 SDR 709 再套 LUT，否则导出会发灰 */
    private val hlgToSdr: Boolean = false,
) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var fbo = 0
    private var fboTex = 0
    private var readBuf: ByteBuffer? = null

    private var program = 0
    private var aPosLoc = 0
    private var uPosMatrixLoc = 0
    private var uTexMatrixLoc = 0
    private var uTexLoc = 0
    private var uLutLoc = 0
    private var uLutSizeLoc = 0
    private var uHlgLoc = 0
    private var vertexBuf: FloatBuffer? = null

    private var externalTexId = 0
    private var lutTexId = 0
    private var lutSize = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    private var decoderSurface: Surface? = null

    private val texMatrix = FloatArray(16)
    private val uvMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)

    /** 顶点位置不参与旋转——旋转在采样矩阵里做 */
    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private val vertexShader = """
        #version 300 es
        uniform mat4 uPosMatrix;
        in vec2 aPos;
        out vec2 vUv;
        void main() {
            gl_Position = uPosMatrix * vec4(aPos, 0.0, 1.0);
            vUv = aPos * 0.5 + 0.5;
        }
    """.trimIndent()

    private val fragmentShader = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform highp samplerExternalOES uTex;
        // LUT 用 2D 平铺纹理（宽 n*n，高 n）：3D 纹理在模拟器 SwiftShader 上采样恒为 0
        uniform highp sampler2D uLut;
        uniform highp float uLutSize;
        uniform float uHlg;
        uniform mat4 uTexMatrix;

        in vec2 vUv;
        // 显式指定 location：不写的话个别驱动把唯一输出绑到别的 attachment，
        // FBO 就会读到全黑（这个坑在 SwiftShader 上踩过一次）
        layout(location = 0) out vec4 fragColor;

        // 8x8 Bayer：输出是 8-bit，加 1/2 LSB 的有序抖动可以把
        // "LUT 是 10-bit 精度、编码只有 8-bit" 造成的色带压下去。
        float bayer8(vec2 p) {
            ivec2 i = ivec2(mod(p, 8.0));
            int idx = i.y * 8 + i.x;
            const float m[64] = float[64](
                 0.0, 32.0,  8.0, 40.0,  2.0, 34.0, 10.0, 42.0,
                48.0, 16.0, 56.0, 24.0, 50.0, 18.0, 58.0, 26.0,
                12.0, 44.0,  4.0, 36.0, 14.0, 46.0,  6.0, 38.0,
                60.0, 28.0, 52.0, 20.0, 62.0, 30.0, 54.0, 22.0,
                 3.0, 35.0, 11.0, 43.0,  1.0, 33.0,  9.0, 41.0,
                51.0, 19.0, 59.0, 27.0, 49.0, 17.0, 57.0, 25.0,
                15.0, 47.0,  7.0, 39.0, 13.0, 45.0,  5.0, 37.0,
                63.0, 31.0, 55.0, 23.0, 61.0, 29.0, 53.0, 21.0
            );
            return m[idx] / 64.0 - 0.5;
        }

        void main() {
            vec3 c = texture(uTex, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy).rgb;
            // HLG(BT.2100) 直接当 SDR 用会整体发灰，先反 OETF、加系统 gamma，再回 709
            if (uHlg > 0.5) {
                vec3 e = vec3(
                    c.r <= 0.5 ? c.r * c.r / 3.0 : (exp((c.r - 0.55991073) / 0.17883277) + 0.28466892) / 12.0,
                    c.g <= 0.5 ? c.g * c.g / 3.0 : (exp((c.g - 0.55991073) / 0.17883277) + 0.28466892) / 12.0,
                    c.b <= 0.5 ? c.b * c.b / 3.0 : (exp((c.b - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
                );
                // HLG 参考白 = 0.75（约 203 nit）。按参考白做线性归一，再走 709 OETF。
                // 实测这比"再叠一次 OOTF(gamma 1.2)"更不容易把中间调压暗。
                vec3 lin = max(e, 0.0) / 0.265;
                vec3 lc = mix(lin * 4.5, 1.099 * pow(max(lin, 0.0), vec3(0.45)) - 0.099,
                              step(vec3(0.018), lin));
                c = clamp(lc, 0.0, 1.0);
            }
            // 2D 平铺 LUT 的三线性采样：r、g 交给硬件线性，b 取两片自己插
            float n = uLutSize;
            float bs = clamp(c.b, 0.0, 1.0) * (n - 1.0);
            float b0 = floor(bs);
            float b1 = min(b0 + 1.0, n - 1.0);
            float fbv = bs - b0;
            float gx = clamp(c.g, 0.0, 1.0) * (n - 1.0);
            float rx = clamp(c.r, 0.0, 1.0) * (n - 1.0);
            float yy = (gx + 0.5) / n;
            float xx0 = (b0 * n + rx + 0.5) / (n * n);
            float xx1 = (b1 * n + rx + 0.5) / (n * n);
            vec3 s0 = texture(uLut, vec2(xx0, yy)).rgb;
            vec3 s1 = texture(uLut, vec2(xx1, yy)).rgb;
            vec3 lutC = mix(s0, s1, fbv);
            //__LUT__
            fragColor = vec4(clamp(o, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * 初始化。`outputSurface` 为 null 时走离屏（FBO）模式。
     */
    fun start(outputSurface: Surface?) {
        setupEgl(outputSurface)
        createProgram()
        createQuad()
        createLutTexture()
        createExternalTexture()
    }

    private fun createFbo() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        fboTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            outWidth, outHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, fboTex, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO 不完整: 0x${Integer.toHexString(status)}")
        }
    }

    /** 解码器的目标 Surface（必须在 start 之后调用）。 */
    fun createDecoderSurface(): Surface {
        val texId = externalTexId
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(1, 1) // 尺寸由解码器决定，这里只是占位
        surfaceTexture = st
        val s = Surface(st)
        decoderSurface = s
        return s
    }

    fun onFrame(rotationDeg: Int) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        // 旋转必须作用在**采样坐标**上。转顶点会把非方形画面拉变形（竖屏素材尤其明显）。
        when (rotMode) {
            1 -> buildUvMatrix(rotationDeg)
            2 -> buildUvMatrix(-rotationDeg)
            else -> buildUvMatrix(0)
        }
        if (frameLogCount < 3) {
            Log.i(
                TAG,
                "onFrame rot=" + rotationDeg + " T=[" + texMatrix[0] + "," + texMatrix[1] + "," + texMatrix[4] + "," + texMatrix[5] + " t=" + texMatrix[12] + "," + texMatrix[13] + "]" +
                        " uv=[" + uvMatrix[0] + "," + uvMatrix[1] + "," + uvMatrix[4] + "," +
                        uvMatrix[5] + " tx=" + uvMatrix[12] + "," + uvMatrix[13] + "]"
            )
            frameLogCount++
        }
        draw()
    }
    private fun draw() {
        if (offscreen) {
            // 画到默认 framebuffer（就是整尺寸 pbuffer）
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, outWidth, outHeight)
        } else {
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        // debugMode 3：只清成红色，完全不画三角形。用来分清"画不出来"还是"FBO 读不回来"
        if (debugMode == 3) {
            GLES30.glClearColor(1f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (frameLogCount == 0) {
                frameLogCount++
                Log.i(TAG, "draw: 仅清屏红. fbo=$fbo err=0x${Integer.toHexString(GLES30.glGetError())}")
            }
            return
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uPosMatrixLoc, 1, false, IDENTITY, 0)
        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, uvMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTexId)
        GLES30.glUniform1i(uLutLoc, 1)
        GLES30.glUniform1f(uLutSizeLoc, lut.size.toFloat())
        GLES30.glUniform1f(uHlgLoc, if (hlgToSdr) 1f else 0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexId)
        GLES30.glUniform1i(uTexLoc, 0)

        vertexBuf?.position(0)
        GLES30.glEnableVertexAttribArray(aPosLoc)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 0, vertexBuf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosLoc)
        if (frameLogCount == 0) {
            frameLogCount++
            Log.i(
                TAG,
                "draw: aPosLoc=$aPosLoc program=$program fbo=$fbo " +
                        "viewport=${outWidth}x${outHeight} err=0x${Integer.toHexString(GLES30.glGetError())}"
            )
        }
    }

    private var frameLogCount = 0

    var viewportWidth = 1
        private set
    var viewportHeight = 1
        private set

    /** 提交这一帧并打上时间戳（编码器按 PTS 排帧）。离屏模式不需要 swap。 */
    fun swap(withTimestampNanos: Long) {
        if (offscreen) return
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, withTimestampNanos)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw RuntimeException("eglSwapBuffers 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    /**
     * 离屏模式专用：把刚画好的 FBO 读回来。
     *
     * 相比 ImageReader，glReadPixels 慢一些，但它在任何驱动上都可靠
     * （ImageReader + EGL 在模拟器上读回来是全黑）。GL 原点在左下，
     * 这里翻成左上，调用方按正常行序处理。
     */
    fun readPixels(dst: ByteArray): Boolean {
        if (!offscreen) return false
        val w = outWidth
        val h = outHeight
        val need = w * h * 4
        if (dst.size < need) return false
        val buf = readBuf ?: ByteBuffer.allocateDirect(need)
            .order(java.nio.ByteOrder.nativeOrder()).also { readBuf = it }
        buf.rewind()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return false
        buf.rewind()
        val rowBytes = w * 4
        val tmp = ByteArray(rowBytes)
        for (y in 0 until h) {
            buf.position((h - 1 - y) * rowBytes)
            buf.get(tmp, 0, rowBytes)
            System.arraycopy(tmp, 0, dst, y * rowBytes, rowBytes)
        }
        return true
    }

    fun release() {
        decoderSurface?.release()
        decoderSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (fboTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
            fboTex = 0
        }
        releaseEgl()
    }

    // ---------------------------------------------------------------- EGL

    private fun setupEgl(outputSurface: Surface?) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize 失败" }

        val surfaceType = if (outputSurface == null) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig 失败"
        }
        check(numConfigs[0] > 0) { "没有可用的 EGL 配置" }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }

        if (outputSurface == null) {
            // 离屏画布直接用**整尺寸 pbuffer**（默认 framebuffer 就是它）。
            // 之前试过 1x1 pbuffer + FBO，以及 ImageReader，在模拟器上读回来都是全黑；
            // 全尺寸 pbuffer 是最保守的路径。
            val pb = intArrayOf(
                EGL14.EGL_WIDTH, if (outWidth > 0) outWidth else 1,
                EGL14.EGL_HEIGHT, if (outHeight > 0) outHeight else 1,
                EGL14.EGL_NONE,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pb, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface 失败" }
        } else {
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, outputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败" }
        }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent 失败"
        }
        if (offscreen) {
            viewportWidth = outWidth
            viewportHeight = outHeight
        } else {
            val w = IntArray(1)
            val h = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
            viewportWidth = w[0].coerceAtLeast(1)
            viewportHeight = h[0].coerceAtLeast(1)
        }
        Log.i(
            TAG,
            "GL: ${GLES30.glGetString(GLES30.GL_VENDOR)} | " +
                    "${GLES30.glGetString(GLES30.GL_RENDERER)} | " +
                    "${GLES30.glGetString(GLES30.GL_VERSION)} | offscreen=$offscreen"
        )
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // ---------------------------------------------------------------- GL 资源

    /**
     * 按 debugMode 拼装片段着色器。
     *
     * 不用 `#if`：在函数体里写条件编译，SwiftShader 与部分驱动会直接报
     * "unexpected token after conditional expression"。换字符串最稳。
     */
    private fun shaderSource(): String {
        val body = when (debugMode) {
            1 -> "vec3 o = vec3(1.0, 0.0, 0.0);"
            2 -> "vec3 o = clamp(c, 0.0, 1.0);"
            else -> "vec3 o = lutC;\n" +
                    "            o += bayer8(gl_FragCoord.xy) / 255.0;"
        }
        return fragmentShader.replace("//__LUT__", body)
    }

    private fun createProgram() {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, shaderSource())
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        // 显式绑定属性位置：驱动若分配成 -1，顶点数组全部无效，画不出东西也不报错
        GLES30.glBindAttribLocation(program, 0, "aPos")
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("程序链接失败: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        aPosLoc = GLES30.glGetAttribLocation(program, "aPos")
        uPosMatrixLoc = GLES30.glGetUniformLocation(program, "uPosMatrix")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTexLoc = GLES30.glGetUniformLocation(program, "uTex")
        uLutLoc = GLES30.glGetUniformLocation(program, "uLut")
        uLutSizeLoc = GLES30.glGetUniformLocation(program, "uLutSize")
        uHlgLoc = GLES30.glGetUniformLocation(program, "uHlg")
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val status = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw RuntimeException("着色器编译失败: $log")
        }
        return sh
    }

    private fun createQuad() {
        val buf = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        // 两个三角形拼成的全屏 quad
        buf.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        buf.rewind()
        vertexBuf = buf
    }

    private fun createLutTexture() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        lutTexId = ids[0]
        lutSize = lut.size
        val n = lut.size
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTexId)

        // 2D 平铺：宽 n*n、高 n。先试 RGBA16F，再退 RGBA8。
        // 3D 纹理在模拟器 SwiftShader 上能创建、采样却恒为 0，所以走 2D。
        var uploader = "RGBA16F"
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
            n * n, n, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, lut.toTiled2dFloat()
        )
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            uploader = "RGBA8"
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                n * n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lut.toTiled2dByte()
            )
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) {
                throw RuntimeException("上传 2D LUT 失败: 0x${Integer.toHexString(err)}")
            }
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            throw RuntimeException("2D LUT 参数设置失败: 0x${Integer.toHexString(err)}")
        }
        Log.i(TAG, "2D LUT 上传完成: ${n}³ -> ${n * n}x$n $uploader id=$lutTexId")
    }

    private fun createExternalTexture() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        externalTexId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * 把旋转合进采样矩阵：`uv' = R(θ) · (uv - 0.5) + 0.5`，再叠上 SurfaceTexture 给的变换。
     *
     * 为什么不在顶点上转：整个四边形铺满视口，转它只是把画面在非方形视口里拉斜，
     * 竖屏素材会变成"被拉长的横屏"。转采样坐标则在纹理空间里转，画面比例不会变。
     */
    private fun buildUvMatrix(deg: Int) {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) {
            System.arraycopy(texMatrix, 0, uvMatrix, 0, 16)
            return
        }
        val rad = Math.toRadians(d.toDouble())
        val c = Math.cos(rad).toFloat()
        val s = Math.sin(rad).toFloat()
        val tx = 0.5f - (0.5f * c - 0.5f * s)
        val ty = 0.5f - (0.5f * s + 0.5f * c)
        rotMatrix[0] = c; rotMatrix[1] = s; rotMatrix[2] = 0f; rotMatrix[3] = 0f
        rotMatrix[4] = -s; rotMatrix[5] = c; rotMatrix[6] = 0f; rotMatrix[7] = 0f
        rotMatrix[8] = 0f; rotMatrix[9] = 0f; rotMatrix[10] = 1f; rotMatrix[11] = 0f
        rotMatrix[12] = tx; rotMatrix[13] = ty; rotMatrix[14] = 0f; rotMatrix[15] = 1f
        mul4(uvMatrix, rotMatrix, texMatrix)
    }

    /** 列主序 4x4 乘法：out = a · b */
    private fun mul4(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
    }
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}