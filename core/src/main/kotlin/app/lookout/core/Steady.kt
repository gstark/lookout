package app.lookout.core

import kotlinx.coroutines.delay

/**
 * Waits until a handheld phone is steady enough for a sharp shot: the blur that the gyro rate predicts
 * for the current exposure stays at or under [GATE_PX] (in px of a full still) for [HOLD_MS].
 * [also] adds a condition that must hold at the same time, such as a green level.
 */
class SteadyGate(private val source: FrameSource, private val clock: Clock, private val latest: () -> Frame?) {
    /** [passed] is true when the conditions held, false at the time limit. [steady] is null when the phone has no gyro. [blurPx] is the last predicted blur. */
    class Result(val passed: Boolean, val steady: Boolean?, val waitedMs: Long, val blurPx: Double?)

    suspend fun await(lens: LensInfo?, maxMs: Long = MAX_MS, also: () -> Boolean = ALWAYS): Result {
        val start = clock.now()
        var okSince: Long? = null
        while (true) {
            val now = clock.now()
            val rate = source.angularRate()
            val px = rate?.let { predictPx(it, lens) }
            // Without a gyro there is nothing to wait for, unless another condition is given.
            if (rate == null && also === ALWAYS) return Result(true, null, now - start, null)
            val ok = (px == null || px <= GATE_PX) && also()
            if (ok) {
                val since = okSince ?: now.also { okSince = it }
                if (now - since >= HOLD_MS) return Result(true, if (rate == null) null else true, now - start, px)
            } else {
                okSince = null
            }
            if (now - start >= maxMs) return Result(false, if (rate == null) null else false, now - start, px)
            delay(POLL_MS)
        }
    }

    /** The blur in px of a full still that the gyro predicts now, or null without a gyro. The phone shows it as a steadiness light. */
    fun predictPx(lens: LensInfo?): Double? = source.angularRate()?.let { predictPx(it, lens) }

    private fun predictPx(rate: Double, lens: LensInfo?): Double? {
        val camera = latest()?.camera
        val width = lens?.stillSize?.substringBefore('x')?.toIntOrNull() ?: DEFAULT_WIDTH
        return Blur.predictedPx(rate, camera?.exposureTimeNs ?: DEFAULT_EXPOSURE_NS, lens?.fovHDeg ?: DEFAULT_FOV_DEG, camera?.zoom ?: 1.0, width)
    }

    companion object {
        /** The gate in px of a full still. */
        const val GATE_PX = 3.0
        const val HOLD_MS = 100L
        const val MAX_MS = 3_000L
        const val POLL_MS = 10L
        private const val DEFAULT_WIDTH = 4000
        private const val DEFAULT_EXPOSURE_NS = 33_000_000L
        private const val DEFAULT_FOV_DEG = 65.0
        val ALWAYS: () -> Boolean = { true }
    }
}
