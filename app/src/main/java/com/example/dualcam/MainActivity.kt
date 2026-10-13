package com.example.dualcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class Res(val label: String, val w: Int, val h: Int, val bitrate: Int)

    // Các mức phân giải VIDEO (dọc, tỉ lệ 9:16)
    private val resolutions = listOf(
        Res("480p", 480, 848, 2_500_000),
        Res("720p (HD)", 720, 1280, 6_000_000),
        Res("1080p (Full HD)", 1080, 1920, 12_000_000)
    )
    private var resIndex = 1

    private lateinit var root: FrameLayout
    private lateinit var previewBack: PreviewView
    private lateinit var previewFront: PreviewView
    private lateinit var surfaceComposite: SurfaceView
    private lateinit var btnMode: Button
    private lateinit var btnRes: Button
    private lateinit var btnAction: Button

    private var provider: ProcessCameraProvider? = null
    private var videoMode = false

    // Chế độ ảnh
    private var imageBack: ImageCapture? = null
    private var imageFront: ImageCapture? = null

    // Chế độ video
    private var compositor: GlCompositor? = null
    private var recorder: VideoRecorder? = null
    private var recordingUri: android.net.Uri? = null
    private var holderValid = false
    private var holderW = 0
    private var holderH = 0

    private val bg = Executors.newSingleThreadExecutor()
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        previewBack = findViewById(R.id.previewBack)
        previewFront = findViewById(R.id.previewFront)
        surfaceComposite = findViewById(R.id.surfaceComposite)
        btnMode = findViewById(R.id.btnMode)
        btnRes = findViewById(R.id.btnRes)
        btnAction = findViewById(R.id.btnAction)

        previewBack.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewFront.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewFront.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 24f * resources.displayMetrics.density)
            }
        }
        previewFront.clipToOutline = true

        surfaceComposite.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                holderValid = true
                holderW = width
                holderH = height
                compositor?.setPreviewSurface(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                holderValid = false
                compositor?.setPreviewSurface(null, 0, 0)
            }
        })

        btnMode.setOnClickListener {
            if (recorder != null) return@setOnClickListener
            videoMode = !videoMode
            updateUi()
            bindCameras()
        }
        btnRes.setOnClickListener { chooseResolution() }
        btnAction.setOnClickListener {
            if (videoMode) {
                if (recorder == null) startVideo() else stopVideo()
            } else {
                takePhoto()
            }
        }
        updateUi()

        if (permissions.all { granted(it) }) startCamera()
        else ActivityCompat.requestPermissions(this, permissions, 10)
    }

    override fun onStop() {
        super.onStop()
        if (recorder != null) stopVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        compositor?.release()
        compositor = null
        bg.shutdown()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (granted(Manifest.permission.CAMERA)) startCamera()
        else toast("Cần cấp quyền camera để sử dụng ứng dụng")
    }

    private fun updateUi() {
        btnMode.text = if (videoMode) "Chế độ: Video" else "Chế độ: Ảnh"
        btnRes.visibility = if (videoMode) View.VISIBLE else View.GONE
        btnRes.text = "Độ phân giải: ${resolutions[resIndex].label}"
        btnAction.text = if (videoMode) "Quay" else "Chụp"
    }

    private fun chooseResolution() {
        if (recorder != null) return
        val labels = resolutions.map { "${it.label}  (${it.w}x${it.h})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Độ phân giải video")
            .setSingleChoiceItems(labels, resIndex) { dialog, which ->
                resIndex = which
                updateUi()
                dialog.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------
    //  Mở camera
    // ------------------------------------------------------------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCameras()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameras() {
        val p = provider ?: return
        p.unbindAll()
        compositor?.release()
        compositor = null

        val supported = p.availableConcurrentCameraInfos.any { pair ->
            CameraSelector.DEFAULT_FRONT_CAMERA.filter(pair).isNotEmpty() &&
                CameraSelector.DEFAULT_BACK_CAMERA.filter(pair).isNotEmpty()
        }
        if (!supported) {
            toast("Điện thoại này không hỗ trợ bật đồng thời 2 camera")
            return
        }
        try {
            if (videoMode) bindVideoMode(p) else bindPhotoMode(p)
        } catch (e: Exception) {
            toast("Không mở được 2 camera: ${e.message}")
        }
    }

    // ---------------- Chế độ ẢNH ----------------
    private fun bindPhotoMode(p: ProcessCameraProvider) {
        previewBack.visibility = View.VISIBLE
        previewFront.visibility = View.VISIBLE
        surfaceComposite.visibility = View.GONE

        fun group(view: PreviewView, back: Boolean): UseCaseGroup {
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val ic = ImageCapture.Builder().build()
            if (back) imageBack = ic else imageFront = ic
            return UseCaseGroup.Builder().addUseCase(preview).addUseCase(ic).build()
        }

        val backCfg = ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_BACK_CAMERA, group(previewBack, true), this
        )
        val frontCfg = ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_FRONT_CAMERA, group(previewFront, false), this
        )
        p.bindToLifecycle(listOf(backCfg, frontCfg))
    }

    // ---------------- Chế độ VIDEO ----------------
    private fun bindVideoMode(p: ProcessCameraProvider) {
        previewBack.visibility = View.GONE
        previewFront.visibility = View.GONE
        surfaceComposite.visibility = View.VISIBLE
        fitCompositeView()

        val comp = GlCompositor()
        if (!comp.start()) {
            comp.release()
            toast("Thiết bị không hỗ trợ ghép video bằng GPU")
            return
        }
        compositor = comp
        if (holderValid) comp.setPreviewSurface(surfaceComposite.holder.surface, holderW, holderH)

        val exec = ContextCompat.getMainExecutor(this)
        val backCfg = ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_BACK_CAMERA, videoGroup(comp, true, exec), this
        )
        val frontCfg = ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_FRONT_CAMERA, videoGroup(comp, false, exec), this
        )
        p.bindToLifecycle(listOf(backCfg, frontCfg))
    }

    private fun videoGroup(comp: GlCompositor, back: Boolean, exec: Executor): UseCaseGroup {
        val rs = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(rs).build()
        preview.setSurfaceProvider(exec) { req -> comp.provideSurface(back, req, exec) }
        return UseCaseGroup.Builder().addUseCase(preview).build()
    }

    /** Khung xem trước video luôn tỉ lệ 9:16 (giống hệt video sẽ ghi), đặt giữa màn hình. */
    private fun fitCompositeView() {
        root.post {
            val rw = root.width
            val rh = root.height
            if (rw == 0 || rh == 0) return@post
            val w: Int
            val h: Int
            if (rw * 16 > rh * 9) {
                h = rh
                w = rh * 9 / 16
            } else {
                w = rw
                h = rw * 16 / 9
            }
            surfaceComposite.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
    }

    private fun stamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    // ==================================================================
    //  QUAY VIDEO: 1 file duy nhất, 2 camera trong cùng khung hình
    // ==================================================================
    private fun startVideo() {
        val comp = compositor
        if (comp == null) {
            toast("Camera chưa sẵn sàng")
            return
        }
        val res = resolutions[resIndex]
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "DualCam_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/DualCam")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = uri?.let { contentResolver.openFileDescriptor(it, "rw") }
        if (uri == null || pfd == null) {
            toast("Không tạo được file video")
            return
        }
        val rec = VideoRecorder(comp, pfd, res.w, res.h, res.bitrate, granted(Manifest.permission.RECORD_AUDIO))
        try {
            rec.start()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("Không bắt đầu ghi được: ${e.message}")
            return
        }
        recorder = rec
        recordingUri = uri
        btnAction.text = "Dừng"
        btnMode.isEnabled = false
        btnRes.isEnabled = false
    }

    private fun stopVideo() {
        val rec = recorder ?: return
        val uri = recordingUri
        recorder = null
        recordingUri = null
        btnAction.isEnabled = false
        rec.stop { ok ->
            runOnUiThread {
                if (!ok && uri != null) contentResolver.delete(uri, null, null)
                toast(if (ok) "Đã lưu video vào Movies/DualCam" else "Lưu video thất bại")
                btnAction.isEnabled = true
                btnMode.isEnabled = true
                btnRes.isEnabled = true
                updateUi()
            }
        }
    }

    // ==================================================================
    //  CHỤP ẢNH: ghép camera sau (toàn khung) + camera trước (góc nhỏ)
    // ==================================================================
    private fun takePhoto() {
        val icBack = imageBack ?: return
        val icFront = imageFront ?: return
        btnAction.isEnabled = false

        var back: Bitmap? = null
        var front: Bitmap? = null
        var remaining = 2

        // Mọi callback chạy trên luồng `bg` (tuần tự) nên không cần khóa
        val finish = {
            remaining--
            if (remaining == 0) composeAndSave(back, front)
        }
        grab(icBack) { back = it; finish() }
        grab(icFront) { front = it; finish() }
    }

    private fun grab(ic: ImageCapture, done: (Bitmap?) -> Unit) {
        ic.takePicture(bg, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try {
                    val raw = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    if (rot == 0) raw else {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                done(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                done(null)
            }
        })
    }

    private fun composeAndSave(back: Bitmap?, front: Bitmap?) {
        if (back == null) {
            runOnUiThread { toast("Chụp ảnh thất bại"); btnAction.isEnabled = true }
            return
        }

        val w = back.width
        val h = back.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(back, 0f, 0f, null)

        if (front != null) {
            val flip = Matrix().apply { postScale(-1f, 1f, front.width / 2f, front.height / 2f) }
            val mirrored = Bitmap.createBitmap(front, 0, 0, front.width, front.height, flip, true)

            val fw = (w * 0.28f)
            val fh = fw * 4f / 3f
            val margin = w * 0.04f
            val left = w - fw - margin
            val top = margin + h * 0.02f
            val rect = RectF(left, top, left + fw, top + fh)
            val radius = fw * 0.12f

            // Cắt ảnh camera trước theo tỉ lệ 3:4 (cover) rồi thu nhỏ
            val srcRatio = mirrored.width.toFloat() / mirrored.height
            val dstRatio = 3f / 4f
            val cropW: Int
            val cropH: Int
            if (srcRatio > dstRatio) {
                cropH = mirrored.height
                cropW = (cropH * dstRatio).toInt()
            } else {
                cropW = mirrored.width
                cropH = (cropW / dstRatio).toInt()
            }
            val cx = (mirrored.width - cropW) / 2
            val cy = (mirrored.height - cropH) / 2
            val cropped = Bitmap.createBitmap(mirrored, cx, cy, cropW, cropH)
            val scaled = Bitmap.createScaledBitmap(cropped, fw.toInt(), fh.toInt(), true)

            canvas.save()
            val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawBitmap(scaled, left, top, null)
            canvas.restore()

            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w * 0.006f
                color = Color.WHITE
            }
            canvas.drawRoundRect(rect, radius, radius, border)
        }

        val ok = saveJpeg(out, "DualCam_${stamp()}")
        runOnUiThread {
            toast(if (ok) "Đã lưu ảnh vào thư viện (Pictures/DualCam)" else "Lưu ảnh thất bại")
            btnAction.isEnabled = true
        }
    }

    private fun saveJpeg(bmp: Bitmap, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DualCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
