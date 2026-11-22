package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a list of fixture images as if they came from a camera. Frames loop, so a short
 * fixture set can feed a long test. [stillImages], when given, act as the full-resolution stills.
 * With [ops], it can also record: it writes the current image at the requested fps into a Lookout clip.
 */
class ReplayFrameSource(
    private val images: List<Img>,
    fps: Double = 2.0,
    private val clock: Clock = Clock.SYSTEM,
    private val loop: Boolean = true,
    private val stillImages: List<Img>? = null,
    private val ops: ImageOps? = null,
) : FrameSource {
    init {
        require(images.isNotEmpty()) { "Replay needs at least one image" }
        require(stillImages == null || stillImages.size == images.size) { "Stills must match frames one to one" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var index = 0
    @Volatile private var intervalMs = (1000.0 / fps).toLong()
    @Volatile private var settings = CameraSettings(lens = "replay", zoom = 1.0, focus = FocusSetting("auto"), torch = false)

    override fun lenses() = listOf(LensInfo(id = "replay", facing = "back", stillSize = "${images[0].width}x${images[0].height}"))

    override val isOpen: Boolean get() = job?.isActive == true

    override suspend fun open(onFrame: (RawFrame) -> Unit) {
        if (isOpen) return
        job = scope.launch {
            while (isActive) {
                onFrame(RawFrame(images[index], clock.now(), meta()))
                delay(intervalMs)
                if (index + 1 < images.size) index++ else if (loop) index = 0
            }
        }
    }

    override suspend fun close() {
        stopRecording()
        job?.cancel()
        job = null
    }

    private var recJob: Job? = null
    private var recWriter: LkClip.Writer? = null
    private var recEnd: ((RecordingEnd) -> Unit)? = null

    override fun recordingCaps(): RecordingCaps? =
        if (ops == null) null else RecordingCaps(listOf(30, 60), VideoResolution.entries.map { it.wire })

    override suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
        val ops = ops ?: return super.startRecording(spec, file, onEnd)
        if (!isOpen) throw LookoutError("camera_closed", 503, "Camera is not open")
        val comp = Compositor(ops)
        val writer = LkClip.Writer(file)
        val start = clock.now()
        val intervalUs = 1_000_000L / spec.fps
        recWriter = writer
        recEnd = onEnd
        // Like a camera, it keeps to the frame clock and skips a frame when it falls behind.
        recJob = scope.launch {
            var last = -1L
            while (isActive) {
                val n = (clock.now() - start) * 1000 / intervalUs
                if (n > last) {
                    writer.write(n * intervalUs, ops.encodeJpeg(comp.fit(images[index], spec.resolution.width), 80))
                    last = n
                }
                delay(maxOf(1L, ((last + 1) * intervalUs / 1000) - (clock.now() - start)))
            }
        }
        val img = images[index]
        return RecordingStarted(start, img.width, img.height, spec.fps, 1, LkClip.EXTENSION)
    }

    override suspend fun stopRecording() {
        val j = recJob ?: return
        recJob = null
        j.cancelAndJoin()
        recWriter?.close()
        recWriter = null
        recEnd?.invoke(RecordingEnd())
        recEnd = null
    }

    override suspend fun captureStill(): RawFrame {
        if (!isOpen) throw LookoutError("camera_closed", 503, "Camera is not open")
        val i = index
        return RawFrame(stillImages?.get(i) ?: images[i], clock.now(), meta())
    }

    override suspend fun apply(settings: CameraSettings): CameraSettings {
        if (settings.lens != null && settings.lens != "replay") {
            throw LookoutError("lens_not_found", 400, "Replay source has only the lens 'replay'")
        }
        this.settings = this.settings.merge(settings)
        return this.settings
    }

    override fun setAnalysisFps(fps: Double) {
        intervalMs = (1000.0 / fps).toLong().coerceAtLeast(10)
    }

    private fun meta() = CameraMeta(lens = "replay", zoom = settings.zoom ?: 1.0, focusDistance = settings.focus?.distance)
}
