package app.lookout.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
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
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.lookout.core.CameraMeta
import app.lookout.core.CameraSettings
import app.lookout.core.FocusSetting
import app.lookout.core.FrameOrientation
import app.lookout.core.FrameSource
import app.lookout.core.LensInfo
import app.lookout.core.LookoutError
import app.lookout.core.NRect
import app.lookout.core.RawFrame
import app.lookout.core.RecordSpec
import app.lookout.core.RecordingCaps
import app.lookout.core.RecordingEnd
import app.lookout.core.RecordingStarted
import app.lookout.core.ScaleCalibration
import app.lookout.core.Tilt
import app.lookout.core.VideoResolution
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.round

/**
 * The real camera: CameraX ImageAnalysis for the stream and ImageCapture for stills, with Camera2
 * interop for manual focus, AE/AWB lock and per-frame exposure metadata. While it records video it
 * binds ImageAnalysis and VideoCapture instead, so the stream keeps running and stills are unavailable.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXFrameSource(private val context: Context) : FrameSource {
    @Volatile private var owner: LifecycleOwner? = null
    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var camera: Camera? = null
    @Volatile private var analysis: ImageAnalysis? = null
    @Volatile private var capture: ImageCapture? = null
    @Volatile private var desired = CameraSettings(zoom = 1.0, focus = FocusSetting("auto"), torch = false, aeLock = false, awbLock = false, exposureComp = 0)
    @Volatile private var intervalMs = 500L
    @Volatile private var lastDelivered = 0L
    @Volatile private var rotation = 0
    // A fixed target rotation from the orientation setting. Without it, CameraX uses the display
    // rotation at the moment it binds, so frames turned with the screen.
    @Volatile private var targetRotation = Surface.ROTATION_0
    @Volatile private var meta = CameraMeta()
    @Volatile private var lenses: List<LensInfo> = emptyList()
    @Volatile private var recording: Recording? = null
    private val gravity = GravityReader(context)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frames: Channel<RawFrame>? = null

    /** Called when the camera fails or another app takes it. */
    @Volatile var onLost: (String) -> Unit = {}

    /** The service attaches itself; CameraX binds to its lifecycle. */
    fun attach(owner: LifecycleOwner?) {
        this.owner = owner
        // The sensor runs while the service runs, so describe has a tilt even with the camera closed.
        if (owner != null) gravity.start(SensorManager.SENSOR_DELAY_NORMAL) else gravity.stop()
        // Load the provider early, so describe can list the recording formats before the camera opens.
        if (owner != null && provider == null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching { future.get() }.getOrNull()?.let { p -> provider = p; lenses = p.availableCameraInfos.map(::lensInfo) } }, Runnable::run)
        }
    }

    override fun lenses(): List<LensInfo> = lenses

    override fun tilt(): Tilt? {
        val up = gravity.up(targetRotation) ?: return null
        val front = lenses.firstOrNull { it.id == desired.lens }?.facing == "front"
        return Tilt.fromUp(up[0].toDouble(), up[1].toDouble(), up[2].toDouble(), front)
    }

    override fun setOrientation(orientation: FrameOrientation) {
        val r = if (orientation == FrameOrientation.LANDSCAPE) Surface.ROTATION_90 else Surface.ROTATION_0
        if (r == targetRotation) return
        targetRotation = r
        provider?.let { p -> runCatching { lenses = p.availableCameraInfos.map(::lensInfo) } }
        // Rebind an open camera so the next frame faces the new way. A recording keeps its rotation
        // until it ends; the rebind after it uses the new one.
        if (camera != null && recording == null) {
            scope.launch(Dispatchers.Main) {
                val p = provider
                val o = owner
                if (p != null && o != null && camera != null && recording == null) runCatching { bind(p, o) }
            }
        }
    }

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
        recording?.stop()
        withContext(Dispatchers.Main) {
            provider?.unbindAll()
            camera = null
            analysis = null
            capture = null
        }
        frames?.close()
        frames = null
    }

    /** Drops the binding without suspending. The service calls it on the main thread as it stops. */
    fun reset() {
        recording?.stop()
        provider?.unbindAll()
        camera = null
        analysis = null
        capture = null
        frames?.close()
        frames = null
    }

    override suspend fun captureStill(): RawFrame {
        if (recording != null) throw LookoutError("recording_active", 409, "Stills are unavailable while a recording runs")
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
        val exposedAt = exposureWallMs(proxy.imageInfo.timestamp)
        val bitmap = proxy.use { AndroidImageOps.rotate(it.toBitmap(), it.imageInfo.rotationDegrees) }
        return RawFrame(BitmapImg(bitmap), exposedAt, meta.copy(tilt = tilt()))
    }

    override suspend fun apply(settings: CameraSettings): CameraSettings {
        val lensChanged = settings.lens != null && settings.lens != desired.lens
        if (lensChanged && recording != null) throw LookoutError("recording_active", 409, "The lens cannot change while a recording runs")
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

    /** The CameraInfo of the lens in use, or of the default back lens when the camera is closed. */
    private fun currentInfo(): CameraInfo? {
        camera?.let { return it.cameraInfo }
        val infos = provider?.availableCameraInfos ?: return null
        val id = desired.lens
        return infos.firstOrNull { id != null && Camera2CameraInfo.from(it).cameraId == id }
            ?: infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
    }

    override fun recordingCaps(): RecordingCaps? {
        val info = currentInfo() ?: return null
        val qualities = runCatching { Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR) }.getOrDefault(emptyList())
        val resolutions = buildList {
            if (Quality.HD in qualities) add(VideoResolution.HD.wire)
            if (Quality.FHD in qualities) add(VideoResolution.FHD.wire)
        }
        val fps = RECORD_FPS.filter { fpsRange(info, it) != null }
        return if (resolutions.isEmpty() || fps.isEmpty()) null else RecordingCaps(fps, resolutions, audio = true)
    }

    override fun audioPermitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** A fixed [fps, fps] range when the lens has one, else the narrowest range that tops out at [fps]. */
    private fun fpsRange(info: CameraInfo, fps: Int): Range<Int>? {
        val ranges = info.supportedFrameRateRanges
        return ranges.firstOrNull { it.lower == fps && it.upper == fps } ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    override suspend fun startRecording(spec: RecordSpec, file: File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
        if (spec.audio && !audioPermitted()) {
            throw LookoutError("audio_permission", 403, "Microphone permission is missing. Grant the microphone permission to Lookout on the phone.")
        }
        val started = CompletableDeferred<Long>()
        val (vc, videoCrop) = withContext(Dispatchers.Main) {
            val p = provider ?: throw LookoutError("camera_closed", 503, "Camera is not open")
            val o = owner ?: throw LookoutError("service_stopped", 503, "The Lookout service is not running")
            val cam = camera ?: throw LookoutError("camera_closed", 503, "Camera is not open")
            if (recording != null) throw LookoutError("recording_active", 409, "A recording is already running")
            val range = fpsRange(cam.cameraInfo, spec.fps) ?: throw LookoutError("invalid_param", 400, "This lens cannot record at ${spec.fps} fps")
            val quality = if (spec.resolution == VideoResolution.HD) Quality.HD else Quality.FHD
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                .build()
            // Request stabilization off so the HAL does not apply an extra EIS crop beyond CameraX's transform.
            val vc = VideoCapture.Builder(recorder)
                .setTargetFrameRate(range)
                .setTargetRotation(targetRotation)
                .setVideoStabilizationEnabled(false)
                .build()
            try {
                bind(p, o, vc)
            } catch (e: Exception) {
                runCatching { bind(p, o) }
                throw LookoutError("recording_failed", 500, "The camera cannot record ${spec.resolution.wire} at ${spec.fps} fps here: ${e.message}")
            }
            vc to (analysis?.let { computeVideoCrop(it, vc) } ?: NRect.FULL)
        }
        // The rebind restarts auto exposure. Wait for it, so the first frames are not dark or blown out.
        withTimeoutOrNull(AE_SETTLE_MS) {
            delay(200)
            while (!meta.aeConverged) delay(50)
        }
        withContext(Dispatchers.Main) {
            val p = provider
            val o = owner
            var pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            if (spec.audio) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    if (p != null && o != null && camera != null) runCatching { bind(p, o) }
                    throw LookoutError("audio_permission", 403, "Microphone permission is missing. Grant the microphone permission to Lookout on the phone.")
                }
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(context)) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> started.complete(System.currentTimeMillis())
                    is VideoRecordEvent.Finalize -> {
                        val error = if (e.hasError()) "Recorder error ${e.error}${e.cause?.message?.let { ": $it" } ?: ""}" else null
                        recording = null
                        if (!started.isCompleted) started.completeExceptionally(LookoutError("recording_failed", 500, error ?: "The recording did not start"))
                        // Back to the normal use cases, so stills work again.
                        // Check again on the main thread: the session can close the camera first.
                        if (p != null && o != null) scope.launch(Dispatchers.Main) { if (camera != null) runCatching { bind(p, o) } }
                        onEnd(RecordingEnd(error))
                    }
                }
            }
        }
        val t0 = try {
            withTimeout(START_TIMEOUT_MS) { started.await() }
        } catch (e: Exception) {
            recording?.stop()
            throw (e as? LookoutError) ?: LookoutError("recording_failed", 500, "The recording did not start: ${e.message}")
        }
        val res = vc.resolutionInfo
        val size = res?.resolution
        val turned = res != null && res.rotationDegrees % 180 != 0
        val w = size?.let { if (turned) it.height else it.width } ?: spec.resolution.width
        val h = size?.let { if (turned) it.width else it.height } ?: spec.resolution.height
        return RecordingStarted(t0, w, h, spec.fps, TIME_PRECISION_MS, "mp4", videoCrop, audio = spec.audio)
    }

    override suspend fun stopRecording() {
        recording?.stop()
    }

    /**
     * Computes where the bound video frame lies inside the upright analysis (look) frame in 0..1 coordinates.
     * Maps the video crop rect through CameraX's sensor-to-buffer transforms when available, falling back
     * to the Camera2 aspect-ratio center-crop rule on the real stream resolutions.
     */
    @SuppressLint("RestrictedApi")
    private fun computeVideoCrop(analysis: ImageAnalysis, vc: VideoCapture<Recorder>): NRect {
        val aRes = analysis.resolutionInfo ?: return NRect.FULL
        val vRes = vc.resolutionInfo ?: return NRect.FULL
        val aSize = aRes.resolution
        val vSize = vRes.resolution
        if (aSize.width <= 0 || aSize.height <= 0 || vSize.width <= 0 || vSize.height <= 0) return NRect.FULL

        val vRect = if (!vRes.cropRect.isEmpty) RectF(vRes.cropRect) else RectF(0f, 0f, vSize.width.toFloat(), vSize.height.toFloat())
        val vMat = vc.sensorToBufferTransformMatrix
        val aMat = analysis.sensorToBufferTransformMatrix
        val vToSensor = Matrix()
        val hasTransforms = !(vMat.isIdentity && aMat.isIdentity && vSize != aSize) && vMat.invert(vToSensor)

        val u0: Double
        val v0: Double
        val u1: Double
        val v1: Double
        if (hasTransforms) {
            val mapped = RectF(vRect)
            Matrix(vToSensor).apply { postConcat(aMat) }.mapRect(mapped)
            u0 = (mapped.left.toDouble() / aSize.width).coerceIn(0.0, 1.0)
            v0 = (mapped.top.toDouble() / aSize.height).coerceIn(0.0, 1.0)
            u1 = (mapped.right.toDouble() / aSize.width).coerceIn(0.0, 1.0)
            v1 = (mapped.bottom.toDouble() / aSize.height).coerceIn(0.0, 1.0)
        } else {
            val va = vRect.width().toDouble() / vRect.height()
            val aa = aSize.width.toDouble() / aSize.height
            if (va > aa) {
                val h = aa / va
                u0 = 0.0; u1 = 1.0; v0 = (1.0 - h) / 2.0; v1 = v0 + h
            } else if (va < aa) {
                val w = va / aa
                u0 = (1.0 - w) / 2.0; u1 = u0 + w; v0 = 0.0; v1 = 1.0
            } else {
                u0 = 0.0; u1 = 1.0; v0 = 0.0; v1 = 1.0
            }
        }
        if (u1 - u0 <= NRect.EPS || v1 - v0 <= NRect.EPS) return NRect.FULL
        return NRect(u0, v0, u1 - u0, v1 - v0).rotated(aRes.rotationDegrees)
    }

    @SuppressLint("RestrictedApi")
    private suspend fun bind(p: ProcessCameraProvider, owner: LifecycleOwner, videoCapture: VideoCapture<Recorder>? = null) {
        p.unbindAll()
        val selector = desired.lens?.let { id ->
            CameraSelector.Builder().addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }.build()
        } ?: CameraSelector.DEFAULT_BACK_CAMERA

        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
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
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector(Size(4000, 3000)))
            .build()
        val cam = if (videoCapture != null) p.bindToLifecycle(owner, selector, analysis, videoCapture) else p.bindToLifecycle(owner, selector, analysis, still)
        camera = cam
        this.analysis = analysis
        capture = if (videoCapture != null) null else still
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
        val exposedAt = exposureWallMs(image.imageInfo.timestamp)
        val bitmap: Bitmap = image.use { AndroidImageOps.rotate(it.toBitmap(), it.imageInfo.rotationDegrees) }
        frames?.trySend(RawFrame(BitmapImg(bitmap), exposedAt, meta.copy(tilt = tilt())))
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
        val deg = cam.cameraInfo.getSensorRotationDegrees(targetRotation)
        when (focus?.mode) {
            "point" -> {
                val (sx, sy) = toSensor(focus.x!!, focus.y!!, deg)
                val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(sx.toFloat(), sy.toFloat())
                runCatching { withTimeout(3_000) { control.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build()).await() } }
            }
            "roi" -> {
                val r = focus.region ?: return
                val (sx, sy) = toSensor(r.x + r.w / 2.0, r.y + r.h / 2.0, deg)
                val size = min(r.w, r.h).toFloat().coerceIn(0.05f, 1f)
                val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(sx.toFloat(), sy.toFloat(), size)
                runCatching {
                    withTimeout(3_000) {
                        control.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()).await()
                    }
                }
            }
            "auto" -> runCatching { control.cancelFocusAndMetering().await() }
        }
    }

    /** Maps an upright image point to the sensor orientation that metering points use. */
    private fun toSensor(x: Double, y: Double, deg: Int = rotation): Pair<Double, Double> = when (deg) {
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
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull { it > 0f }?.toDouble()?.let { round(it * 1000) / 1000.0 }
        val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val fov = if (focal != null && sensor != null) {
            ScaleCalibration.lensFovDeg(
                sensor.width.toDouble(),
                sensor.height.toDouble(),
                focal,
                info.getSensorRotationDegrees(targetRotation),
            )
        } else null
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
            focalLengthMm = focal,
            fovHDeg = fov?.first,
            fovVDeg = fov?.second,
        )
    }
}

private val RECORD_FPS = listOf(30, 60)
private const val AE_SETTLE_MS = 1_500L
private const val START_TIMEOUT_MS = 5_000L

/** The Start event comes from the muxer, some tens of ms after the first frame. */
private const val TIME_PRECISION_MS = 50

/** A sensor timestamp older than this is in another clock base, so it is not used. */
private const val MAX_EXPOSURE_AGE_MS = 2_000L

/**
 * The wall time of the exposure with sensor timestamp [sensorNs]. Most phones stamp frames with the
 * elapsedRealtime clock. Some use another clock, and then the age is out of range and the time is now.
 */
private fun exposureWallMs(sensorNs: Long): Long {
    val now = System.currentTimeMillis()
    val ageMs = (SystemClock.elapsedRealtimeNanos() - sensorNs) / 1_000_000
    return if (ageMs in 0..MAX_EXPOSURE_AGE_MS) now - ageMs else now
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
