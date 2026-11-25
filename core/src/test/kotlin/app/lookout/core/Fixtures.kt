package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import java.awt.Color
import java.awt.image.BufferedImage
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertTrue

/** Fails when a number anywhere in [e] has more than 3 decimals. */
fun assertNoLongDecimals(e: JsonElement) {
    when (e) {
        is JsonObject -> e.values.forEach(::assertNoLongDecimals)
        is JsonArray -> e.forEach(::assertNoLongDecimals)
        is JsonPrimitive -> if (!e.isString) assertTrue(e.content.substringAfter('.', "").length <= 3, "${e.content} has more than 3 decimals")
    }
}

/** Synthetic scenes: a desk with a board and an LED, and a "hand" that can enter from the left. */
object Fixtures {
    fun scene(width: Int = 640, height: Int = 480, handX: Double? = null, ledOn: Boolean = false, noiseSeed: Int = 0): AwtImg {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(110, 110, 115)
        g.fillRect(0, 0, width, height)
        g.color = Color(20, 90, 40)
        g.fillRect(width / 4, height / 4, width / 2, height / 2)
        g.color = if (ledOn) Color(255, 40, 40) else Color(60, 20, 20)
        g.fillOval(width * 5 / 8, height * 3 / 8, width / 20, width / 20)
        if (handX != null) {
            g.color = Color(225, 180, 150)
            g.fillRect(0, height / 3, (handX * width).toInt(), height / 4)
        }
        g.dispose()
        if (noiseSeed != 0) {
            val r = java.util.Random(noiseSeed.toLong())
            for (i in 0 until width * height / 20) {
                val x = r.nextInt(width)
                val y = r.nextInt(height)
                val c = Color(img.getRGB(x, y))
                val d = r.nextInt(11) - 5
                img.setRGB(x, y, Color((c.red + d).coerceIn(0, 255), (c.green + d).coerceIn(0, 255), (c.blue + d).coerceIn(0, 255)).rgb)
            }
        }
        return AwtImg(img)
    }

    fun core(
        source: FrameSource,
        clock: Clock = Clock.SYSTEM,
        device: DeviceState = DeviceState(charging = true, batteryPct = 80),
        recordingsDir: java.io.File? = null,
        textReader: TextReader? = null,
    ): LookoutCore = LookoutCore(AwtImageOps, source, MemoryKeyValueStore(), MemoryBlobStore(), { device }, { true }, clock, recordingsDir = recordingsDir, textReader = textReader)
}

/** A source the test drives frame by frame. */
class PushSource(
    private val still: Img? = null,
    private val clock: Clock = Clock.SYSTEM,
    private val onOpen: () -> Unit = {},
    private val onStill: () -> Unit = {},
) : FrameSource {
    @Volatile private var sink: ((RawFrame) -> Unit)? = null
    @Volatile var last: Img? = null
    var applied = CameraSettings()
    var opens = 0
    var lastOrientation: FrameOrientation? = null
    @Volatile var tilt: Tilt? = null

    override fun lenses() = listOf(LensInfo("back", "back"), LensInfo("front", "front"))
    override val isOpen get() = sink != null
    override suspend fun open(onFrame: (RawFrame) -> Unit) { onOpen(); sink = onFrame; opens++ }
    override suspend fun close() { sink = null }
    override suspend fun captureStill(): RawFrame {
        onStill()
        return RawFrame(still ?: last ?: error("no frame yet"), clock.now(), CameraMeta(tilt = tilt))
    }
    override suspend fun apply(settings: CameraSettings): CameraSettings { applied = applied.merge(settings); return applied }
    override fun setAnalysisFps(fps: Double) {}
    override fun setOrientation(orientation: FrameOrientation) { lastOrientation = orientation }
    override fun tilt(): Tilt? = tilt

    fun push(img: Img, at: Long = clock.now(), meta: CameraMeta = CameraMeta(tilt = tilt)) {
        last = img
        sink?.invoke(RawFrame(img, at, meta))
    }
}

/**
 * A test source whose look frames are [images], while recorded video is cropped to [videoCrop] of each
 * look frame (simulating a 16:9 video stream inside a 4:3 look frame).
 */
class CroppedReplaySource(
    private val images: List<Img>,
    private val videoCrop: NRect = NRect.FULL,
    fps: Double = 5.0,
    private val clock: Clock = Clock.SYSTEM,
    private val ops: ImageOps = AwtImageOps,
) : FrameSource {
    private val delegate = ReplayFrameSource(images, fps = fps, clock = clock, ops = ops)
    private val comp = Compositor(ops)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var recJob: kotlinx.coroutines.Job? = null
    private var recWriter: LkClip.Writer? = null
    private var recEnd: ((RecordingEnd) -> Unit)? = null

    override fun lenses() = delegate.lenses()
    override val isOpen get() = delegate.isOpen
    override suspend fun open(onFrame: (RawFrame) -> Unit) = delegate.open(onFrame)
    override suspend fun captureStill() = delegate.captureStill()
    override suspend fun apply(settings: CameraSettings) = delegate.apply(settings)
    override fun setAnalysisFps(fps: Double) = delegate.setAnalysisFps(fps)
    override fun recordingCaps() = delegate.recordingCaps()

    override suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
        if (!isOpen) throw LookoutError("camera_closed", 503, "Camera is not open")
        val cropped = images.map { comp.crop(it, videoCrop) }
        val writer = LkClip.Writer(file)
        val start = clock.now()
        val intervalUs = 1_000_000L / spec.fps
        recWriter = writer
        recEnd = onEnd
        recJob = scope.launch {
            var last = -1L
            while (isActive) {
                val n = (clock.now() - start) * 1000 / intervalUs
                if (n > last) {
                    val i = ((clock.now() - start) / 200L).toInt().mod(cropped.size)
                    writer.write(n * intervalUs, ops.encodeJpeg(cropped[i], 90))
                    last = n
                }
                kotlinx.coroutines.delay(maxOf(1L, ((last + 1) * intervalUs / 1000) - (clock.now() - start)))
            }
        }
        return RecordingStarted(start, cropped[0].width, cropped[0].height, spec.fps, 1, LkClip.EXTENSION, videoCrop)
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

    override suspend fun close() {
        stopRecording()
        delegate.close()
    }
}
