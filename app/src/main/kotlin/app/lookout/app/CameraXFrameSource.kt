package app.lookout.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import app.lookout.core.CameraMeta
import app.lookout.core.CameraSettings
import app.lookout.core.FocusSetting
import app.lookout.core.FrameSource
import app.lookout.core.LensInfo
import app.lookout.core.LookoutError
import app.lookout.core.RawFrame
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real camera: CameraX ImageAnalysis for the stream and ImageCapture for stills, with Camera2
 * interop for manual focus, AE/AWB lock and per-frame exposure metadata.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXFrameSource(private val context: Context) : FrameSource {
    @Volatile private var owner: LifecycleOwner? = null
    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var camera: Camera? = null
    @Volatile private var capture: ImageCapture? = null
    @Volatile private var desired = CameraSettings(zoom = 1.0, focus = FocusSetting("auto"), torch = false, aeLock = false, awbLock = false, exposureComp = 0)
    @Volatile private var intervalMs = 500L
    @Volatile private var lastDelivered = 0L
    @Volatile private var rotation = 0
    @Volatile private var meta = CameraMeta()
    @Volatile private var lenses: List<LensInfo> = emptyList()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frames: Channel<RawFrame>? = null

    /** Called when the camera fails or another app takes it. */
    @Volatile var onLost: (String) -> Unit = {}

    /** The service attaches itself; CameraX binds to its lifecycle. */
    fun attach(owner: LifecycleOwner?) {
        this.owner = owner
    }

    override fun lenses(): List<LensInfo> = lenses

    override val isOpen: Boolean get() = camera != null

    override suspend fun open(onFrame: (RawFrame) -> Unit) {
        val owner = owner ?: throw LookoutError("service_stopped", 503, "The Lookout service is not running")
        val channel = Channel<RawFrame>(Channel.CONFLATED)
        frames = channel
        scope.launch { for (f in channel) onFrame(f) }
        withContext(Dispatchers.Main) {
            val p = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
            lenses = p.availableCameraInfos.map(::lensInfo)
            bind(p, owner)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.Main) {
            provider?.unbindAll()
            camera = null
            capture = null
        }
        frames?.close()
        frames = null
    }

    /** Drops the binding without suspending. The service calls it on the main thread as it stops. */
    fun reset() {
        provider?.unbindAll()
        camera = null
        capture = null
        frames?.close()
        frames = null
    }

    override suspend fun captureStill(): RawFrame {
        val cap = capture ?: throw LookoutError("camera_closed", 503, "Camera is not open")
        val proxy = withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                cap.takePicture(analyzerExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = cont.resume(image)
                    override fun onError(exception: ImageCaptureException) = cont.resumeWithException(
                        LookoutError("capture_failed", 500, "Still capture failed: ${exception.message}"),
                    )
                })
            }
        }
        val bitmap = proxy.use { AndroidImageOps.rotate(it.toBitmap(), it.imageInfo.rotationDegrees) }
        return RawFrame(BitmapImg(bitmap), System.currentTimeMillis(), meta)
    }

    override suspend fun apply(settings: CameraSettings): CameraSettings {
        val lensChanged = settings.lens != null && settings.lens != desired.lens
        if (settings.lens != null && lenses.isNotEmpty() && lenses.none { it.id == settings.lens }) {
            throw LookoutError("lens_not_found", 400, "No lens '${settings.lens}'. Lenses: ${lenses.joinToString { it.id }}")
        }
        desired = desired.merge(settings)
        withContext(Dispatchers.Main) {
            val p = provider
            val o = owner
            if (lensChanged && camera != null && p != null && o != null) bind(p, o) else camera?.let { applyControls(it) }
        }
        return desired
    }

    override fun setAnalysisFps(fps: Double) {
        intervalMs = (1000.0 / fps).toLong().coerceAtLeast(30)
    }

    @SuppressLint("RestrictedApi")
    private suspend fun bind(p: ProcessCameraProvider, owner: LifecycleOwner) {
        p.unbindAll()
        val selector = desired.lens?.let { id ->
            CameraSelector.Builder().addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }.build()
        } ?: CameraSelector.DEFAULT_BACK_CAMERA

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(selector(Size(1280, 960)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                meta = metaFrom(result)
            }
        })
        val analysis = analysisBuilder.build().also { it.setAnalyzer(analyzerExecutor, ::analyze) }
        val still = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector(Size(4000, 3000)))
            .build()
        val cam = p.bindToLifecycle(owner, selector, analysis, still)
        camera = cam
        capture = still
        val lensId = Camera2CameraInfo.from(cam.cameraInfo).cameraId
        desired = desired.copy(lens = lensId)
        cam.cameraInfo.cameraState.observe(owner) { state ->
            val err = state.error
            if (err != null && camera === cam) {
                onLost("Camera error ${err.code}")
                scope.launch { close() }
            }
        }
        applyControls(cam)
    }

    private fun selector(size: Size) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

    private fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastDelivered < intervalMs) {
            image.close()
            return
        }
        lastDelivered = now
        rotation = image.imageInfo.rotationDegrees
        val bitmap: Bitmap = image.use { AndroidImageOps.rotate(it.toBitmap(), it.imageInfo.rotationDegrees) }
        frames?.trySend(RawFrame(BitmapImg(bitmap), now, meta))
    }

    private suspend fun applyControls(cam: Camera) {
        val d = desired
        val control = cam.cameraControl
        d.zoom?.let { z ->
            val zs = cam.cameraInfo.zoomState.value
            val clamped = if (zs == null) z.toFloat() else z.toFloat().coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
            runCatching { control.setZoomRatio(clamped).await() }
        }
        if (cam.cameraInfo.hasFlashUnit()) d.torch?.let { runCatching { control.enableTorch(it).await() } }
        d.exposureComp?.let { idx ->
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                runCatching { control.setExposureCompensationIndex(idx.coerceIn(range.lower, range.upper)).await() }
            }
        }
        val options = CaptureRequestOptions.Builder()
        if (d.aeLock == true) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        if (d.awbLock == true) options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        val focus = d.focus
        if (focus?.mode == "manual") {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.distance!!.toFloat())
        }
        runCatching { Camera2CameraControl.from(control).setCaptureRequestOptions(options.build()).await() }
        when (focus?.mode) {
            "point" -> {
                val (sx, sy) = toSensor(focus.x!!, focus.y!!)
                val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(sx.toFloat(), sy.toFloat())
                runCatching { withTimeout(3_000) { control.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build()).await() } }
            }
            "auto" -> runCatching { control.cancelFocusAndMetering().await() }
        }
    }

    /** Maps an upright image point to the sensor orientation that metering points use. */
    private fun toSensor(x: Double, y: Double): Pair<Double, Double> = when (rotation) {
        90 -> y to 1 - x
        180 -> 1 - x to 1 - y
        270 -> 1 - y to x
        else -> x to y
    }

    private fun metaFrom(r: TotalCaptureResult): CameraMeta {
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val af = r.get(CaptureResult.CONTROL_AF_STATE)
        val afMode = r.get(CaptureResult.CONTROL_AF_MODE)
        return CameraMeta(
            lens = desired.lens ?: "back",
            zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: 1.0,
            focusDistance = r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble(),
            exposureTimeNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            whiteBalance = when (r.get(CaptureResult.CONTROL_AWB_MODE)) {
                CaptureResult.CONTROL_AWB_MODE_AUTO -> if (r.get(CaptureResult.CONTROL_AWB_LOCK) == true) "auto-locked" else "auto"
                CaptureResult.CONTROL_AWB_MODE_OFF -> "off"
                null -> null
                else -> "preset"
            },
            aeConverged = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
            afConverged = af == null || afMode == CaptureResult.CONTROL_AF_MODE_OFF || af == CaptureResult.CONTROL_AF_STATE_INACTIVE ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        )
    }

    private fun lensInfo(info: CameraInfo): LensInfo {
        val c2 = Camera2CameraInfo.from(info)
        val zs = info.zoomState.value
        return LensInfo(
            id = c2.cameraId,
            facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> "front"
                CameraSelector.LENS_FACING_BACK -> "back"
                else -> "external"
            },
            minZoom = zs?.minZoomRatio?.toDouble() ?: 1.0,
            maxZoom = zs?.maxZoomRatio?.toDouble() ?: 1.0,
            hasFlash = info.hasFlashUnit(),
            manualFocus = (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f,
            minFocusDistance = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toDouble(),
        )
    }
}

/** Awaits a ListenableFuture without blocking. */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: java.util.concurrent.ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}
