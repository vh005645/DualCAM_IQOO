package com.example.dualcam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Nhận 2 luồng camera (sau + trước) qua SurfaceTexture, ghép thành 1 khung hình:
 * camera sau toàn khung, camera trước ở góc trên bên phải (bo góc, viền trắng).
 * Kết quả được vẽ đồng thời lên: (1) màn hình xem trước, (2) bộ mã hóa video.
 * Mọi thao tác OpenGL đều chạy trên 1 luồng riêng.
 */
class GlCompositor {

    private val thread = HandlerThread("GlCompositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewSurf: EGLSurface? = null
    private var previewW = 0
    private var previewH = 0

    private var encSurf: EGLSurface? = null
    private var encW = 0
    private var encH = 0
    private var encStartNs = 0L

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTexMat = 0
    private var uTexture = 0
    private var uMask = 0
    private var uSize = 0
    private var uRadius = 0
    private var uBorder = 0

    private var backTex = 0
    private var frontTex = 0
    @Volatile private var backST: SurfaceTexture? = null
    @Volatile private var frontST: SurfaceTexture? = null

    private val backMat = FloatArray(16)
    private val frontMat = FloatArray(16)
    private val mvp = FloatArray(16)
    private var backHas = false
    private var frontHas = false

    @Volatile private var backW = 1280
    @Volatile private var backH = 720
    @Volatile private var frontW = 1280
    @Volatile private var frontH = 720
    @Volatile private var backRot = 90
    @Volatile private var frontRot = 270
    @Volatile private var released = false

    private val posBuf: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // ------------------------------------------------------------------
    // API công khai
    // ------------------------------------------------------------------

    /** Khởi tạo EGL/GL. Trả về false nếu thiết bị không hỗ trợ. */
    fun start(): Boolean {
        var ok = false
        runOnGl {
            ok = try { initEgl(); initGl(); true } catch (e: Exception) { false }
        }
        return ok
    }

    /** Cấp Surface cho CameraX Preview: hình camera sẽ chảy vào SurfaceTexture của lớp này. */
    fun provideSurface(back: Boolean, request: SurfaceRequest, executor: Executor) {
        val st = (if (back) backST else frontST)
        if (st == null) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        st.setDefaultBufferSize(size.width, size.height)
        if (back) {
            backW = size.width; backH = size.height
        } else {
            frontW = size.width; frontH = size.height
        }
        val surface = Surface(st)
        request.provideSurface(surface, executor) { surface.release() }
        request.setTransformationInfoListener(executor) { info ->
            if (back) {
                backRot = info.rotationDegrees
            } else {
                frontRot = info.rotationDegrees
            }
        }
    }

    fun setPreviewSurface(surface: Surface?, w: Int, h: Int) {
        runOnGl {
            previewSurf?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            previewSurf = null
            if (surface != null && surface.isValid && !released) {
                val s = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
                previewSurf = s
                previewW = w
                previewH = h
                EGL14.eglMakeCurrent(eglDisplay, s, s, eglContext)
                EGL14.eglSwapInterval(eglDisplay, 0)
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                drawFrame()
            }
        }
    }

    fun startEncoding(surface: Surface, w: Int, h: Int, startNs: Long) {
        runOnGl {
            encSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encW = w
            encH = h
            encStartNs = startNs
        }
    }

    fun stopEncoding() {
        runOnGl {
            encSurf?.let {
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                EGL14.eglDestroySurface(eglDisplay, it)
            }
            encSurf = null
        }
    }

    fun release() {
        runOnGl {
            released = true
            try {
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                previewSurf?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                encSurf?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                previewSurf = null
                encSurf = null
                backST?.release()
                frontST?.release()
                backST = null
                frontST = null
                if (program != 0) GLES20.glDeleteProgram(program)
                GLES20.glDeleteTextures(2, intArrayOf(backTex, frontTex), 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                // bỏ qua lỗi khi dọn dẹp
            }
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------
    // Khởi tạo
    // ------------------------------------------------------------------

    private fun runOnGl(block: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw RuntimeException("eglInitialize")

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        if (num[0] <= 0 || configs[0] == null) throw RuntimeException("eglChooseConfig")
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) throw RuntimeException("eglMakeCurrent")
    }

    private fun initGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
        uTexture = GLES20.glGetUniformLocation(program, "uTex")
        uMask = GLES20.glGetUniformLocation(program, "uMask")
        uSize = GLES20.glGetUniformLocation(program, "uSize")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uBorder = GLES20.glGetUniformLocation(program, "uBorder")

        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        for (t in tex) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        backTex = tex[0]
        frontTex = tex[1]
        Matrix.setIdentityM(backMat, 0)
        Matrix.setIdentityM(frontMat, 0)

        val bst = SurfaceTexture(backTex)
        val fst = SurfaceTexture(frontTex)
        backST = bst
        frontST = fst

        // Camera sau điều khiển nhịp vẽ; camera trước chỉ cập nhật texture
        bst.setOnFrameAvailableListener({
            if (!released) {
                try {
                    bst.updateTexImage()
                    bst.getTransformMatrix(backMat)
                    backHas = true
                    drawFrame()
                } catch (e: Exception) {
                    // bỏ qua khung lỗi
                }
            }
        }, handler)

        fst.setOnFrameAvailableListener({
            if (!released) {
                try {
                    fst.updateTexImage()
                    fst.getTransformMatrix(frontMat)
                    frontHas = true
                } catch (e: Exception) {
                    // bỏ qua khung lỗi
                }
            }
        }, handler)
    }

    // ------------------------------------------------------------------
    // Vẽ
    // ------------------------------------------------------------------

    private fun drawFrame() {
        if (!backHas || released) return
        previewSurf?.let { render(it, previewW, previewH, false) }
        encSurf?.let { render(it, encW, encH, true) }
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun render(surf: EGLSurface, w: Int, h: Int, forEncoder: Boolean) {
        if (w <= 0 || h <= 0) return
        EGL14.eglMakeCurrent(eglDisplay, surf, surf, eglContext)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Camera sau: toàn khung
        drawLayer(backTex, backMat, backW, backH, backRot, false, 0, 0, w, h, false, 0f, 0f)

        // Camera trước: khung nhỏ góc trên bên phải
        if (frontHas) {
            val fw = (w * 0.28f).toInt()
            val fh = fw * 4 / 3
            val margin = (w * 0.04f).toInt()
            val topPx = margin + (h * 0.02f).toInt()
            val vx = w - fw - margin
            val vy = h - topPx - fh
            drawLayer(
                frontTex, frontMat, frontW, frontH, frontRot, true,
                vx, vy, fw, fh, true, fw * 0.12f, max(2f, w * 0.006f)
            )
        }

        if (forEncoder) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, surf, System.nanoTime() - encStartNs)
        }
        EGL14.eglSwapBuffers(eglDisplay, surf)
    }

    private fun drawLayer(
        tex: Int, texMat: FloatArray, bw: Int, bh: Int, rot: Int, mirror: Boolean,
        vx: Int, vy: Int, vw: Int, vh: Int, pip: Boolean, radius: Float, border: Float
    ) {
        GLES20.glViewport(vx, vy, vw, vh)

        // Xoay nội dung cho thẳng đứng, rồi phủ kín (cover) khung đích
        val rotated = rot % 180 != 0
        val cw = (if (rotated) bh else bw).toFloat()
        val ch = (if (rotated) bw else bh).toFloat()
        val k = max(vw / cw, vh / ch)

        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, (if (mirror) -1f else 1f) * k * 2f / vw, k * 2f / vh, 1f)
        Matrix.rotateM(mvp, 0, -rot.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, bw / 2f, bh / 2f, 1f)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        GLES20.glUniform1f(uMask, if (pip) 1f else 0f)
        GLES20.glUniform2f(uSize, vw.toFloat(), vh.toFloat())
        GLES20.glUniform1f(uRadius, radius)
        GLES20.glUniform1f(uBorder, border)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    // ------------------------------------------------------------------
    // Tiện ích GL
    // ------------------------------------------------------------------

    private fun createProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("link: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader: $log")
        }
        return s
    }

    companion object {
        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a)
                position(0)
            }

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMat;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTex;
            varying vec2 vPos;
            void main() {
                gl_Position = uMvp * aPosition;
                vTex = (uTexMat * aTexCoord).xy;
                vPos = gl_Position.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            varying vec2 vPos;
            uniform samplerExternalOES uTex;
            uniform float uMask;
            uniform vec2 uSize;
            uniform float uRadius;
            uniform float uBorder;
            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                if (uMask < 0.5) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                vec2 p = vPos * uSize * 0.5;
                float d = length(max(abs(p) - (uSize * 0.5 - vec2(uRadius)), 0.0)) - uRadius;
                float a = 1.0 - smoothstep(-0.5, 0.5, d);
                float b = smoothstep(-uBorder - 0.5, -uBorder + 0.5, d);
                c = mix(c, vec3(1.0), b);
                gl_FragColor = vec4(c, a);
            }
        """
    }
}
