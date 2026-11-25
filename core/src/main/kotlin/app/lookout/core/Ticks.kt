package app.lookout.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** An image overlay requested by the `grid` parameter: either a labelled cell grid or edge ruler ticks. */
sealed class GridOverlay {
    data class Cells(val spec: GridSpec) : GridOverlay()
    data object Ticks : GridOverlay()

    companion object {
        fun parse(v: String?): GridOverlay? = when (v?.trim()?.lowercase()) {
            null, "", "false", "0", "no" -> null
            "true", "1", "yes" -> Cells(GridSpec.DEFAULT)
            "ticks" -> Ticks
            else -> Cells(GridSpec.parse(v))
        }
    }
}

/** One tick mark along a normalized coordinate axis. [label] is set on major ticks only. */
data class Tick(val value: Double, val major: Boolean, val label: String?)

/** Computed tick layout for one edge spanning [start]..[end] in full-frame normalized coordinates. */
data class TickAxis(val step: Double, val majorStep: Double, val decimals: Int, val ticks: List<Tick>)

/** Pure ruler tick math: chooses a nice step (1, 2 or 5 x 10^k) giving 8..20 ticks along an edge. */
object Ticks {
    private const val MAX_TICKS = 20.0
    private const val EPS = 1e-9

    /**
     * Computes ticks in full-frame normalized coordinates for an edge spanning [start]..[end].
     * A full frame (0..1) gets step 0.05 with labels every 0.1.
     */
    fun axis(start: Double, end: Double): TickAxis {
        require(end > start) { "Tick span must be positive: $start..$end" }
        val span = end - start
        val rawStep = span / MAX_TICKS
        val exp = floor(log10(rawStep)).toInt()
        val base = 10.0.pow(exp)
        val norm = rawStep / base
        val (mantissa, stepExp) = when {
            norm <= 1.0 + EPS -> 1 to exp
            norm <= 2.0 + EPS -> 2 to exp
            norm <= 5.0 + EPS -> 5 to exp
            else -> 1 to (exp + 1)
        }
        val step = mantissa * 10.0.pow(stepExp)
        val labelEvery = when {
            mantissa == 5 -> 2
            span / (step * 5) >= 3.0 - EPS -> 5
            else -> 2
        }
        val majorStep = step * labelEvery
        val decimals = if (mantissa * labelEvery == 10) max(0, -(stepExp + 1)) else max(0, -stepExp)
        val firstK = ceil(start / step - EPS).toLong()
        val lastK = floor(end / step + EPS).toLong()
        val ticks = ArrayList<Tick>((lastK - firstK + 1).coerceAtLeast(0L).toInt())
        for (k in firstK..lastK) {
            val v = (k * step).coerceIn(start, end)
            val major = k % labelEvery == 0L
            ticks += Tick(v, major, if (major) formatDecimal(v, decimals) else null)
        }
        return TickAxis(step, majorStep, decimals, ticks)
    }

    /** Formats [value] with exactly [decimals] fractional digits, independent of locale. */
    fun formatDecimal(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.roundToInt().toString()
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        val scaled = (value * scale).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absScaled = abs(scaled)
        val whole = absScaled / scale
        val frac = (absScaled % scale).toString().padStart(decimals, '0')
        return "$sign$whole.$frac"
    }
}
