package com.example.duocamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    private lateinit var previewContainer: FrameLayout
    private lateinit var previewPrimary: PreviewView
    private lateinit var previewSecondary: PreviewView
    private lateinit var previewComposite: PreviewView
    private lateinit var statusText: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var layoutButton: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var flipButton: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    /** A rear + front pair this phone can run at the same time, or null if it can't. */
    private var dualCameras: DualCameras? = null
    private val isDual get() = dualCameras != null

    private var mode = CaptureMode.PHOTO
    private var layout = DualLayout.PIP

    /** Dual mode: whether the selfie camera is the big one. Single mode: which camera is showing. */
    private var frontIsMain = false

    private var mainCapture: ImageCapture? = null
    private var insetCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var busy = false

    /** Message that stays on screen (e.g. "single camera mode"). */
    private var notice: String? = null
    /** Short-lived message that overrides [notice] (e.g. the recording timer). */
    private var status: String? = null

    private data class DualCameras(val back: CameraSelector, val front: CameraSelector) {
        fun main(frontIsMain: Boolean) = if (frontIsMain) front else back
        fun inset(frontIsMain: Boolean) = if (frontIsMain) back else front
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            notice = getString(R.string.camera_permission_needed)
            updateControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        previewContainer = findViewById(R.id.previewContainer)
        previewPrimary = findViewById(R.id.previewPrimary)
        previewSecondary = findViewById(R.id.previewSecondary)
        previewComposite = findViewById(R.id.previewComposite)
        statusText = findViewById(R.id.statusText)
        photoTab = findViewById(R.id.photoTab)
        videoTab = findViewById(R.id.videoTab)
        layoutButton = findViewById(R.id.layoutButton)
        shutterButton = findViewById(R.id.shutterButton)
        flipButton = findViewById(R.id.flipButton)

        // Android 15+ draws apps edge to edge; keep the controls clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // TextureView-based previews can overlap each other (needed for picture-in-picture).
        listOf(previewPrimary, previewSecondary, previewComposite).forEach {
            it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewComposite.scaleType = PreviewView.ScaleType.FIT_CENTER

        cameraExecutor = Executors.newSingleThreadExecutor()

        photoTab.setOnClickListener { setMode(CaptureMode.PHOTO) }
        videoTab.setOnClickListener { setMode(CaptureMode.VIDEO) }
        layoutButton.setOnClickListener {
            layout = layout.next()
            bindCameras()
        }
        flipButton.setOnClickListener {
            frontIsMain = !frontIsMain
            bindCameras()
        }
        shutterButton.setOnClickListener {
            if (mode == CaptureMode.PHOTO) takePhoto() else toggleRecording()
        }
        updateControls()

        val needed = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = needed.filterNot { hasPermission(it) }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onStop() {
        // Finish and save any recording when the app goes to the background.
        recording?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ---------------------------------------------------------------- camera setup

    private fun startCamera() {
        if (cameraProvider != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                notice = getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName)
                updateControls()
                return@addListener
            }
            cameraProvider = provider
            dualCameras = findDualCameras(provider)
            notice = if (dualCameras == null) getString(R.string.single_camera_notice) else null
            bindCameras()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Looks for a rear + front camera combination the phone says it can run concurrently. */
    private fun findDualCameras(provider: ProcessCameraProvider): DualCameras? {
        for (infos in provider.availableConcurrentCameraInfos) {
            val back = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) return DualCameras(back.cameraSelector, front.cameraSelector)
        }
        return null
    }

    private fun setMode(newMode: CaptureMode) {
        if (newMode == mode || recording != null || busy) return
        mode = newMode
        bindCameras()
    }

    /** (Re)connects the cameras for the current mode, layout and camera order. */
    private fun bindCameras() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        mainCapture = null
        insetCapture = null
        // A single-camera recording survives rebinding (that's how Flip works mid-recording).
        if (recording == null) videoCapture = null
        applyViewfinderLayout()

        val dual = dualCameras
        try {
            when {
                dual != null && mode == CaptureMode.PHOTO -> bindDualPhoto(provider, dual)
                dual != null -> bindDualVideo(provider, dual)
                else -> bindSingle(provider)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binding cameras failed", e)
            if (dual != null) {
                // The phone advertised dual camera but this combination failed: fall back.
                dualCameras = null
                notice = getString(R.string.dual_failed_notice)
                bindCameras()
                return
            }
            notice = getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName)
        }
        updateControls()
    }

    /** Photo mode, both cameras live: each camera gets its own preview and still capture. */
    private fun bindDualPhoto(provider: ProcessCameraProvider, dual: DualCameras) {
        val mainPreview = newPreview(previewPrimary, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val insetPreview = newPreview(previewSecondary, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val mainCap = newImageCapture()
        val insetCap = newImageCapture()

        val mainGroup = UseCaseGroup.Builder().addUseCase(mainPreview).addUseCase(mainCap).build()
        val insetGroup = UseCaseGroup.Builder().addUseCase(insetPreview).addUseCase(insetCap).build()

        provider.bindToLifecycle(
            listOf(
                SingleCameraConfig(dual.main(frontIsMain), mainGroup, this),
                SingleCameraConfig(dual.inset(frontIsMain), insetGroup, this),
            )
        )
        mainCapture = mainCap
        insetCapture = insetCap
    }

    /**
     * Video mode, both cameras live: one shared preview and one shared recorder.
     * CameraX composites both cameras into a single frame using [VideoComposition].
     */
    private fun bindDualVideo(provider: ProcessCameraProvider, dual: DualCameras) {
        val preview = newPreview(previewComposite, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        val vc = newVideoCapture(Quality.HD)
        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc).build()
        val (mainComposition, insetComposition) = VideoComposition.forLayout(layout)

        // Order matters: the second camera is drawn on top of the first.
        provider.bindToLifecycle(
            listOf(
                SingleCameraConfig(dual.main(frontIsMain), group, mainComposition, this),
                SingleCameraConfig(dual.inset(frontIsMain), group, insetComposition, this),
            )
        )
        videoCapture = vc
    }

    /** Fallback for phones that can't run both cameras at once. */
    private fun bindSingle(provider: ProcessCameraProvider) {
        var selector = if (frontIsMain) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!hasCamera(provider, selector)) {
            frontIsMain = !frontIsMain
            selector = if (frontIsMain) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (mode == CaptureMode.PHOTO) {
            val preview = newPreview(previewPrimary, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val cap = newImageCapture()
            provider.bindToLifecycle(this, selector, preview, cap)
            mainCapture = cap
        } else {
            val preview = newPreview(previewPrimary, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            val vc = videoCapture ?: newVideoCapture(Quality.FHD)
            provider.bindToLifecycle(this, selector, preview, vc)
            videoCapture = vc
        }
    }

    private fun hasCamera(provider: ProcessCameraProvider, selector: CameraSelector): Boolean =
        runCatching { provider.hasCamera(selector) }.getOrDefault(false)

    private fun newPreview(target: PreviewView, aspect: AspectRatioStrategy): Preview =
        Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .build()
            .also { it.setSurfaceProvider(target.surfaceProvider) }

    private fun newImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(2048, 1536),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()

    private fun newVideoCapture(quality: Quality): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        return VideoCapture.withOutput(recorder)
    }

    // ---------------------------------------------------------------- viewfinder

    /** Sizes the viewfinder like the saved file and arranges the preview windows for the layout. */
    private fun applyViewfinderLayout() {
        previewContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
            dimensionRatio = if (mode == CaptureMode.PHOTO) "3:4" else "9:16"
        }
        val showComposite = isDual && mode == CaptureMode.VIDEO
        val showInset = isDual && mode == CaptureMode.PHOTO
        previewComposite.isVisible = showComposite
        previewPrimary.isVisible = !showComposite
        previewSecondary.isVisible = showInset

        if (!showInset) {
            previewPrimary.layoutParams = frame(MATCH, MATCH, Gravity.TOP)
            return
        }
        previewContainer.doOnLayout { container ->
            val w = container.width
            val h = container.height
            when (layout) {
                DualLayout.PIP -> {
                    val pipW = (w * 0.32f).toInt()
                    val margin = (w * 0.04f).toInt()
                    previewPrimary.layoutParams = frame(MATCH, MATCH, Gravity.TOP)
                    previewSecondary.layoutParams = frame(pipW, pipW * 4 / 3, Gravity.TOP or Gravity.END)
                        .apply { setMargins(margin, margin, margin, margin) }
                    previewSecondary.bringToFront()
                }
                DualLayout.TOP_BOTTOM -> {
                    previewPrimary.layoutParams = frame(MATCH, h / 2, Gravity.TOP)
                    previewSecondary.layoutParams = frame(MATCH, h / 2, Gravity.BOTTOM)
                }
                DualLayout.SIDE_BY_SIDE -> {
                    previewPrimary.layoutParams = frame(w / 2, MATCH, Gravity.START)
                    previewSecondary.layoutParams = frame(w / 2, MATCH, Gravity.END)
                }
            }
        }
    }

    private fun frame(width: Int, height: Int, gravity: Int) = FrameLayout.LayoutParams(width, height, gravity)

    // ---------------------------------------------------------------- photos

    private fun takePhoto() {
        if (busy || mainCapture == null) return
        busy = true
        updateControls()
        lifecycleScope.launch {
            try {
                val (main, inset) = if (isDual) captureBoth() else captureOneAfterAnother()
                val merged = withContext(Dispatchers.Default) {
                    PhotoComposer.compose(main, inset, layout).also {
                        main.recycle()
                        inset.recycle()
                    }
                }
                withContext(Dispatchers.IO) {
                    MediaSaver.saveJpeg(this@MainActivity, merged)
                    merged.recycle()
                }
                toast(getString(R.string.photo_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Photo failed", e)
                toast(getString(R.string.photo_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
                status = null
                // Single-camera mode rebinds cameras while shooting; restore the normal preview.
                if (isDual) updateControls() else bindCameras()
            }
        }
    }

    /** Both cameras fire at the same moment. */
    private suspend fun captureBoth(): Pair<Bitmap, Bitmap> = coroutineScope {
        val mainCap = mainCapture ?: error("Camera not ready")
        val insetCap = insetCapture ?: error("Camera not ready")
        val main = async { mainCap.takeBitmap(mirror = frontIsMain) }
        val inset = async { insetCap.takeBitmap(mirror = !frontIsMain) }
        main.await() to inset.await()
    }

    /** Fallback: shoot the showing camera, switch, shoot the other one. */
    private suspend fun captureOneAfterAnother(): Pair<Bitmap, Bitmap> {
        val provider = cameraProvider ?: error("Camera not ready")
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val back = CameraSelector.DEFAULT_BACK_CAMERA

        setStatus(getString(R.string.taking_first))
        val main = mainCapture!!.takeBitmap(mirror = frontIsMain)

        setStatus(getString(R.string.taking_second))
        provider.unbindAll()
        val preview = newPreview(previewPrimary, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val cap = newImageCapture()
        provider.bindToLifecycle(this@MainActivity, if (frontIsMain) back else front, preview, cap)
        val inset = cap.takeBitmap(mirror = !frontIsMain)
        return main to inset
    }

    private suspend fun ImageCapture.takeBitmap(mirror: Boolean): Bitmap =
        suspendCancellableCoroutine { cont ->
            takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        image.use {
                            PhotoComposer.upright(it.toBitmap(), it.imageInfo.rotationDegrees, mirror)
                        }
                    }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    // ---------------------------------------------------------------- video

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked right before use.
    @OptIn(ExperimentalPersistentRecording::class)
    private fun toggleRecording() {
        recording?.let {
            it.stop()
            return
        }
        val vc = videoCapture ?: return
        var pending = vc.output.prepareRecording(this, MediaSaver.videoOutputOptions(this))
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        // Single-camera mode: keep recording while Flip switches cameras.
        if (!isDual) pending = pending.asPersistentRecording()
        recording = pending.start(ContextCompat.getMainExecutor(this), ::onRecordEvent)
        updateControls()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> setStatus(formatDuration(event.recordingStats.recordedDurationNanos))
            is VideoRecordEvent.Finalize -> {
                recording = null
                status = null
                if (event.hasError()) {
                    Log.e(TAG, "Recording finished with error ${event.error}", event.cause)
                    toast(getString(R.string.video_failed, event.error))
                } else {
                    toast(getString(R.string.video_saved))
                }
                // Release the recorder that was kept alive for the recording.
                if (!isDual && mode == CaptureMode.VIDEO) bindCameras() else updateControls()
            }
            else -> Unit
        }
    }

    private fun formatDuration(nanos: Long): String {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        return String.format(Locale.US, "● REC %02d:%02d", seconds / 60, seconds % 60)
    }

    // ---------------------------------------------------------------- UI state

    private fun updateControls() {
        val recordingNow = recording != null
        val idle = !busy && !recordingNow

        styleTab(photoTab, mode == CaptureMode.PHOTO, idle)
        styleTab(videoTab, mode == CaptureMode.VIDEO, idle)

        layoutButton.setText(layout.labelRes)
        // Single-camera video has no second camera to arrange.
        layoutButton.visibility = if (isDual || mode == CaptureMode.PHOTO) View.VISIBLE else View.INVISIBLE
        setEnabled(layoutButton, idle)

        flipButton.setText(if (isDual) R.string.swap else R.string.flip)
        // A combined dual recording can't change cameras mid-way; a single-camera one can.
        setEnabled(flipButton, !busy && !(isDual && recordingNow) && cameraProvider != null)

        shutterButton.setImageResource(
            when {
                mode == CaptureMode.PHOTO -> R.drawable.shutter_photo
                recordingNow -> R.drawable.shutter_recording
                else -> R.drawable.shutter_video
            }
        )
        setEnabled(shutterButton, !busy && cameraProvider != null)

        val text = status ?: notice
        statusText.text = text
        statusText.isVisible = text != null
    }

    private fun setStatus(text: String?) {
        status = text
        updateControls()
    }

    private fun styleTab(tab: TextView, selected: Boolean, enabled: Boolean) {
        tab.setTextColor(if (selected) ACCENT else Color.WHITE)
        setEnabled(tab, enabled || selected)
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "DuoCamera"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val ACCENT = Color.parseColor("#FFC107")
    }
}
