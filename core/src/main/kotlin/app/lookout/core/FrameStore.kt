package app.lookout.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** A downscaled JPEG kept in the history ring buffer. */
class BufferedFrame(val id: Long, val capturedAtMs: Long, val jpeg: ByteArray)

/** A new analysis frame with its motion score against the frame before it. */
class AnalyzedFrame(val frame: Frame, val previous: Frame?, val motion: Double)

/**
 * Keeps the latest analysis frame, a ring buffer of downscaled JPEGs and the last few stills.
 * Every frame gets a monotonically increasing id.
 */
class FrameStore(
    private val ops: ImageOps,
    private val detector: ChangeDetector,
    private val clock: Clock = Clock.SYSTEM,
    @Volatile var bufferIntervalMs: Long = 2_000,
    @Volatile var bufferMaxFrames: Int = 900,
    private val bufferLongEdge: Int = 640,
    @Volatile var motionThreshold: Double = 0.01,
) {
    private val nextId = AtomicLong(1)
    private val _latest = MutableStateFlow<Frame?>(null)
    val latest: StateFlow<Frame?> = _latest.asStateFlow()

    private val _frames = MutableSharedFlow<AnalyzedFrame>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<AnalyzedFrame> = _frames.asSharedFlow()

    private val lock = Any()
    private val buffer = ArrayDeque<BufferedFrame>()
    private val stills = ArrayDeque<Frame>()
    private var lastBufferedAt = 0L

    /** The time of the last frame whose motion score passed [motionThreshold]. */
    @Volatile var lastMotionAtMs: Long = 0
        private set

    fun ingest(raw: RawFrame): AnalyzedFrame {
        val frame = Frame(nextId.getAndIncrement(), raw.image, raw.capturedAtMs, raw.camera, detector.thumb(raw.image))
        val previous = _latest.value
        val motion = if (previous == null) 0.0 else detector.compare(previous.gray, frame.gray).score
        if (motion > motionThreshold) lastMotionAtMs = frame.capturedAtMs
        if (frame.capturedAtMs - lastBufferedAt >= bufferIntervalMs) {
            lastBufferedAt = frame.capturedAtMs
            val jpeg = ops.encodeJpeg(Compositor(ops).fit(frame.image, bufferLongEdge), 75)
            synchronized(lock) {
                buffer.addLast(BufferedFrame(frame.id, frame.capturedAtMs, jpeg))
                while (buffer.size > bufferMaxFrames) buffer.removeFirst()
            }
        }
        _latest.value = frame
        val analyzed = AnalyzedFrame(frame, previous, motion)
        _frames.tryEmit(analyzed)
        return analyzed
    }

    /** Gives a full-resolution still an id and keeps the last four. */
    fun registerStill(raw: RawFrame): Frame {
        val frame = Frame(nextId.getAndIncrement(), raw.image, raw.capturedAtMs, raw.camera, detector.thumb(raw.image))
        synchronized(lock) {
            stills.addLast(frame)
            while (stills.size > 4) stills.removeFirst()
        }
        return frame
    }

    suspend fun await(timeoutMs: Long, predicate: (Frame) -> Boolean): Frame? =
        withTimeoutOrNull(timeoutMs) { latest.filterNotNull().first(predicate) }

    fun buffered(): List<BufferedFrame> = synchronized(lock) { buffer.toList() }

    fun bufferedById(id: Long): BufferedFrame? = synchronized(lock) { buffer.firstOrNull { it.id == id } }

    fun stillById(id: Long): Frame? = synchronized(lock) { stills.firstOrNull { it.id == id } }

    fun bufferSpan(): Pair<Long, Long>? = synchronized(lock) {
        if (buffer.isEmpty()) null else buffer.first().capturedAtMs to buffer.last().capturedAtMs
    }

    /** Drops the buffer and the latest frame, for example when the camera closes in cold mode. */
    fun clearLatest() {
        _latest.value = null
    }

    fun clearBuffer() = synchronized(lock) { buffer.clear() }

    fun nowMs() = clock.now()
}
