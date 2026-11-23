package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class PowerMode {
    @SerialName("warm") WARM,
    @SerialName("cold") COLD,
    @SerialName("auto") AUTO;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(text: String): PowerMode = entries.firstOrNull { it.wire == text.trim().lowercase() }
            ?: throw LookoutError("invalid_mode", 400, "mode must be warm, cold or auto")
    }
}

/**
 * Decides when the camera is open. Warm keeps it open with the ring buffer filling. Cold opens it
 * per request. Auto stays warm while a wait is active or a request arrived in the last 5 minutes.
 * A SEVERE thermal state forces cold behaviour.
 */
class CameraSession(
    private val source: FrameSource,
    private val store: FrameStore,
    private val status: DeviceStatus,
    private val events: EventLog,
    private val clock: Clock = Clock.SYSTEM,
) {
    /** Null means the default: warm while charging, cold on battery. */
    @Volatile var modeSetting: PowerMode? = null

    // A count, not a flag: when Android replaces the activity (after an update, or on a configuration
    // change), the new screen starts before the old one stops, and a flag would end up false.
    private val previewScreens = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while a phone screen shows the preview. The camera stays open and the analysis rate goes up. */
    val previewActive: Boolean get() = previewScreens.get() > 0

    /** A screen that shows the preview started. Call [previewStopped] once when it stops. */
    fun previewStarted() {
        previewScreens.incrementAndGet()
    }

    fun previewStopped() {
        previewScreens.updateAndGet { maxOf(0, it - 1) }
    }

    @Volatile var baseFps: Double = 2.0

    private val mutex = Mutex()
    private var users = 0
    private var holds = 0
    @Volatile private var lastRequestAt = 0L
    @Volatile private var lastReleaseAt = 0L
    private var failures = 0
    private var retryAt = 0L
    private var loop: Job? = null
    private var appliedFps = 0.0

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    @Volatile var lastError: String? = null
        private set

    fun effectiveMode(): PowerMode {
        val s = status.read()
        if (s.thermal >= Thermal.SEVERE) return PowerMode.COLD
        return modeSetting ?: if (s.charging == true) PowerMode.WARM else PowerMode.COLD
    }

    val thermalLimited: Boolean get() = status.read().thermal >= Thermal.SEVERE

    fun start(scope: CoroutineScope) {
        loop = scope.launch {
            while (isActive) {
                runCatching { reconcile() }
                delay(TICK_MS)
            }
        }
    }

    suspend fun stop() {
        loop?.cancel()
        mutex.withLock { closeCamera() }
    }

    /** Runs [block] with the camera open. */
    suspend fun <T> use(block: suspend () -> T): T {
        mutex.withLock {
            users++
            lastRequestAt = clock.now()
        }
        try {
            ensureOpen()
            return block()
        } finally {
            mutex.withLock {
                users--
                lastReleaseAt = clock.now()
            }
        }
    }

    /** Keeps the camera open (warm) for the length of [block], for waits and streams. */
    suspend fun <T> hold(block: suspend () -> T): T {
        mutex.withLock {
            holds++
            lastRequestAt = clock.now()
        }
        try {
            ensureOpen()
            return block()
        } finally {
            mutex.withLock {
                holds--
                lastReleaseAt = clock.now()
            }
        }
    }

    private suspend fun ensureOpen() {
        mutex.withLock {
            if (!source.isOpen) openCamera(force = true)
        }
        if (!source.isOpen) throw LookoutError("camera_unavailable", 503, "Camera could not open: ${lastError ?: "unknown error"}")
    }

    suspend fun reconcile() = mutex.withLock {
        val now = clock.now()
        val mode = effectiveMode()
        val wantWarm = when (mode) {
            PowerMode.WARM -> true
            PowerMode.AUTO -> holds > 0 || now - lastRequestAt < AUTO_WARM_MS
            PowerMode.COLD -> false
        }
        val busy = users > 0 || holds > 0
        val preview = previewActive && status.read().thermal < Thermal.SEVERE
        if ((wantWarm || busy || preview) && !source.isOpen && now >= retryAt) openCamera(force = false)
        if (!wantWarm && !busy && !preview && source.isOpen && now - lastReleaseAt > COLD_IDLE_MS) closeCamera()
        val fps = when {
            status.read().thermal >= Thermal.MODERATE -> baseFps / 2
            previewActive -> PREVIEW_FPS
            now - lastRequestAt < ACTIVE_WINDOW_MS -> ACTIVE_FPS
            else -> baseFps
        }
        if (fps != appliedFps) {
            source.setAnalysisFps(fps)
            appliedFps = fps
        }
        _open.value = source.isOpen
    }

    private suspend fun openCamera(force: Boolean) {
        try {
            source.open { raw -> store.ingest(raw) }
            failures = 0
            lastError = null
            events.add("camera", buildJsonObject { put("state", "open") })
        } catch (e: Exception) {
            failures++
            lastError = e.message ?: e.javaClass.simpleName
            retryAt = clock.now() + minOf(30_000L, 1_000L shl minOf(failures, 5))
            events.add("camera", buildJsonObject {
                put("state", "error")
                put("message", lastError)
                put("forced", force)
            })
        }
        _open.value = source.isOpen
    }

    /** Called by a source when it loses the camera (another app took it, or an error). */
    fun onCameraLost(reason: String) {
        lastError = reason
        failures++
        retryAt = clock.now() + minOf(30_000L, 1_000L shl minOf(failures, 5))
        events.add("camera", buildJsonObject {
            put("state", "lost")
            put("message", reason)
        })
    }

    private suspend fun closeCamera() {
        if (source.isOpen) {
            source.close()
            events.add("camera", buildJsonObject { put("state", "closed") })
        }
        store.clearLatest()
        _open.value = false
    }

    companion object {
        const val TICK_MS = 500L
        const val AUTO_WARM_MS = 5 * 60_000L
        const val COLD_IDLE_MS = 3_000L
        const val PREVIEW_FPS = 8.0

        /** After an agent request, frames arrive faster so the next fresh frame comes sooner. */
        const val ACTIVE_FPS = 5.0
        const val ACTIVE_WINDOW_MS = 30_000L
    }
}
