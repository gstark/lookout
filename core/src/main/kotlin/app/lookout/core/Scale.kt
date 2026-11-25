package app.lookout.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Resolution-independent scale calibration for one plane at a fixed distance from the camera.
 * Stored as the physical width and height of the full look frame in millimetres.
 */
@Serializable
data class ScaleCalibration(
    val widthMm: Double,
    val heightMm: Double,
    val from: NPoint,
    val to: NPoint,
    val mm: Double,
    val lens: String,
    val zoom: Double,
    val tilt: Tilt? = null,
    val setAt: Long,
) {
    /**
     * Converts this calibration to mm per pixel for an image [imageWidthPx] pixels wide that covers
     * [cover] of the full look frame (in normalized 0..1 coordinates).
     */
    fun mmPerPx(imageWidthPx: Int, cover: NRect = NRect.FULL): Double {
        require(imageWidthPx > 0) { "imageWidthPx must be positive" }
        return widthMm * cover.w / imageWidthPx
    }

    /**
     * Converts this calibration to mm per pixel for an image of width [outputWidth] cropped to [roi]
     * from a full look frame of [sourceWidth] x [sourceHeight] pixels.
     */
    fun mmPerPxFor(sourceWidth: Int, sourceHeight: Int, roi: NRect?, outputWidth: Int): Double {
        val cover = if (roi == null || roi.isFull) NRect.FULL else {
            val px = roi.toPixels(sourceWidth, sourceHeight)
            NRect(0.0, 0.0, px.w.toDouble() / sourceWidth, px.h.toDouble() / sourceHeight)
        }
        return mmPerPx(outputWidth, cover)
    }

    /** Why this calibration does not apply to [currentLens] and [currentZoom], or null when it is valid. */
    fun staleReason(currentLens: String?, currentZoom: Double?): String? {
        if (currentLens != null && currentLens != lens) return "lens changed from $lens to $currentLens"
        if (currentZoom != null && abs(currentZoom - zoom) > CameraFields.ZOOM_STEP) {
            return "zoom changed from ${round3(zoom)} to ${round3(currentZoom)}"
        }
        return null
    }

    companion object {
        /**
         * Calibrates the scale from two normalized points [from] and [to] in a look frame of
         * [frameWidth] x [frameHeight] pixels and the known real distance [mm] between them.
         */
        fun fromPoints(
            from: NPoint,
            to: NPoint,
            mm: Double,
            frameWidth: Int,
            frameHeight: Int,
            lens: String,
            zoom: Double,
            tilt: Tilt?,
            setAt: Long,
        ): ScaleCalibration {
            if (!mm.isFinite() || mm <= 0.0) throw LookoutError("invalid_param", 400, "mm must be above 0")
            if (frameWidth <= 0 || frameHeight <= 0) throw LookoutError("invalid_param", 400, "Frame size must be positive")
            val distPx = hypot((to.x - from.x) * frameWidth, (to.y - from.y) * frameHeight)
            if (distPx <= 1e-6) throw LookoutError("invalid_param", 400, "from and to must be different points")
            val mmPerPx = mm / distPx
            return ScaleCalibration(
                widthMm = mmPerPx * frameWidth,
                heightMm = mmPerPx * frameHeight,
                from = from,
                to = to,
                mm = mm,
                lens = lens,
                zoom = zoom,
                tilt = tilt,
                setAt = setAt,
            )
        }

        /** Parses a point spec ("x,y" in 0..1 or a grid cell like "C3") using [Marks.place]. */
        fun parsePoint(spec: String, grid: GridSpec): NPoint {
            val s = spec.trim()
            if (s.contains(":") || s.count { it == ',' } > 1) {
                throw LookoutError("invalid_param", 400, "'$spec' must be a point \"x,y\" in 0..1 or a grid cell like C3")
            }
            return Marks.place(s, grid) {
                throw LookoutError("invalid_param", 400, "'$spec' must be a point \"x,y\" in 0..1 or a grid cell like C3")
            }.center
        }

        /**
         * Horizontal and vertical field of view in degrees for a 4:3 look frame from a sensor of
         * physical size [sensorWidthMm] x [sensorHeightMm] mm, [focalLengthMm] mm focal length, and
         * [sensorRotationDeg] rotation to upright. Returns null when any dimension is non-positive.
         */
        fun lensFovDeg(
            sensorWidthMm: Double,
            sensorHeightMm: Double,
            focalLengthMm: Double,
            sensorRotationDeg: Int,
        ): Pair<Double, Double>? {
            if (sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0 || focalLengthMm <= 0.0) return null
            val sLong = max(sensorWidthMm, sensorHeightMm)
            val sShort = min(sensorWidthMm, sensorHeightMm)
            val cLong = min(sLong, sShort * 4.0 / 3.0)
            val cShort = min(sShort, sLong * 3.0 / 4.0)
            val sw = if (sensorWidthMm >= sensorHeightMm) cLong else cShort
            val sh = if (sensorWidthMm >= sensorHeightMm) cShort else cLong
            val turned = sensorRotationDeg % 180 != 0
            val fw = if (turned) sh else sw
            val fh = if (turned) sw else sh
            val h = round(Math.toDegrees(2.0 * atan(fw / (2.0 * focalLengthMm))) * 10) / 10
            val v = round(Math.toDegrees(2.0 * atan(fh / (2.0 * focalLengthMm))) * 10) / 10
            return h to v
        }
    }
}

/** Single stored scale calibration, persisted as JSON in the key-value store. */
class ScaleStore(private val kv: KeyValueStore) {
    private val lock = Any()
    private var current: ScaleCalibration? = kv.get(KEY)?.let {
        runCatching { LookoutJson.decodeFromString(ScaleCalibration.serializer(), it) }.getOrNull()
    }

    fun get(): ScaleCalibration? = synchronized(lock) { current }

    fun set(scale: ScaleCalibration): ScaleCalibration = synchronized(lock) {
        current = scale
        kv.put(KEY, LookoutJson.encodeToString(ScaleCalibration.serializer(), scale))
        scale
    }

    fun clear(): Boolean = synchronized(lock) {
        val had = current != null
        current = null
        kv.put(KEY, null)
        had
    }

    companion object {
        const val KEY = "scale"
    }
}
