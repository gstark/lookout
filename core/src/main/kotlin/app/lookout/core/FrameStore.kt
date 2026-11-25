package app.lookout.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A downscaled JPEG kept in the history ring buffer. */
class BufferedFrame(val id: Long, val capturedAtMs: Long, val jpeg: ByteArray)

/** A new analysis frame with its motion score against the frame before it. */
class AnalyzedFrame(val frame: Frame, val previous: Frame?, val motion: Double)

/**
 * A frame found by id, at the best resolution the store has. [source] is still, analysis or history (the 640 px ring buffer).
 * [lens] and [zoom] are the camera state it was taken with, null for a history frame, which does not record them.
 */
class StoredFrame(
    val id: Long,
    val capturedAtMs: Long,
    val tilt: Tilt?,
    val lens: String?,
    val zoom: Double?,
    val source: String,
    private val loadImage: suspend () -> Img,
    private val loadJpeg: suspend () -> ByteArray,
) {
    suspend fun image(): Img = loadImage()
    suspend fun jpeg(): ByteArray = loadJpeg()
}

/**
 * A frame that a result returned, kept as a JPEG at its own resolution. [encode] makes the JPEG and calls
 * its second argument when it is ready. Until then, the decoded image serves.
 */
private class KeptFrame(frame: Frame, encode: (Img, (ByteArray) -> Unit) -> Deferred<ByteArray>) {
    private val id = frame.id
    private val capturedAtMs = frame.capturedAtMs
    private val tilt = frame.camera.tilt
    private val lens = frame.camera.lens
    private val zoom = frame.camera.zoom
    private val source = if (frame.still) "still" else "analysis"
    @Volatile private var pending: Img? = frame.image

    /** The memory it holds: the decoded image until the JPEG is ready, then the JPEG. */
    @Volatile var bytes: Long = frame.image.width.toLong() * frame.image.height * 4
        private set

    private val jpeg: Deferred<ByteArray> = encode(frame.image) {
        bytes = it.size.toLong()
        pending = null
    }

    fun stored(decode: (ByteArray) -> Img) =
        StoredFrame(id, capturedAtMs, tilt, lens, zoom, source, { pending ?: decode(jpeg.await()) }, { jpeg.await() })
}

/**
 * Keeps the latest analysis frame, a ring buffer of downscaled JPEGs, and the frames that results returned.
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
    keepBytes: Long = Retention.FRAME_BYTES,
) {
    // A kept frame does not hold the decoded image, so frames that results return no longer pin a full still each.
    private val kept = RetainedCache<Long, KeptFrame>(clock, keepBytes) { it.bytes }
    private val encoder = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingEncodes = AtomicInteger(0)

    private val nextId = AtomicLong(1)
    private val _latest = MutableStateFlow<Frame?>(null)
    val latest: StateFlow<Frame?> = _latest.asStateFlow()

    private val _frames = MutableSharedFlow<AnalyzedFrame>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<AnalyzedFrame> = _frames.asSharedFlow()

    private val lock = Any()
    private val buffer = ArrayDeque<BufferedFrame>()
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

    /** Gives a full-resolution still an id. A result that returns it calls [keep]. */
    fun registerStill(raw: RawFrame): Frame =
        Frame(nextId.getAndIncrement(), raw.image, raw.capturedAtMs, raw.camera, detector.thumb(raw.image), still = true)

    /** Keeps [frame] for at least [Retention.KEEP_MS], because a result returned it. A second call only renews the time. */
    fun keep(frame: Frame) {
        kept.put(frame.id, kept.get(frame.id) ?: KeptFrame(frame, ::encodeKept))
    }

    /**
     * Encodes [img] in the background, so the result is not slower. Each waiting encode holds a decoded image,
     * so when [MAX_PENDING_ENCODES] already wait, it encodes on this thread and the image goes before it returns.
     */
    private fun encodeKept(img: Img, done: (ByteArray) -> Unit): Deferred<ByteArray> {
        if (pendingEncodes.incrementAndGet() > MAX_PENDING_ENCODES) {
            pendingEncodes.decrementAndGet()
            return CompletableDeferred(ops.encodeJpeg(img, KEEP_QUALITY).also(done))
        }
        return encoder.async {
            try {
                ops.encodeJpeg(img, KEEP_QUALITY).also(done)
            } finally {
                pendingEncodes.decrementAndGet()
            }
        }
    }

    /** The frame [id]: a kept frame, the latest frame, or one from the history buffer. Throws when none has it. */
    fun find(id: Long): StoredFrame {
        kept.get(id)?.let { return it.stored(ops::decode) }
        _latest.value?.takeIf { it.id == id }?.let { f ->
            return StoredFrame(f.id, f.capturedAtMs, f.camera.tilt, f.camera.lens, f.camera.zoom, "analysis", { f.image }, { ops.encodeJpeg(f.image, KEEP_QUALITY) })
        }
        bufferedById(id)?.let { b -> return StoredFrame(b.id, b.capturedAtMs, null, null, null, "history", { ops.decode(b.jpeg) }, { b.jpeg }) }
        if (kept.droppedForMemory(id)) throw LookoutError("frame_dropped", 404, "Frame $id was ${Retention.DROPPED_HINT}.")
        throw LookoutError("frame_not_found", 404, "Frame $id is no longer in memory. A frame that a result returned stays for ${Retention.URL_LIFE_MS / 60_000} minutes.")
    }

    suspend fun await(timeoutMs: Long, predicate: (Frame) -> Boolean): Frame? =
        withTimeoutOrNull(timeoutMs) { latest.filterNotNull().first(predicate) }

    fun buffered(): List<BufferedFrame> = synchronized(lock) { buffer.toList() }

    private fun bufferedById(id: Long): BufferedFrame? = synchronized(lock) { buffer.firstOrNull { it.id == id } }

    fun bufferSpan(): Pair<Long, Long>? = synchronized(lock) {
        if (buffer.isEmpty()) null else buffer.first().capturedAtMs to buffer.last().capturedAtMs
    }

    /** Drops the buffer and the latest frame, for example when the camera closes in cold mode. */
    fun clearLatest() {
        _latest.value = null
    }

    fun clearBuffer() = synchronized(lock) { buffer.clear() }

    fun nowMs() = clock.now()

    companion object {
        /** The JPEG quality of a kept frame. High, so a crop of it shows fine detail. */
        const val KEEP_QUALITY = 92

        /** Background encodes of kept frames that can wait at a time. Each holds a decoded image, 48 MB for a 12 MP still. */
        const val MAX_PENDING_ENCODES = 2
    }
}
