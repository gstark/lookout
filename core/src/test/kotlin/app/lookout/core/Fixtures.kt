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

    /**
     * Smooth random texture: Gaussian blobs (sigma 2.5..6 px) on an area of [w] × [h] px. It is a function of
     * continuous coordinates, so a render at a fractional offset is an exact resampling.
     */
    class Blobs(seed: Int, private val w: Double = 640.0, private val h: Double = 480.0, count: Int = (w * h / 700).toInt(), private val amplitude: Double = 70.0) {
        private val cell = 24.0
        private val cols = (w / cell).toInt() + 1
        private val buckets = Array((cols) * ((h / cell).toInt() + 1)) { mutableListOf<DoubleArray>() }

        init {
            val r = java.util.Random(seed.toLong())
            repeat(count) {
                val b = doubleArrayOf(r.nextDouble() * w, r.nextDouble() * h, 2.5 + r.nextDouble() * 3.5, if (r.nextBoolean()) 1.0 else -1.0)
                buckets[(b[1] / cell).toInt() * cols + (b[0] / cell).toInt()] += b
            }
        }

        /** Luma 0..255 at (x, y); 128 outside the area. */
        fun at(x: Double, y: Double): Double {
            var v = 128.0
            val cx = (x / cell).toInt()
            val cy = (y / cell).toInt()
            for (j in cy - 1..cy + 1) for (i in cx - 1..cx + 1) {
                if (i < 0 || j < 0 || i >= cols || j * cols + i >= buckets.size) continue
                for (b in buckets[j * cols + i]) {
                    val dx = x - b[0]
                    val dy = y - b[1]
                    v += b[3] * amplitude * kotlin.math.exp(-(dx * dx + dy * dy) / (2 * b[2] * b[2]))
                }
            }
            return v.coerceIn(0.0, 255.0)
        }
    }

    /** A [width] × [height] gray image of [value] at the center of each pixel, in units of 1 / [scale] px. */
    fun render(width: Int, height: Int, scale: Double = 1.0, value: (Double, Double) -> Double): AwtImg {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val v = value((x + 0.5) / scale - 0.5, (y + 0.5) / scale - 0.5).toInt()
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return AwtImg(img)
    }

    /** [Blobs] of [seed] moved by ([dx], [dy]). The texture reaches [PAD] px past each side, so a shift brings in more of it. */
    fun texture(width: Int = 640, height: Int = 480, dx: Double = 0.0, dy: Double = 0.0, seed: Int = 1): AwtImg {
        val b = Blobs(seed, width + 2.0 * PAD, height + 2.0 * PAD)
        return render(width, height) { x, y -> b.at(x - dx + PAD, y - dy + PAD) }
    }

    const val PAD = 300

    /**
     * A photo of a painted plane: each pixel of a [width] × [height] gray image is the mean of [samples]² points,
     * each mapped through [toPlane] (image px to plane units) and painted by [paint] (luma 0..255). The area
     * sampling gives edges the sub-pixel position a camera would.
     */
    fun plane(width: Int, height: Int, toPlane: Homography, samples: Int = 4, paint: (Double, Double) -> Double): AwtImg {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val m = toPlane.m
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0.0
            for (j in 0 until samples) for (i in 0 until samples) {
                val u = x + (i + 0.5) / samples
                val v = y + (j + 0.5) / samples
                val d = m[6] * u + m[7] * v + m[8]
                sum += paint((m[0] * u + m[1] * v + m[2]) / d, (m[3] * u + m[4] * v + m[5]) / d)
            }
            val g = (sum / (samples * samples)).toInt().coerceIn(0, 255)
            img.setRGB(x, y, (g shl 16) or (g shl 8) or g)
        }
        return AwtImg(img)
    }

    /**
     * A perspective view: plane points (0, 0), ([w], 0), ([w], [h]), (0, [h]) land on [corners] in the image.
     * Returns the map from plane to image.
     */
    fun view(w: Double, h: Double, corners: List<Pt>): Homography =
        Homography.fit(listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h)), corners)

    /** A high-contrast checkerboard for sharpness tests. */
    fun checkerboard(width: Int = 640, height: Int = 480, cellsX: Int = 32, cellsY: Int = 24): AwtImg {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            val cy = y * cellsY / height
            for (x in 0 until width) {
                val cx = x * cellsX / width
                val v = if ((cx + cy) % 2 == 0) 230 else 25
                img.setRGB(x, y, Color(v, v, v).rgb)
            }
        }
        return AwtImg(img)
    }

    /** Separable box filter of [radius] pixels, to simulate defocus or motion blur in tests. */
    fun boxBlur(src: AwtImg, radius: Int): AwtImg {
        val w = src.width
        val h = src.height
        val inPx = src.image.getRGB(0, 0, w, h, null, 0, w)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val size = 2 * radius + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dx in -radius..radius) {
                    val c = inPx[row + (x + dx).coerceIn(0, w - 1)]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                tmp[row + x] = ((r / size) shl 16) or ((g / size) shl 8) or (b / size)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dy in -radius..radius) {
                    val c = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                out[y * w + x] = ((r / size) shl 16) or ((g / size) shl 8) or (b / size)
            }
        }
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, w, h, out, 0, w)
        return AwtImg(bi)
    }

    fun core(
        source: FrameSource,
        clock: Clock = Clock.SYSTEM,
        device: DeviceState = DeviceState(charging = true, batteryPct = 80),
        recordingsDir: java.io.File? = null,
        textReader: TextReader? = null,
        clipDecoder: ClipDecoder = LkClip.Decoder(AwtImageOps),
    ): LookoutCore = LookoutCore(
        AwtImageOps, source, MemoryKeyValueStore(), MemoryBlobStore(), { device }, { true }, clock,
        recordingsDir = recordingsDir, clipDecoder = clipDecoder, textReader = textReader,
    )
}

/** A source the test drives frame by frame. */
class PushSource(
    private val still: Img? = null,
    private val clock: Clock = Clock.SYSTEM,
    private val onOpen: () -> Unit = {},
    private val onStill: () -> Unit = {},
    private val nextStill: (() -> Img)? = null,
    private val lensList: List<LensInfo> = listOf(LensInfo("back", "back"), LensInfo("front", "front")),
) : FrameSource {
    @Volatile private var sink: ((RawFrame) -> Unit)? = null
    @Volatile var last: Img? = null
    var applied = CameraSettings()
    val appliedHistory = mutableListOf<CameraSettings>()
    @Volatile var appliedFps = 0.0
    var opens = 0
    var lastOrientation: FrameOrientation? = null
    @Volatile var tilt: Tilt? = null

    override fun lenses() = lensList
    override val isOpen get() = sink != null
    override suspend fun open(onFrame: (RawFrame) -> Unit) { onOpen(); sink = onFrame; opens++ }
    override suspend fun close() { sink = null }
    override suspend fun captureStill(): RawFrame {
        onStill()
        return RawFrame(nextStill?.invoke() ?: still ?: last ?: error("no frame yet"), clock.now(), CameraMeta(tilt = tilt))
    }
    override suspend fun apply(settings: CameraSettings): CameraSettings {
        appliedHistory += settings
        applied = applied.merge(settings)
        return applied
    }
    override fun setAnalysisFps(fps: Double) { appliedFps = fps }
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

/**
 * A test source that records a scripted clip: on start it writes [count] frames at [fps] made by [frame],
 * with exact frame times, so a test knows what each frame shows. Look frames show frame 0.
 */
class ScriptedClipSource(
    private val count: Int,
    private val fps: Int = 30,
    private val ops: ImageOps = AwtImageOps,
    private val frame: (Int) -> Img,
) : FrameSource {
    private val delegate = ReplayFrameSource(listOf(frame(0)), fps = 5.0, ops = ops)
    private var recEnd: ((RecordingEnd) -> Unit)? = null

    override fun lenses() = delegate.lenses()
    override val isOpen get() = delegate.isOpen
    override suspend fun open(onFrame: (RawFrame) -> Unit) = delegate.open(onFrame)
    override suspend fun close() = delegate.close()
    override suspend fun captureStill() = delegate.captureStill()
    override suspend fun apply(settings: CameraSettings) = delegate.apply(settings)
    override fun setAnalysisFps(fps: Double) = delegate.setAnalysisFps(fps)
    override fun recordingCaps() = RecordingCaps(listOf(fps), VideoResolution.entries.map { it.wire })

    override suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
        val first = frame(0)
        LkClip.Writer(file).use { w -> repeat(count) { w.write(it * 1_000_000L / fps, ops.encodeJpeg(frame(it), 95)) } }
        recEnd = onEnd
        return RecordingStarted(System.currentTimeMillis(), first.width, first.height, fps, 1, LkClip.EXTENSION)
    }

    override suspend fun stopRecording() {
        recEnd?.invoke(RecordingEnd())
        recEnd = null
    }
}

/**
 * A test source whose scene is [scene] of the ms since the last recording started (null before one). Analysis
 * frames sample it at the analysis rate and the clip at the recording fps, so both show the same moment at the
 * same wall time. With [preview] false, the camera opens but sends no analysis frames.
 */
class TimelineSource(
    private val preview: Boolean = true,
    private val ops: ImageOps = AwtImageOps,
    private val scene: (tMs: Long?) -> Img,
) : FrameSource {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @Volatile private var opened = false
    @Volatile private var intervalMs = 200L
    @Volatile private var startedAt: Long? = null
    @Volatile var appliedFps = 0.0
    private var job: kotlinx.coroutines.Job? = null
    private var recJob: kotlinx.coroutines.Job? = null
    private var recWriter: LkClip.Writer? = null
    private var recEnd: ((RecordingEnd) -> Unit)? = null

    private fun now(): Long? = startedAt?.let { System.currentTimeMillis() - it }

    override fun lenses() = listOf(LensInfo("back", "back"))
    override val isOpen get() = opened
    override suspend fun open(onFrame: (RawFrame) -> Unit) {
        opened = true
        if (!preview) return
        job = scope.launch {
            while (isActive) {
                onFrame(RawFrame(scene(now()), System.currentTimeMillis(), CameraMeta()))
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }
    override suspend fun close() {
        stopRecording()
        job?.cancel()
        job = null
        opened = false
    }
    override suspend fun captureStill() = RawFrame(scene(now()), System.currentTimeMillis(), CameraMeta())
    override suspend fun apply(settings: CameraSettings) = settings
    override fun setAnalysisFps(fps: Double) {
        appliedFps = fps
        intervalMs = (1000.0 / fps).toLong()
    }
    override fun recordingCaps() = RecordingCaps(listOf(30), VideoResolution.entries.map { it.wire })

    override suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
        val writer = LkClip.Writer(file)
        val start = System.currentTimeMillis()
        val intervalUs = 1_000_000L / spec.fps
        startedAt = start
        recWriter = writer
        recEnd = onEnd
        recJob = scope.launch {
            var last = -1L
            while (isActive) {
                val n = (System.currentTimeMillis() - start) * 1000 / intervalUs
                if (n > last) {
                    writer.write(n * intervalUs, ops.encodeJpeg(scene(n * intervalUs / 1000), 85))
                    last = n
                }
                kotlinx.coroutines.delay(maxOf(1L, ((last + 1) * intervalUs / 1000) - (System.currentTimeMillis() - start)))
            }
        }
        val first = scene(0)
        return RecordingStarted(start, first.width, first.height, spec.fps, 1, LkClip.EXTENSION)
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
}

/**
 * Wraps [inner] and counts the full passes (decodes to the end of the file). While [gated], a full pass waits
 * for [release] before it reads a frame.
 */
class GatedDecoder(private val inner: ClipDecoder, gated: Boolean = true) : ClipDecoder {
    private val gate = java.util.concurrent.CountDownLatch(if (gated) 1 else 0)
    val fullPasses = java.util.concurrent.atomic.AtomicInteger(0)
    val finishedPasses = java.util.concurrent.atomic.AtomicInteger(0)

    fun release() = gate.countDown()

    override fun decode(file: java.io.File, fromUs: Long, toUs: Long, request: (Long) -> FrameRequest?, onFrame: (Long, Img?) -> Unit) {
        if (toUs != Long.MAX_VALUE) return inner.decode(file, fromUs, toUs, request, onFrame)
        fullPasses.incrementAndGet()
        gate.await(60, java.util.concurrent.TimeUnit.SECONDS)
        inner.decode(file, fromUs, toUs, request, onFrame)
        finishedPasses.incrementAndGet()
    }

    override fun times(file: java.io.File) = inner.times(file)
}
