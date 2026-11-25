package app.lookout.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
data class Mark(val tMs: Long, val label: String)

/** A stretch of the clip with motion. Times are ms from the first frame. */
@Serializable
data class ActivitySegment(val fromMs: Long, val toMs: Long, val peak: Double, val peakMs: Long)

@Serializable
data class RecordingInfo(
    val id: String,
    /** recording, processing, ready or failed. */
    val state: String,
    val file: String,
    val fps: Int,
    val resolution: String,
    val startedAt: Long,
    val plannedMs: Long,
    val label: String? = null,
    val roi: NRect? = null,
    val roiName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val precisionMs: Int = 0,
    val durationMs: Long? = null,
    val frames: Int? = null,
    val actualFps: Double? = null,
    val dropped: Int? = null,
    /** duration, stop, thermal or error. */
    val endedBy: String? = null,
    val error: String? = null,
    val marks: List<Mark> = emptyList(),
    val activity: List<ActivitySegment> = emptyList(),
    val sizeBytes: Long? = null,
    val videoCrop: NRect = NRect.FULL,
) {
    val done: Boolean get() = state == "ready" || state == "failed"
    val truncated: Boolean get() = endedBy == "thermal" || endedBy == "error"

    /** The time on this recording's timeline of the wall time [wallMs]. */
    fun tMs(wallMs: Long): Long = wallMs - startedAt

    /**
     * Converts a look-frame [roi] into video-frame coordinates using [videoCrop], clipping to the
     * video frame. Throws `invalid_param` when [roi] lies completely outside the video frame.
     */
    fun videoRoi(roi: NRect?): NRect? {
        if (roi == null) return null
        return roi.relativeTo(videoCrop) ?: throw LookoutError(
            "invalid_param", 400,
            "roi is outside the video frame. The video covers x=${round3(videoCrop.x)}..${round3(videoCrop.x + videoCrop.w)}, " +
                "y=${round3(videoCrop.y)}..${round3(videoCrop.y + videoCrop.h)} of the look frame.",
        )
    }
}

/** Per-frame data from the analysis pass. Times are presentation times in microseconds. */
@Serializable
data class ClipIndex(val ptsUs: List<Long>, val motion: List<Double>) {
    val firstUs: Long get() = ptsUs.firstOrNull() ?: 0L

    fun tMs(i: Int): Long = (ptsUs[i] - firstUs) / 1000

    /** The index of the frame nearest to [tMs]. */
    fun nearest(tMs: Long): Int {
        val target = firstUs + tMs * 1000
        var lo = 0
        var hi = ptsUs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ptsUs[mid] < target) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && target - ptsUs[lo - 1] < ptsUs[lo] - target) lo - 1 else lo
    }
}

/**
 * Video recordings: one at a time, recorded by the [FrameSource] into [dir], then analyzed in one
 * decode pass. Each recording keeps <id>.<ext> (the clip), <id>.json (its info) and <id>.index.json.
 */
class Recordings(
    private val source: FrameSource,
    private val session: CameraSession,
    private val device: DeviceStatus,
    private val events: EventLog,
    private val detector: ChangeDetector,
    val decoder: ClipDecoder,
    private val clock: Clock,
    private val dir: File?,
    private val motionThreshold: () -> Double,
) {
    private class Active(val id: String) {
        @Volatile var stopRequested = false
    }

    private val lock = Any()
    private val infos = LinkedHashMap<String, RecordingInfo>()
    private val indexes = HashMap<String, ClipIndex>()
    private var active: Active? = null
    private val _current = MutableStateFlow<RecordingInfo?>(null)

    /** The recording that is running or being analyzed, for the phone UI. */
    val current: kotlinx.coroutines.flow.StateFlow<RecordingInfo?> get() = _current
    private var starting = false
    private var nextNumber = 1
    private val version = MutableStateFlow(0L)
    private var scope: CoroutineScope? = null

    init {
        dir?.mkdirs()
        dir?.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".index.json") }?.forEach { f ->
            runCatching { LookoutJson.decodeFromString(RecordingInfo.serializer(), f.readText()) }.getOrNull()?.let { info ->
                // A recording that was running when the app stopped has no final file.
                val fixed = if (info.done) info else info.copy(state = "failed", error = "The app stopped during this recording")
                infos[info.id] = fixed
                if (fixed !== info) save(fixed)
            }
        }
        nextNumber = (infos.keys.mapNotNull { it.removePrefix("r").toIntOrNull() }.maxOrNull() ?: 0) + 1
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    val caps: RecordingCaps? get() = if (dir == null) null else source.recordingCaps()

    val activeId: String? get() = synchronized(lock) { active?.id }

    val isRecording: Boolean get() = synchronized(lock) { active != null || starting }

    fun list(): List<RecordingInfo> = synchronized(lock) { infos.values.sortedByDescending { it.startedAt } }

    fun get(id: String): RecordingInfo = synchronized(lock) { infos[id] }
        ?: throw LookoutError("recording_not_found", 404, "No recording '$id'")

    /** The given recording, or the newest one. */
    fun resolve(id: String?): RecordingInfo = if (id != null) get(id) else list().firstOrNull()
        ?: throw LookoutError("recording_not_found", 404, "There are no recordings yet")

    fun file(info: RecordingInfo): File = File(dir ?: throw unsupported(), info.file)

    fun index(info: RecordingInfo): ClipIndex {
        if (info.state != "ready") throw LookoutError("recording_not_ready", 409, "Recording ${info.id} is ${info.state}. Call wait_for(condition=recording_done) first.")
        synchronized(lock) { indexes[info.id] }?.let { return it }
        val f = File(dir, "${info.id}.index.json")
        val idx = runCatching { LookoutJson.decodeFromString(ClipIndex.serializer(), f.readText()) }.getOrNull()
            ?: throw LookoutError("recording_corrupt", 500, "The frame index of ${info.id} is missing")
        synchronized(lock) { indexes[info.id] = idx }
        return idx
    }

    /** Starts a recording and returns once the encoder is rolling. */
    suspend fun start(spec: RecordSpec, durationMs: Long, roi: RoiRef?, label: String?): RecordingInfo {
        val dir = dir ?: throw unsupported()
        val caps = source.recordingCaps() ?: throw unsupported()
        if (spec.fps !in caps.fps) throw LookoutError("invalid_param", 400, "fps ${spec.fps} is not supported on this lens. Supported: ${caps.fps.joinToString()}")
        if (spec.resolution.wire !in caps.resolutions) {
            throw LookoutError("invalid_param", 400, "resolution ${spec.resolution.wire} is not supported. Supported: ${caps.resolutions.joinToString()}")
        }
        val thermal = device.read().thermal
        if (thermal >= Thermal.SEVERE) throw LookoutError("thermal", 503, "The phone is too hot to record. Try again later.")
        if (thermal >= Thermal.MODERATE && spec.fps > 30) throw LookoutError("thermal", 503, "The phone is warm; record at 30 fps or less until it cools.")
        val scope = scope ?: throw LookoutError("not_started", 503, "Lookout is not running")
        val id = synchronized(lock) {
            active?.let { a ->
                throw LookoutError("recording_active", 409, "Recording ${a.id} is still running. Call record(action=stop) or wait_for(condition=recording_done).")
            }
            if (starting) throw LookoutError("recording_active", 409, "A recording is starting")
            starting = true
            "r${nextNumber++}"
        }
        try {
            makeRoom(dir, estimateBytes(spec, durationMs))
            val ready = CompletableDeferred<RecordingInfo>()
            scope.launch { run(id, spec, durationMs, roi, label, dir, ready) }
            return withTimeout(START_TIMEOUT_MS) { ready.await() }
        } finally {
            synchronized(lock) { starting = false }
        }
    }

    /** A mark to add: [tMs] on the timeline, or null for now. */
    class MarkAt(val label: String, val tMs: Long?)

    /**
     * Adds [wanted] to recording [id], all or none, and returns them. A mark for now needs a running recording.
     * A mark must lie on the recording: from 0 to now while it runs, else to its end.
     */
    fun mark(id: String, wanted: List<MarkAt>): List<Mark> {
        val (info, added) = synchronized(lock) {
            val info = infos[id] ?: throw LookoutError("recording_not_found", 404, "No recording '$id'")
            val elapsed = clock.now() - info.startedAt
            val live = info.state == "recording"
            val added = wanted.map { w ->
                if (w.tMs == null && !live) {
                    throw LookoutError("no_recording", 409, "Recording $id is not running. To mark it after the end, give at (an ISO time or t_ms).")
                }
                Mark(w.tMs ?: elapsed, w.label)
            }
            val end = if (live) elapsed else info.durationMs ?: elapsed
            added.firstOrNull { it.tMs !in 0..end }?.let { m ->
                throw LookoutError("invalid_param", 400,
                    "Mark '${m.label}' at t_ms=${m.tMs} is outside $id. Use t_ms 0..$end (${isoTime(info.startedAt)} to ${isoTime(info.startedAt + end)}).")
            }
            val next = info.copy(marks = (info.marks + added).sortedBy { it.tMs })
            infos[id] = next
            next to added
        }
        save(info)
        return added
    }

    /** Asks the running recording to stop early. wait_for(recording_done) reports the result. */
    fun stop(): RecordingInfo = synchronized(lock) {
        val a = active ?: throw LookoutError("no_recording", 409, "No recording is running")
        a.stopRequested = true
        infos[a.id]!!
    }

    /** Waits until [id] is ready or failed. Returns null on timeout. */
    suspend fun awaitDone(id: String, timeoutMs: Long): RecordingInfo? =
        withTimeoutOrNull(timeoutMs) {
            version.first { get(id).done }
            get(id)
        }

    private suspend fun run(id: String, spec: RecordSpec, durationMs: Long, roi: RoiRef?, label: String?, dir: File, ready: CompletableDeferred<RecordingInfo>) {
        val ended = CompletableDeferred<RecordingEnd>()
        var endedBy = "duration"
        val info: RecordingInfo = try {
            session.hold {
                val file = File(dir, "$id.tmp")
                val started = try {
                    source.startRecording(spec, file) { ended.complete(it) }
                } catch (e: Throwable) {
                    ready.completeExceptionally(e)
                    file.delete()
                    return@hold null
                }
                val first = RecordingInfo(
                    id = id, state = "recording", file = "$id.${started.extension}", fps = started.fps, resolution = spec.resolution.wire,
                    startedAt = started.startedAtMs, plannedMs = durationMs, label = label, roi = roi?.rect, roiName = roi?.name,
                    width = started.width, height = started.height, precisionMs = started.precisionMs, videoCrop = started.videoCrop,
                )
                try {
                    first.videoRoi(roi?.rect)
                } catch (e: Throwable) {
                    if (!ended.isCompleted) runCatching { source.stopRecording() }
                    withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { ended.await() }
                    file.delete()
                    ready.completeExceptionally(e)
                    return@hold null
                }
                val a = Active(id)
                synchronized(lock) {
                    infos[id] = first
                    active = a
                }
                save(first)
                bump()
                events.add("recording", buildJsonObject { put("id", id); put("state", "recording") })
                ready.complete(first)
                val deadline = clock.now() + durationMs
                while (true) {
                    if (ended.isCompleted) { endedBy = "error"; break }
                    if (a.stopRequested) { endedBy = "stop"; break }
                    if (device.read().thermal >= Thermal.SEVERE) { endedBy = "thermal"; break }
                    val left = deadline - clock.now()
                    if (left <= 0) break
                    delay(min(left, POLL_MS))
                }
                if (!ended.isCompleted) source.stopRecording()
                val end = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { ended.await() } ?: RecordingEnd("The encoder did not finish the file")
                file.renameTo(File(dir, first.file))
                val now = synchronized(lock) { infos[id]!! }
                if (end.error != null && endedBy == "duration") endedBy = "error"
                now.copy(state = "processing", endedBy = endedBy, error = end.error)
            } ?: return
        } catch (e: Throwable) {
            if (!ready.isCompleted) ready.completeExceptionally(e)
            synchronized(lock) { if (active?.id == id) active = null }
            val failed = synchronized(lock) { infos[id] }?.copy(state = "failed", error = e.message ?: e.javaClass.simpleName)
            if (failed != null) finish(failed)
            return
        }
        val stopped = synchronized(lock) {
            active = null
            withLatestMarks(info)
        }
        save(stopped)
        bump()
        finish(analyze(stopped))
    }

    private suspend fun analyze(info: RecordingInfo): RecordingInfo = withContext(Dispatchers.Default) {
        val f = file(info)
        if (!f.exists() || f.length() == 0L) return@withContext info.copy(state = "failed", error = info.error ?: "The recording produced no file")
        try {
            val pts = ArrayList<Long>()
            val motion = ArrayList<Double>()
            val lag = max(1, info.fps / 10)
            val recent = ArrayDeque<GrayImage>()
            val videoRoi = info.videoRoi(info.roi)
            decoder.decode(f, 0, Long.MAX_VALUE, { FrameRequest(videoRoi, ANALYSIS_EDGE) }) { p, img ->
                val g = detector.thumb(img!!)
                // Compare with the frame about 100 ms back, so slow motion at 60 fps still scores.
                motion += recent.firstOrNull()?.let { detector.compare(it, g).score } ?: 0.0
                recent.addLast(g)
                while (recent.size > lag) recent.removeFirst()
                pts += p
            }
            if (pts.isEmpty()) return@withContext info.copy(state = "failed", error = info.error ?: "The recording has no frames")
            val idx = ClipIndex(pts, motion)
            File(dir, "${info.id}.index.json").writeText(LookoutJson.encodeToString(ClipIndex.serializer(), idx))
            synchronized(lock) { indexes[info.id] = idx }
            val nominalUs = 1_000_000.0 / info.fps
            val spanUs = pts.last() - pts.first()
            var dropped = 0
            for (i in 1 until pts.size) dropped += max(0, ((pts[i] - pts[i - 1]) / nominalUs).roundToInt() - 1)
            info.copy(
                state = "ready",
                frames = pts.size,
                durationMs = ((spanUs + nominalUs) / 1000).roundToLong(),
                actualFps = if (spanUs > 0) ((pts.size - 1) * 1_000_000.0 / spanUs * 10).roundToInt() / 10.0 else info.fps.toDouble(),
                dropped = dropped,
                activity = segments(idx, motionThreshold()),
                sizeBytes = f.length(),
            )
        } catch (e: Exception) {
            info.copy(state = "failed", error = "Analysis failed: ${e.message ?: e.javaClass.simpleName}", sizeBytes = f.length())
        }
    }

    private fun finish(result: RecordingInfo) {
        val info = synchronized(lock) { withLatestMarks(result) }
        save(info)
        bump()
        events.add("recording_done", buildJsonObject {
            put("id", info.id)
            put("state", info.state)
            info.error?.let { put("error", it) }
        })
    }

    /** Stores [info] with the marks of the stored copy, which can gain marks while [info] is computed. Hold [lock]. */
    private fun withLatestMarks(info: RecordingInfo): RecordingInfo =
        info.copy(marks = infos[info.id]?.marks ?: info.marks).also { infos[info.id] = it }

    private fun bump() {
        _current.value = synchronized(lock) { infos.values.firstOrNull { !it.done } }
        version.value = version.value + 1
    }

    private fun save(info: RecordingInfo) {
        val d = dir ?: return
        File(d, "${info.id}.json").writeText(LookoutJson.encodeToString(RecordingInfo.serializer(), info))
    }

    /** Deletes the oldest recordings until [needed] more bytes fit under the cap and on the disk. */
    private fun makeRoom(dir: File, needed: Long) {
        fun used() = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val oldest = synchronized(lock) { infos.values.filter { it.done }.sortedBy { it.startedAt }.toMutableList() }
        while (oldest.isNotEmpty() && (used() + needed > MAX_BYTES || infos.size >= MAX_RECORDINGS)) delete(dir, oldest.removeAt(0))
        if (dir.usableSpace < needed + MIN_FREE_BYTES) {
            throw LookoutError("storage_full", 507, "Not enough free space on the phone for this recording (needs about ${needed / 1_000_000} MB)")
        }
    }

    private fun delete(dir: File, info: RecordingInfo) {
        File(dir, info.file).delete()
        File(dir, "${info.id}.json").delete()
        File(dir, "${info.id}.index.json").delete()
        synchronized(lock) {
            infos.remove(info.id)
            indexes.remove(info.id)
        }
    }

    private fun unsupported() = LookoutError("recording_unsupported", 501, "This camera source cannot record video")

    companion object {
        const val MAX_DURATION_MS = 60_000L
        const val DEFAULT_DURATION_MS = 10_000L
        const val MAX_BYTES = 2_000_000_000L
        const val MAX_RECORDINGS = 50
        const val MIN_FREE_BYTES = 300_000_000L
        const val START_TIMEOUT_MS = 15_000L
        const val FINALIZE_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
        const val ANALYSIS_EDGE = 320

        /** Gaps shorter than this join two motion segments. */
        const val SEGMENT_GAP_MS = 300L
        const val MAX_SEGMENTS = 20

        /** About 0.15 bits per pixel per frame, which is near what phone H.264 encoders use. */
        fun estimateBytes(spec: RecordSpec, durationMs: Long): Long =
            (spec.resolution.width.toLong() * spec.resolution.height * spec.fps * 0.15 / 8 * durationMs / 1000).toLong()

        /** Joins frames whose motion passes [threshold] into segments. Keeps the strongest [MAX_SEGMENTS] in time order. */
        fun segments(idx: ClipIndex, threshold: Double): List<ActivitySegment> {
            val out = mutableListOf<ActivitySegment>()
            var from = -1L
            var to = -1L
            var peak = 0.0
            var peakMs = 0L
            for (i in idx.motion.indices) {
                val m = idx.motion[i]
                if (m <= threshold) continue
                val t = idx.tMs(i)
                if (from >= 0 && t - to > SEGMENT_GAP_MS) {
                    out += ActivitySegment(from, to, round3(peak), peakMs)
                    from = -1
                }
                if (from < 0) { from = t; peak = 0.0 }
                to = t
                if (m > peak) { peak = m; peakMs = t }
            }
            if (from >= 0) out += ActivitySegment(from, to, round3(peak), peakMs)
            return out.sortedByDescending { it.peak }.take(MAX_SEGMENTS).sortedBy { it.fromMs }
        }
    }
}
