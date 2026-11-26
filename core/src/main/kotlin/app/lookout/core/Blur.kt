package app.lookout.core

import kotlin.math.atan
import kotlin.math.round
import kotlin.math.tan

/**
 * Motion blur from the turn of a handheld phone. A turn at [rateRadS] for one exposure moves the image
 * by rate × exposure × focal length in px. Hand tremor turns the phone; a sideways shift of the phone
 * moves the image much less at desk distances, so it is left out.
 */
object Blur {
    /** Below this rate (rad/s) the phone is on a stand. A hand does not stay this still for long. */
    const val HANDHELD_RAD_S = 0.006

    /** The predicted blur in px of an image [widthPx] wide, or null when a value is missing. */
    fun predictedPx(rateRadS: Double?, exposureNs: Long?, fovHDeg: Double?, zoom: Double, widthPx: Int): Double? {
        if (rateRadS == null || exposureNs == null || fovHDeg == null || fovHDeg <= 0.0 || widthPx <= 0) return null
        val halfFov = Math.toRadians(fovHDeg) / 2
        // Zoom narrows the field of view, so the same turn crosses more pixels.
        val zoomedHalf = atan(tan(halfFov) / zoom.coerceAtLeast(1e-3))
        val focalPx = widthPx / 2.0 / tan(zoomedHalf)
        return round(rateRadS * exposureNs / 1e9 * focalPx * 10) / 10
    }

    fun handheld(rateRadS: Double?) = rateRadS != null && rateRadS > HANDHELD_RAD_S
}
