package app.lookout.core

import kotlinx.serialization.Serializable

/** Camera state attached to every frame. */
@Serializable
data class CameraMeta(
    val lens: String = "back",
    val zoom: Double = 1.0,
    val focusDistance: Double? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val whiteBalance: String? = null,
    val aeConverged: Boolean = true,
    val afConverged: Boolean = true,
)

/** A frame as the source delivers it, before the store gives it an id. */
class RawFrame(val image: Img, val capturedAtMs: Long, val camera: CameraMeta)

/** A frame in the store. [gray] is the 160x120 thumbnail of the whole frame. */
class Frame(
    val id: Long,
    val image: Img,
    val capturedAtMs: Long,
    val camera: CameraMeta,
    val gray: GrayImage,
) {
    val brightness: Double get() = gray.mean / 255.0
}

@Serializable
data class LensInfo(
    val id: String,
    val facing: String,
    val minZoom: Double = 1.0,
    val maxZoom: Double = 1.0,
    val hasFlash: Boolean = false,
    val manualFocus: Boolean = false,
    val minFocusDistance: Double? = null,
    val stillSize: String? = null,
)

@Serializable
data class FocusSetting(val mode: String, val distance: Double? = null, val x: Double? = null, val y: Double? = null) {
    init {
        if (mode !in setOf("auto", "manual", "point")) throw LookoutError("invalid_focus", 400, "focus mode must be auto, manual or point")
        if (mode == "manual" && distance == null) throw LookoutError("invalid_focus", 400, "manual focus needs a distance in diopters")
        if (mode == "point" && (x == null || y == null)) throw LookoutError("invalid_focus", 400, "point focus needs x and y")
    }

    companion object {
        /** Parses "auto", "manual:2.5" or "point:0.4,0.6". */
        fun parse(text: String): FocusSetting {
            val t = text.trim().lowercase()
            return when {
                t == "auto" -> FocusSetting("auto")
                t.startsWith("manual:") -> FocusSetting("manual", distance = t.removePrefix("manual:").toDoubleOrNull())
                t.startsWith("point:") -> {
                    val p = t.removePrefix("point:").split(",").map { it.trim().toDoubleOrNull() }
                    FocusSetting("point", x = p.getOrNull(0), y = p.getOrNull(1))
                }
                else -> throw LookoutError("invalid_focus", 400, "focus must be auto, manual:<diopters> or point:<x>,<y>")
            }
        }
    }
}

/** Settings the camera applies. A null field means "leave as it is". */
@Serializable
data class CameraSettings(
    val lens: String? = null,
    val zoom: Double? = null,
    val focus: FocusSetting? = null,
    val aeLock: Boolean? = null,
    val awbLock: Boolean? = null,
    val torch: Boolean? = null,
    val exposureComp: Int? = null,
) {
    fun merge(over: CameraSettings) = CameraSettings(
        lens = over.lens ?: lens,
        zoom = over.zoom ?: zoom,
        focus = over.focus ?: focus,
        aeLock = over.aeLock ?: aeLock,
        awbLock = over.awbLock ?: awbLock,
        torch = over.torch ?: torch,
        exposureComp = over.exposureComp ?: exposureComp,
    )

    val isEmpty: Boolean get() = this == CameraSettings()
}

/** The only component that touches camera hardware. A replay source can stand in for it. */
interface FrameSource {
    fun lenses(): List<LensInfo>

    /** Opens the camera and starts the analysis stream. Frames arrive on any thread. */
    suspend fun open(onFrame: (RawFrame) -> Unit)

    suspend fun close()

    val isOpen: Boolean

    /** A full-resolution still. The source must be open. */
    suspend fun captureStill(): RawFrame

    /** Applies the settings and returns the full set now in effect. */
    suspend fun apply(settings: CameraSettings): CameraSettings

    fun setAnalysisFps(fps: Double)

    /** Sets which way frames face. Only a camera source acts on it. */
    fun setOrientation(orientation: FrameOrientation) {}

    /** What the current lens can record, or null if this source cannot record video. */
    fun recordingCaps(): RecordingCaps? = null

    /**
     * Starts a video recording into [file] and returns when the encoder is rolling. The source calls
     * [onEnd] once, when the file is final (after [stopRecording], or on its own after an error).
     */
    suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted =
        throw LookoutError("recording_unsupported", 501, "This camera source cannot record video")

    /** Asks the encoder to finish. [startRecording]'s onEnd reports when the file is final. */
    suspend fun stopRecording() {}
}

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** An error with a stable code and an HTTP status. REST and MCP both report it as is. */
class LookoutError(val code: String, val status: Int, message: String) : Exception(message)
