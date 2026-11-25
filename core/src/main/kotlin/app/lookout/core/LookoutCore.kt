package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Which way the camera frames face. The phone screen locks to the same orientation. */
@Serializable
enum class FrameOrientation(val wire: String) {
    @kotlinx.serialization.SerialName("portrait") PORTRAIT("portrait"),
    @kotlinx.serialization.SerialName("landscape") LANDSCAPE("landscape"),
}

@Serializable
data class LookoutSettings(
    val port: Int = 8080,
    val defaultWidth: Int = Compositor.DEFAULT_EDGE,
    val jpegQuality: Int = 80,
    val bufferIntervalMs: Long = 2_000,
    val bufferMinutes: Int = 30,
    /** Divides the change and motion thresholds. Above 1 is more sensitive. */
    val sensitivity: Double = 1.0,
    val powerMode: PowerMode? = null,
    val keepScreenOn: Boolean = false,
    val startOnLaunch: Boolean = true,
    /** Start serving after the phone restarts or the app updates. */
    val startOnBoot: Boolean = false,
    val clickSound: Boolean = false,
    /** Fixed, so frames, ROIs and baselines do not change with the way the screen happens to face. */
    val orientation: FrameOrientation = FrameOrientation.PORTRAIT,
    /** Accept connections that arrive over Tailscale, as well as the LAN. */
    val allowTailscale: Boolean = false,
    /** Accept pairing requests from agents. The person still approves each one on the phone. */
    val pairing: Boolean = true,
    /** Sleep after this many minutes with no agent call and no touch on the phone. 0 turns the idle timer off. */
    val sleepAfterMinutes: Int = 15,
)

/** A frame or capture that went to an agent. The phone UI flashes its indicator on each one. */
data class Served(val tool: String, val via: String, val remote: String?, val bytes: Int, val roi: String?, val ts: Long)

/** The one core that REST, MCP and the phone UI all sit on. */
class LookoutCore(
    val ops: ImageOps,
    val source: FrameSource,
    private val kv: KeyValueStore,
    blobs: BlobStore,
    val device: DeviceStatus,
    speaker: Speaker,
    val clock: Clock = Clock.SYSTEM,
    val deviceName: String = "lookout",
    /** Where recordings go. Null turns recording off. */
    recordingsDir: java.io.File? = null,
    /** Reads recorded clips. The default reads only the Lookout clip format of the replay source. */
    clipDecoder: ClipDecoder = LkClip.Decoder(ops),
    /** Reads text on the device. Null turns read off. */
    val textReader: TextReader? = null,
) {
    val detector = ChangeDetector(ops)
    val compositor = Compositor(ops)
    val events = EventLog(clock)
    val store = FrameStore(ops, detector, clock)
    val sleep = Sleep(clock)
    val session = CameraSession(source, store, device, events, clock) { sleep.asleep.value }
    val rois = RoiRegistry(kv)
    val baselines = Baselines(kv, blobs)
    val composites = CompositeStore(clock)
    val human = HumanIO(speaker, clock)
    val pointer = Pointer(clock)
    val captions = Captions(clock)
    val recordings = Recordings(source, session, device, events, detector, store.frames, clipDecoder, clock, recordingsDir) { store.motionThreshold }
    val startedAt = clock.now()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _settings = MutableStateFlow(
        kv.get(SETTINGS_KEY)?.let { runCatching { LookoutJson.decodeFromString(LookoutSettings.serializer(), it) }.getOrNull() } ?: LookoutSettings(),
    )
    val settings: StateFlow<LookoutSettings> = _settings.asStateFlow()

    private val _served = MutableSharedFlow<Served>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val served: SharedFlow<Served> = _served.asSharedFlow()

    internal val imageLimiter = TokenBucket(IMAGE_RATE, IMAGE_RATE, clock)
    internal val activeWaits = AtomicInteger(0)

    @Volatile var cameraSettings: CameraSettings = CameraSettings()
        internal set

    val tools = Tools(this)
    val capabilities: List<Capability> get() = tools.all

    init {
        applySettings(_settings.value)
    }

    fun capability(name: String): Capability =
        capabilities.firstOrNull { it.name == name } ?: throw LookoutError("unknown_tool", 404, "No tool named '$name'")

    fun start(scope: CoroutineScope) {
        session.start(scope)
        recordings.start(scope)
        scope.launch { detectLoop() }
        scope.launch {
            while (isActive) {
                delay(SLEEP_TICK_MS)
                runCatching { sleepIfDue() }
            }
        }
    }

    suspend fun stop() = session.stop()

    fun setPaused(value: Boolean) {
        _paused.value = value
        events.add("pause", buildJsonObject { put("paused", value) })
    }

    /** An agent call. It wakes Lookout and restarts the idle timer. */
    fun agentActive() {
        if (sleep.agentActive()) events.add("sleep", buildJsonObject { put("state", "awake"); put("by", SleepCause.AGENT.wire) })
    }

    /** A touch on the phone. It wakes Lookout and restarts the idle timer. */
    fun phoneActive() {
        if (sleep.phoneActive()) events.add("sleep", buildJsonObject { put("state", "awake"); put("by", SleepCause.PHONE.wire) })
    }

    /** What keeps Lookout awake now. Empty means it can sleep. */
    fun sleepBlockers(): List<String> = buildList {
        if (session.busy) add("camera_in_use")
        if (activeWaits.get() > 0) add("wait")
        if (recordings.isRecording) add("recording")
        if (human.current.value != null) add("prompt")
    }

    /** An agent is done. Lookout sleeps now when nothing else keeps it awake, or later when that ends. */
    suspend fun release() {
        sleep.release()
        captions.clear()
        pointer.clear()
        sleepIfDue()
    }

    internal suspend fun sleepIfDue() {
        val idleMs = settings.value.sleepAfterMinutes.takeIf { it > 0 }?.let { it * 60_000L }
        val cause = sleep.due(idleMs, sleepBlockers().isNotEmpty()) ?: return
        sleep.fallAsleep()
        // The light uses the most power, so it goes off first. It stays off after a wake.
        if (cameraSettings.torch == true) runCatching { applyCamera(CameraSettings(torch = false)) }
        events.add("sleep", buildJsonObject { put("state", "asleep"); put("by", cause.wire) })
        session.reconcile()
    }

    /** Applies persistent camera settings. The camera capability and the phone UI both use this. */
    suspend fun applyCamera(s: CameraSettings) {
        cameraSettings = source.apply(s)
        events.add("camera", buildJsonObject { put("applied", LookoutJson.encodeToJsonElement(CameraSettings.serializer(), s)) })
    }

    fun updateSettings(change: (LookoutSettings) -> LookoutSettings) {
        val next = change(_settings.value)
        _settings.value = next
        kv.put(SETTINGS_KEY, LookoutJson.encodeToString(LookoutSettings.serializer(), next))
        applySettings(next)
    }

    private fun applySettings(s: LookoutSettings) {
        store.bufferIntervalMs = s.bufferIntervalMs
        store.bufferMaxFrames = ((s.bufferMinutes * 60_000L) / s.bufferIntervalMs).toInt().coerceIn(10, 3_600)
        store.motionThreshold = MOTION_THRESHOLD / s.sensitivity
        session.modeSetting = s.powerMode
        source.setOrientation(s.orientation)
    }

    /** Wipes saved baselines and composites. The ring buffer is RAM-only and goes with the service. */
    fun wipeSavedMedia() {
        baselines.clear()
        events.add("wipe")
    }

    /** Flashes the phone's indicator. [log] adds a request event; a stream logs only its first frame. */
    fun recordServed(tool: String, ctx: CallContext, bytes: Int, roi: String?, log: Boolean = true) {
        val s = Served(tool, ctx.via, ctx.remote, bytes, roi, clock.now())
        _served.tryEmit(s)
        if (!log) return
        events.add("request", buildJsonObject {
            put("tool", tool)
            put("via", ctx.via)
            ctx.remote?.let { put("remote", it) }
            put("bytes", bytes)
            roi?.let { put("roi", it) }
        })
    }

    /**
     * Logs whole-frame motion and stillness, so history can pick tiles at events. Also logs a moved
     * event when the phone tilts more than [MOVED_DEG] from where it rested, because then the saved
     * ROIs and baselines no longer line up.
     */
    private suspend fun detectLoop() {
        var moving = false
        var rest: Tilt? = null
        var restLens: String? = null
        store.frames.collect { a ->
            val tilt = a.frame.camera.tilt
            if (tilt != null) {
                val from = rest
                if (from == null || restLens != a.frame.camera.lens) {
                    rest = tilt
                    restLens = a.frame.camera.lens
                } else {
                    val change = from.angleTo(tilt)
                    if (change > MOVED_DEG) {
                        rest = tilt
                        events.add("moved", buildJsonObject {
                            put("frame_id", a.frame.id)
                            put("pitch_deg", tilt.pitchDeg)
                            put("roll_deg", tilt.rollDeg)
                            put("change_deg", kotlin.math.round(change * 10) / 10)
                        })
                    }
                }
            }
            val threshold = store.motionThreshold
            if (a.motion > threshold && !moving) {
                moving = true
                events.add("motion", buildJsonObject {
                    put("frame_id", a.frame.id)
                    put("score", a.motion)
                })
            } else if (moving && a.frame.capturedAtMs - store.lastMotionAtMs > STILL_AFTER_MS) {
                moving = false
                events.add("still", buildJsonObject { put("frame_id", a.frame.id) })
            }
        }
    }

    companion object {
        const val API_VERSION = "1"
        const val SERVER_VERSION = "0.1.0"
        const val SETTINGS_KEY = "settings"
        const val MOTION_THRESHOLD = 0.01
        const val CHANGE_THRESHOLD = 0.02
        const val STILL_AFTER_MS = 5_000L

        /** A tilt change above this many degrees counts as a moved phone. */
        const val MOVED_DEG = 2.0
        const val IMAGE_RATE = 10.0
        const val MAX_WAITS = 4
        const val SLEEP_TICK_MS = 1_000L

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/** A token bucket: [capacity] tokens, refilled at [ratePerSecond]. */
class TokenBucket(private val capacity: Double, private val ratePerSecond: Double, private val clock: Clock) {
    private var tokens = capacity
    private var last = clock.now()

    @Synchronized
    fun tryTake(): Boolean {
        val now = clock.now()
        tokens = minOf(capacity, tokens + (now - last) / 1000.0 * ratePerSecond)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
