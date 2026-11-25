package app.lookout.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Follows one edge along one axis of a thin ROI over the frames of a recording, to tell if something moved,
 * how far and when.
 *
 * Method, for each frame:
 * - [profile]: the ROI collapses to a 1-D luma profile along the axis (the mean across the other axis).
 * - [edges]: the profile is smoothed with a [1, 2, 1] kernel. Its forward differences are the gradient, and
 *   each local peak of |gradient| is an edge candidate. The candidate spans the neighbouring differences of
 *   the same sign above [TAIL] of the peak and above the noise of the gradient. Its position is the centroid
 *   of the gradient over that span, which gives sub-pixel precision: for a whole symmetric edge it is exact,
 *   because smoothing and area sampling keep the centroid. Its contrast is the luma step across the span.
 * - [Tracker]: the edge near the previous position wins when it is at least [KEEP] as strong as before.
 *   Otherwise the [Edge] rule picks among all candidates. A frame with no candidate of [MIN_CONTRAST] is lost.
 *
 * [analyze] then finds holds: runs of frames whose positions stay within a tolerance of [NOISE_TOL] times the
 * noise (at least [MIN_TOL_PX]) for [MIN_HOLD_MS] or more. The noise is the robust standard deviation of the
 * frame-to-frame change. The stretches between holds are the moves. A mark settles at the last hold that ends
 * after it and starts before the next mark.
 */
object Track {
    enum class Edge(val wire: String) {
        STRONGEST("strongest"), FIRST("first"), LAST("last"), RISING("rising"), FALLING("falling");

        companion object {
            val WIRES = entries.map { it.wire }
            fun parse(text: String): Edge = entries.first { it.wire == text }
        }
    }

    /**
     * An edge in a profile. [pos] is in px from the start of the profile, where 0 is the outer side of the
     * first pixel. [contrast] is the luma step across it, [rising] means dark to light. [from]..[to] are the
     * gradient samples it spans.
     */
    class Found(val pos: Double, val contrast: Double, val rising: Boolean, val from: Int, val to: Int)

    /** One frame: [px] is the edge position in source px, or null when the edge is lost. */
    class Point(val tMs: Long, val px: Double?, val contrast: Double)

    /** Below this luma step an edge counts as lost. */
    const val MIN_CONTRAST = 12.0
    const val MIN_HOLD_MS = 100L
    const val MIN_TOL_PX = 0.3
    const val MAX_ROWS = 200
    private const val TAIL = 0.05
    private const val NOISE_FLOOR = 4.0
    private const val MAX_HALF_SPAN = 12
    private const val KEEP = 0.5
    private const val SEARCH_FRACTION = 0.1
    private const val MIN_SEARCH_PX = 6.0
    private const val NOISE_TOL = 6.0

    /** The mean luma of [argb] ([w] × [h]) along x when [alongX], else along y. */
    fun profile(argb: IntArray, w: Int, h: Int, alongX: Boolean): DoubleArray {
        val out = DoubleArray(if (alongX) w else h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = argb[y * w + x]
            out[if (alongX) x else y] += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000.0
        }
        val across = if (alongX) h else w
        for (i in out.indices) out[i] /= across
        return out
    }

    /** All edge candidates of [profile], strongest first. Candidates that share gradient samples keep the stronger one. */
    fun edges(profile: DoubleArray): List<Found> {
        val n = profile.size
        if (n < 3) return emptyList()
        val s = DoubleArray(n) { (profile[max(0, it - 1)] + 2 * profile[it] + profile[min(n - 1, it + 1)]) / 4 }
        // d[i] is the step from pixel i to pixel i + 1, so it sits at position i + 1.
        val d = DoubleArray(n - 1) { s[it + 1] - s[it] }
        fun mag(k: Int) = if (k in d.indices) abs(d[k]) else 0.0
        // Most gradient samples are flat background, so their median size measures the noise.
        val noise = NOISE_FLOOR * d.map { abs(it) }.sorted()[d.size / 2]
        val found = mutableListOf<Found>()
        for (k in d.indices) {
            val m = abs(d[k])
            if (m == 0.0 || m < mag(k - 1) || m <= mag(k + 1)) continue
            val sign = if (d[k] > 0) 1.0 else -1.0
            val floor = max(m * TAIL, noise)
            var a = k
            while (a > 0 && k - a < MAX_HALF_SPAN && d[a - 1] * sign > floor) a--
            var b = k
            while (b < d.size - 1 && b - k < MAX_HALF_SPAN && d[b + 1] * sign > floor) b++
            var weight = 0.0
            var moment = 0.0
            for (i in a..b) {
                weight += d[i] * sign
                moment += d[i] * sign * (i + 1)
            }
            found += Found(moment / weight, weight, sign > 0, a, b)
        }
        // A noisy slope can peak twice.
        val kept = mutableListOf<Found>()
        for (f in found.sortedByDescending { it.contrast }) if (kept.none { f.from <= it.to && it.from <= f.to }) kept += f
        return kept
    }

    /** The edge to follow among [edges]: the one near [near] if it stays strong, else the one that [edge] names. */
    fun pick(edges: List<Found>, edge: Edge, near: Found?, radius: Double): Found? {
        val c = edges.filter {
            it.contrast >= MIN_CONTRAST && when (edge) {
                Edge.RISING -> it.rising
                Edge.FALLING -> !it.rising
                else -> true
            }
        }
        if (near != null) {
            c.filter { it.rising == near.rising && abs(it.pos - near.pos) <= radius }.maxByOrNull { it.contrast }
                ?.takeIf { it.contrast >= near.contrast * KEEP }?.let { return it }
        }
        return when (edge) {
            Edge.FIRST -> c.minByOrNull { it.pos }
            Edge.LAST -> c.maxByOrNull { it.pos }
            else -> c.maxByOrNull { it.contrast }
        }
    }

    /** Follows one edge from frame to frame. */
    class Tracker(private val edge: Edge) {
        private var last: Found? = null

        /** The edge in [profile] (null when it is lost) and the strongest contrast in it, which a lost frame reports. */
        fun next(profile: DoubleArray): Pair<Found?, Double> {
            val all = edges(profile)
            val f = pick(all, edge, last, max(MIN_SEARCH_PX, profile.size * SEARCH_FRACTION))
            if (f != null) last = f
            return f to (all.maxOfOrNull { it.contrast } ?: 0.0)
        }
    }

    private class Hold(val from: Int, var to: Int, val members: MutableList<Int>) {
        fun mean(p: List<Double>) = members.sumOf { p[it] } / members.size
        fun sd(p: List<Double>): Double {
            val m = mean(p)
            return sqrt(members.sumOf { (p[it] - m) * (p[it] - m) } / members.size)
        }
    }

    /**
     * Holds, moves and the position at each of [marks] for [points] (in time order). [mmPerPx], when set, adds
     * mm columns. [look] turns a position in source px into the normalized look-frame coordinate along the axis.
     */
    fun analyze(points: List<Point>, frameMs: Double, marks: List<Mark>, mmPerPx: Double?, look: (Double) -> Double, raw: Boolean): JsonObject {
        val found = points.filter { it.px != null }
        val p = found.map { it.px!! }
        val t = found.map { it.tMs }
        val noise = noise(p)
        val tol = max(MIN_TOL_PX, NOISE_TOL * noise)
        val holds = holds(t, p, tol, frameMs)
        return buildJsonObject {
            put("frames", points.size)
            put("lost", points.size - found.size)
            put("frame_interval_ms", frameMs)
            mmPerPx?.let { put("mm_per_px", it) }
            if (found.isEmpty()) {
                put("hint", "No edge in any frame. Use a thin roi across one sharp edge on the path of the part.")
                return@buildJsonObject
            }
            val lo = p.min()
            val hi = p.max()
            putJsonObject("px") { put("min", lo); put("max", hi); put("travel", hi - lo) }
            if (mmPerPx != null) putJsonObject("mm") { put("min", lo * mmPerPx); put("max", hi * mmPerPx); put("travel", (hi - lo) * mmPerPx) }
            put("noise_px", noise)
            put("hold_tolerance_px", tol)

            val h = Table(mmPerPx, "from_ms", "to_ms", "mean_px", "sd_px", "look")
            put("holds_columns", h.columns)
            putJsonArray("holds") { holds.take(MAX_ROWS).forEach { add(h.row(t[it.from], t[it.to], it.mean(p), it.sd(p), look(it.mean(p)))) } }
            if (holds.size > MAX_ROWS) put("holds_truncated", holds.size - MAX_ROWS)

            val moves = holds.zipWithNext()
            val mv = Table(mmPerPx, "from_ms", "to_ms", "from_px", "to_px", "distance_px", "peak_px_s")
            put("moves_columns", mv.columns)
            putJsonArray("moves") {
                moves.take(MAX_ROWS).forEach { (a, b) ->
                    var peak = 0.0
                    for (k in a.to until b.from) {
                        val dt = t[k + 1] - t[k]
                        if (dt > 0) peak = max(peak, abs(p[k + 1] - p[k]) * 1000.0 / dt)
                    }
                    add(mv.row(t[a.to], t[b.from], a.mean(p), b.mean(p), b.mean(p) - a.mean(p), peak))
                }
            }
            if (moves.size > MAX_ROWS) put("moves_truncated", moves.size - MAX_ROWS)

            if (marks.isNotEmpty()) {
                val mk = Table(mmPerPx, "label", "t_ms", "at_px", "settled_px", "moved_px", "settle_ms")
                put("marks_columns", mk.columns)
                putJsonArray("marks") {
                    marks.forEachIndexed { i, m ->
                        val at = points.minBy { abs(it.tMs - m.tMs) }.px
                        val next = marks.getOrNull(i + 1)?.tMs ?: Long.MAX_VALUE
                        // Where it went for this mark: the last hold that starts before the next mark and ends after this one.
                        val settled = holds.lastOrNull { t[it.from] < next && t[it.to] >= m.tMs }
                        val sp = settled?.mean(p)
                        add(mk.row(m.label, m.tMs, at, sp, if (at != null && sp != null) sp - at else null, settled?.let { max(0L, t[it.from] - m.tMs) }))
                    }
                }
            }
            if (raw) {
                val r = Table(mmPerPx, "t_ms", "pos_px", "contrast")
                put("raw_columns", r.columns)
                putJsonArray("raw") { points.forEach { add(r.row(it.tMs, it.px, it.contrast)) } }
            }
        }
    }

    /** Robust standard deviation of one position, from the median frame-to-frame change. */
    private fun noise(p: List<Double>): Double {
        if (p.size < 3) return 0.0
        val d = (1 until p.size).map { abs(p[it] - p[it - 1]) }.sorted()
        return 1.4826 * d[d.size / 2] / sqrt(2.0)
    }

    /** Runs of positions within [tol] of each other for [MIN_HOLD_MS] or more. Neighbours whose means differ by [tol] or less join. */
    private fun holds(t: List<Long>, p: List<Double>, tol: Double, frameMs: Double): List<Hold> {
        val out = mutableListOf<Hold>()
        var i = 0
        while (i < p.size) {
            var lo = p[i]
            var hi = p[i]
            var j = i
            while (j + 1 < p.size && max(hi, p[j + 1]) - min(lo, p[j + 1]) <= tol) {
                j++
                lo = min(lo, p[j])
                hi = max(hi, p[j])
            }
            if (t[j] - t[i] + frameMs < MIN_HOLD_MS) {
                i++
                continue
            }
            val h = Hold(i, j, (i..j).toMutableList())
            val prev = out.lastOrNull()
            if (prev != null && abs(prev.mean(p) - h.mean(p)) <= tol) {
                prev.to = j
                prev.members += h.members
            } else {
                out += h
            }
            i = j + 1
        }
        return out
    }

    /** Rows of [names]. When [mmPerPx] is set, each "_px" column gets an "_mm" copy at the end. */
    private class Table(private val mmPerPx: Double?, vararg names: String) {
        private val px = names.indices.filter { "_px" in names[it] }
        val columns: JsonArray = buildJsonArray {
            names.forEach { add(JsonPrimitive(it)) }
            if (mmPerPx != null) px.forEach { add(JsonPrimitive(names[it].replace("_px", "_mm"))) }
        }

        fun row(vararg v: Any?): JsonArray = buildJsonArray {
            v.forEach { add(json(it)) }
            if (mmPerPx != null) px.forEach { add(json((v[it] as Double?)?.times(mmPerPx))) }
        }

        private fun json(v: Any?) = when (v) {
            null -> JsonNull
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}
