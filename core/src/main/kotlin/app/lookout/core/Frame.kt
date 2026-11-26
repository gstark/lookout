package app.lookout.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    val tilt: Tilt? = null,
    /** The highest turn rate of the phone in rad/s during the exposure, from the gyroscope. Null without one. */
    val rateRadS: Double? = null,
)

/**
 * Which way the camera points, from the gravity sensor, in the axes of the frame as delivered.
 * [pitchDeg] is the elevation of the lens axis: 0 looks level, -90 looks straight down, +90 straight up.
 * [rollDeg] is the angle from frame up to true up, positive clockwise: a true vertical line shows turned
 * clockwise by [rollDeg]. Roll is unstable when the lens looks nearly straight down or up.
 */
@Serializable
data class Tilt(val pitchDeg: Double, val rollDeg: Double) {
    /** The angle in degrees between the lens attitudes of this tilt and [other]. */
    fun angleTo(other: Tilt): Double {
        val a = unit()
        val b = other.unit()
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(dot))
    }

    /** True up in frame axes (x right, y up, z along the lens axis). */
    private fun unit(): DoubleArray {
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        return doubleArrayOf(kotlin.math.cos(p) * kotlin.math.sin(r), kotlin.math.cos(p) * kotlin.math.cos(r), kotlin.math.sin(p))
    }

    companion object {
        /**
         * Makes a tilt from the up vector (the gravity sensor reading) in frame axes: x to the right of the
         * frame, y to its top, z out of the phone screen. [frontLens] is true for a lens on the screen side.
         * Null when the vector is too short to have a direction.
         */
        fun fromUp(x: Double, y: Double, z: Double, frontLens: Boolean): Tilt? {
            val g = kotlin.math.sqrt(x * x + y * y + z * z)
            if (g < 0.1) return null
            // A back lens looks along -z. A front frame is not mirrored, so its right is the screen's left.
            val axis = if (frontLens) z else -z
            val right = if (frontLens) -x else x
            val pitch = Math.toDegrees(kotlin.math.asin((axis / g).coerceIn(-1.0, 1.0)))
            val roll = Math.toDegrees(kotlin.math.atan2(right, y))
            return Tilt(round1(pitch), round1(roll))
        }

        // Adding 0.0 turns -0.0 into 0.0.
        private fun round1(v: Double) = kotlin.math.round(v * 10) / 10 + 0.0
    }
}

/** A frame as the source delivers it, before the store gives it an id. */
class RawFrame(val image: Img, val capturedAtMs: Long, val camera: CameraMeta)

/** A frame in the store. [gray] is the 160x120 thumbnail of the whole frame. [still] is true for a full-resolution still. */
class Frame(
    val id: Long,
    val image: Img,
    val capturedAtMs: Long,
    val camera: CameraMeta,
    val gray: GrayImage,
    val still: Boolean = false,
) {
    val brightness: Double get() = gray.mean / 255.0
}

/** Converts a focus distance in diopters (1 / metres) to rounded mm, or null at infinity (0 diopters). */
fun dioptersToMm(diopters: Double?): Int? {
    if (diopters == null || !diopters.isFinite() || diopters <= 0.0) return null
    return (1000.0 / diopters).roundToInt()
}

/** True when [focusDistance] (diopters) is at or within [margin] of the lens nearest focus [minFocusDistance]. */
fun focusAtLimit(focusDistance: Double?, minFocusDistance: Double?, margin: Double = 0.02): Boolean {
    if (focusDistance == null || minFocusDistance == null || minFocusDistance <= 0.0) return false
    return focusDistance >= minFocusDistance * (1.0 - margin)
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
    @SerialName("min_focus_mm") val minFocusMm: Int? = dioptersToMm(minFocusDistance),
    @SerialName("focal_length_mm") val focalLengthMm: Double? = null,
    @SerialName("fov_h_deg") val fovHDeg: Double? = null,
    @SerialName("fov_v_deg") val fovVDeg: Double? = null,
    val stillSize: String? = null,
)

@Serializable
data class FocusSetting(
    val mode: String,
    val distance: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
    val region: NRect? = null,
) {
    init {
        if (mode !in setOf("auto", "manual", "point", "roi")) throw LookoutError("invalid_focus", 400, "focus mode must be auto, manual, point or roi")
        if (mode == "manual" && distance == null) throw LookoutError("invalid_focus", 400, "manual focus needs a distance in diopters")
        if (mode == "point" && (x == null || y == null)) throw LookoutError("invalid_focus", 400, "point focus needs x and y")
    }

    companion object {
        /** Parses "auto", "manual:2.5", "point:0.4,0.6" or "roi". */
        fun parse(text: String): FocusSetting {
            val t = text.trim().lowercase()
            return when {
                t == "auto" -> FocusSetting("auto")
                t == "roi" -> FocusSetting("roi")
                t.startsWith("manual:") -> FocusSetting("manual", distance = t.removePrefix("manual:").toDoubleOrNull())
                t.startsWith("point:") -> {
                    val p = t.removePrefix("point:").split(",").map { it.trim().toDoubleOrNull() }
                    FocusSetting("point", x = p.getOrNull(0), y = p.getOrNull(1))
                }
                else -> throw LookoutError("invalid_focus", 400, "focus must be auto, manual:<diopters>, point:<x>,<y> or roi")
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
    /** A person holds the phone: auto exposure keeps the exposure at or under [HANDHELD_MAX_EXPOSURE_MS]. */
    val handheld: Boolean? = null,
) {
    fun merge(over: CameraSettings) = CameraSettings(
        lens = over.lens ?: lens,
        zoom = over.zoom ?: zoom,
        focus = over.focus ?: focus,
        aeLock = over.aeLock ?: aeLock,
        awbLock = over.awbLock ?: awbLock,
        torch = over.torch ?: torch,
        exposureComp = over.exposureComp ?: exposureComp,
        handheld = over.handheld ?: handheld,
    )

    val isEmpty: Boolean get() = this == CameraSettings()

    companion object {
        /** The longest exposure for a handheld shot: 1/30 s, from a frame rate range of at least 30 fps. */
        const val HANDHELD_MAX_EXPOSURE_MS = 34
    }
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

    /** The tilt now, from the gravity sensor, or null if this source has none. */
    fun tilt(): Tilt? = null

    /** The turn rate of the phone in rad/s over the last few tens of ms, from the gyroscope, or null if this source has none. */
    fun angularRate(): Double? = null

    /** What the bubble level on the phone shows now, in the axes of the frames, or null if this source has no gravity sensor. */
    fun level(): LevelReading? = null

    /** Sets which way frames face. Only a camera source acts on it. */
    fun setOrientation(orientation: FrameOrientation) {}

    /** What the current lens can record, or null if this source cannot record video. */
    fun recordingCaps(): RecordingCaps? = null

    /** True when this source has permission to record sound. */
    fun audioPermitted(): Boolean = true

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
