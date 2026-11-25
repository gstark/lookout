package app.lookout.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A luma raster. Pixel (i, j) covers [i, i + 1) × [j, j + 1), so its center is at (i + 0.5, j + 0.5). */
class Raster(val w: Int, val h: Int, val v: FloatArray) {
    /** Bilinear luma at (x, y), clamped to the edge pixels. */
    fun at(x: Double, y: Double): Double {
        val fx = (x - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = min(fx.toInt(), w - 2).coerceAtLeast(0)
        val y0 = min(fy.toInt(), h - 2).coerceAtLeast(0)
        val ax = fx - x0
        val ay = fy - y0
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val top = v[y0 * w + x0] * (1 - ax) + v[y0 * w + x1] * ax
        val bottom = v[y1 * w + x0] * (1 - ax) + v[y1 * w + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    companion object {
        fun of(argb: IntArray, w: Int, h: Int) = Raster(w, h, FloatArray(w * h) { ChangeDetector.luma(argb[it]).toFloat() })
    }
}

/**
 * The pixels of [box] of a source image, scaled to [w] × [h]. [toWork] maps source pixel coordinates to
 * coordinates of these pixels, and [toSource] maps them back.
 */
class Pulled(val argb: IntArray, val w: Int, val h: Int, val box: PixelRect) {
    val toWork = Homography.scaling(w.toDouble() / box.w, h.toDouble() / box.h, -box.x * w.toDouble() / box.w, -box.y * h.toDouble() / box.h)
    val toSource get() = toWork.inverse()
    /** Working pixels per source pixel. */
    val scale get() = w.toDouble() / box.w
    fun luma() = Raster.of(argb, w, h)
}

/**
 * Pixel work for a top-down view of a plane: pulling a region of a large image, warping it, putting four corners
 * in order and snapping approximate corners to the edges of a rectangle.
 */
object Rectify {
    /** Samples of each side that look for its edge. */
    const val SIDE_SAMPLES = 20
    /** A side needs this many edge points that agree on one line. */
    const val MIN_SIDE_POINTS = 8
    /** The luma step that counts as an edge. */
    const val MIN_STEP = 12.0
    /** An edge point this far from the fitted line, in px, always counts as an inlier. */
    private const val MIN_TOL_PX = 0.75
    /** The fitted line must be within this angle of the approximate side. */
    private const val MAX_TURN_DEG = 10.0
    /** Profile step across an edge, in px. */
    const val STEP = 0.5
    /** A gradient sample belongs to an edge while it is above this fraction of the edge peak. */
    private const val TAIL = 0.1

    /**
     * [box] of [img] at [scale] (at most 1) as ARGB. Halving steps come first, so the final bilinear scaling
     * does not alias, and only the box is ever pulled, so a 12 MP source never becomes one 48 MB array.
     */
    fun pull(ops: ImageOps, img: Img, box: PixelRect, scale: Double): Pulled {
        val w = max(1, (box.w * scale).roundToInt())
        val h = max(1, (box.h * scale).roundToInt())
        var cur = if (box.x == 0 && box.y == 0 && box.w == img.width && box.h == img.height) img else ops.crop(img, box)
        while (cur.width > w * 2 && cur.height > h * 2) cur = ops.scale(cur, cur.width / 2, cur.height / 2)
        return Pulled(ops.argb(cur, w, h), w, h, box)
    }

    /** The pixel box of [img] that holds [pts] with [pad] px around them, or null when they lie outside it. */
    fun box(pts: List<Pt>, pad: Double, width: Int, height: Int): PixelRect? {
        val x0 = floor(pts.minOf { it.x } - pad).toInt().coerceAtLeast(0)
        val y0 = floor(pts.minOf { it.y } - pad).toInt().coerceAtLeast(0)
        val x1 = ceil(pts.maxOf { it.x } + pad).toInt().coerceAtMost(width)
        val y1 = ceil(pts.maxOf { it.y } + pad).toInt().coerceAtMost(height)
        return if (x1 - x0 < 2 || y1 - y0 < 2) null else PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * A [vw] × [vh] view: each view pixel center maps through [viewToSource] to the source, and its color is the
     * bilinear sample of [src] there. A view pixel that maps outside [src] gets [background].
     */
    fun warp(src: Pulled, viewToSource: Homography, vw: Int, vh: Int, background: Int): IntArray {
        val m = (src.toWork * viewToSource).m
        val out = IntArray(vw * vh)
        val w = src.w
        val h = src.h
        val px = src.argb
        for (j in 0 until vh) {
            val y = j + 0.5
            for (i in 0 until vw) {
                val x = i + 0.5
                val d = m[6] * x + m[7] * y + m[8]
                val u = (m[0] * x + m[1] * y + m[2]) / d - 0.5
                val v = (m[3] * x + m[4] * y + m[5]) / d - 0.5
                if (u < -0.5 || v < -0.5 || u > w - 0.5 || v > h - 0.5) {
                    out[j * vw + i] = background
                    continue
                }
                val fu = u.coerceIn(0.0, w - 1.0)
                val fv = v.coerceIn(0.0, h - 1.0)
                val x0 = min(fu.toInt(), max(0, w - 2))
                val y0 = min(fv.toInt(), max(0, h - 2))
                val x1 = min(x0 + 1, w - 1)
                val y1 = min(y0 + 1, h - 1)
                val ax = fu - x0
                val ay = fv - y0
                out[j * vw + i] = mix(px[y0 * w + x0], px[y0 * w + x1], px[y1 * w + x0], px[y1 * w + x1], ax, ay)
            }
        }
        return out
    }

    private fun mix(c00: Int, c10: Int, c01: Int, c11: Int, ax: Double, ay: Double): Int {
        var out = 0xFF000000.toInt()
        for (shift in intArrayOf(16, 8, 0)) {
            val top = ((c00 shr shift) and 0xFF) * (1 - ax) + ((c10 shr shift) and 0xFF) * ax
            val bottom = ((c01 shr shift) and 0xFF) * (1 - ax) + ((c11 shr shift) and 0xFF) * ax
            out = out or ((top * (1 - ay) + bottom * ay).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /**
     * [pts] (4 points) in the order top-left, top-right, bottom-right, bottom-left, clockwise on the image
     * (y down). The top and bottom sides are the pair of opposite sides nearer to horizontal, so the axes
     * cannot swap with the order the points come in. Throws when the points do not form a convex quad.
     */
    fun order(pts: List<Pt>): List<Pt> {
        require(pts.size == 4) { "A quad has 4 corners" }
        val cx = pts.sumOf { it.x } / 4
        val cy = pts.sumOf { it.y } / 4
        val cw = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        if (!convex(cw)) throw LookoutError("invalid_corners", 400, "The 4 corners do not form a convex quad. Give the corners of one rectangle.")
        fun flatness(i: Int) = (abs(dir(cw[i], cw[(i + 1) % 4]).y) + abs(dir(cw[(i + 2) % 4], cw[(i + 3) % 4]).y))
        // Side i runs from corner i to corner i + 1. Sides 0 and 2 are top and bottom when they are flatter.
        val start = if (flatness(0) <= flatness(1)) 0 else 1
        val a = listOf(start, start + 2).minBy { (cw[it % 4].y + cw[(it + 1) % 4].y) }
        return List(4) { cw[(a + it) % 4] }
    }

    /** True when the closed polygon [q] turns the same way at every corner. */
    fun convex(q: List<Pt>): Boolean {
        val turns = q.indices.map { i ->
            val a = q[i]
            val b = q[(i + 1) % q.size]
            val c = q[(i + 2) % q.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return turns.all { it > 0 } || turns.all { it < 0 }
    }

    /** The interior angle in degrees at each corner of the convex quad [q]. */
    fun angles(q: List<Pt>): List<Double> = q.indices.map { i ->
        val a = dir(q[i], q[(i + q.size - 1) % q.size])
        val b = dir(q[i], q[(i + 1) % q.size])
        Math.toDegrees(acos((a.x * b.x + a.y * b.y).coerceIn(-1.0, 1.0)))
    }

    private fun dir(a: Pt, b: Pt): Pt {
        val d = hypot(b.x - a.x, b.y - a.y)
        return Pt((b.x - a.x) / d, (b.y - a.y) / d)
    }

    /** One edge in a profile: [pos] in profile samples (fractional), and the luma [step] across it (positive). */
    class Edge(val pos: Double, val step: Double)

    /**
     * The edges in [profile] whose luma rises along it when [sign] is 1, or falls when it is -1. In each run of
     * gradient samples of that sign, each stretch above [TAIL] of the run's peak is one edge, so a small dip in
     * the gradient does not split an edge in two. Its position is the centroid of the gradient over the stretch.
     * For an area-sampled step edge this is the edge itself to a small fraction of a pixel, whatever the threshold.
     */
    fun edges(profile: DoubleArray, sign: Int): List<Edge> {
        val g = DoubleArray(profile.size - 1) { sign * (profile[it + 1] - profile[it]) }
        val out = ArrayList<Edge>()
        var i = 0
        while (i < g.size) {
            if (g[i] <= 0) { i++; continue }
            var end = i
            while (end + 1 < g.size && g[end + 1] > 0) end++
            val floor = TAIL * (i..end).maxOf { g[it] }
            var k = i
            while (k <= end) {
                if (g[k] <= floor) { k++; continue }
                var sum = 0.0
                var moment = 0.0
                while (k <= end && g[k] > floor) {
                    sum += g[k]
                    moment += g[k] * (k + 0.5)
                    k++
                }
                out += Edge(moment / sum, sum)
            }
            i = end + 1
        }
        return out
    }

    /** One side of a refined quad: [found] when its edge was found, with the RMS distance of its edge points from the fitted line. */
    class Side(val found: Boolean, val rmsPx: Double?)

    /** [corners] after refinement, in the order of the approximate quad, and each [sides] i from corner i to corner i + 1. */
    class Refined(val corners: List<Pt>, val sides: List<Side>)

    /**
     * Snaps the approximate convex quad [quad] to the straight edges near it in [l]. For each side, [SIDE_SAMPLES]
     * luma profiles across it (within [radiusFraction] of its length from the side) give one edge point each.
     * All points of one side keep one polarity (the one with the most contrast), and of the strong edges in a
     * profile the one nearest the side wins. A line is fitted to the points by total least squares, dropping the
     * worst point while it is more than 3 robust standard deviations off. A corner moves to the meeting point of
     * its two fitted lines; when one of them has no clear edge, the corner stays where it was.
     */
    fun refine(l: Raster, quad: List<Pt>, radiusFraction: Double): Refined {
        val lines = quad.indices.map { side(l, quad[it], quad[(it + 1) % 4], radiusFraction) }
        val corners = quad.indices.map { i ->
            val before = lines[(i + 3) % 4]
            val after = lines[i]
            if (before == null || after == null) quad[i] else meet(before, after) ?: quad[i]
        }
        return Refined(corners, lines.map { Side(it != null, it?.rms) })
    }

    /** A line through [c] with unit direction [u], fitted to points with an [rms] distance from it. */
    class Line(val c: Pt, val u: Pt, val rms: Double)

    private fun meet(a: Line, b: Line): Pt? {
        val den = a.u.x * b.u.y - a.u.y * b.u.x
        if (abs(den) < 1e-9) return null
        val t = ((b.c.x - a.c.x) * b.u.y - (b.c.y - a.c.y) * b.u.x) / den
        return a.c + a.u * t
    }

    /** The fitted edge line near the side [a] to [b], or null when the side has no clear straight edge. */
    private fun side(l: Raster, a: Pt, b: Pt, radiusFraction: Double): Line? {
        val d = dir(a, b)
        val n = Pt(-d.y, d.x)
        val len = a.dist(b)
        val r = max(MIN_RADIUS_PX, radiusFraction * len)
        val count = 2 * ceil(r / STEP).toInt() + 1
        val half = (count - 1) / 2
        val profiles = (0 until SIDE_SAMPLES).map { k ->
            val p = a + (b - a) * (0.1 + 0.8 * k / (SIDE_SAMPLES - 1))
            p to DoubleArray(count) { i ->
                val s = (i - half) * STEP
                ALONG.sumOf { o -> l.at(p.x + n.x * s + d.x * o, p.y + n.y * s + d.y * o) } / ALONG.size
            }
        }
        val sign = listOf(1, -1).maxBy { sg -> profiles.sumOf { (_, pr) -> edges(pr, sg).maxOfOrNull { it.step } ?: 0.0 } }
        val pts = profiles.mapNotNull { (p, pr) ->
            val found = edges(pr, sign)
            val top = found.maxOfOrNull { it.step } ?: return@mapNotNull null
            if (top < MIN_STEP) return@mapNotNull null
            val e = found.filter { it.step >= max(MIN_STEP, 0.5 * top) }.minBy { abs(it.pos - half) }
            p + n * ((e.pos - half) * STEP)
        }.toMutableList()
        while (pts.size >= MIN_SIDE_POINTS) {
            val line = fitLine(pts)
            val res = pts.map { (it - line.c).let { q -> q.x * -line.u.y + q.y * line.u.x } }
            val sigma = 1.4826 * res.map { abs(it) }.sorted()[res.size / 2]
            val worst = res.indices.maxBy { abs(res[it]) }
            if (abs(res[worst]) > max(MIN_TOL_PX, 3 * sigma)) {
                pts.removeAt(worst)
                continue
            }
            val turn = abs(line.u.x * n.x + line.u.y * n.y)
            val mid = (a + b) * 0.5 - line.c
            val off = abs(mid.x * -line.u.y + mid.y * line.u.x)
            return line.takeIf { turn < sin(Math.toRadians(MAX_TURN_DEG)) && off <= r }
        }
        return null
    }

    /** Total least squares line through [pts]. */
    private fun fitLine(pts: List<Pt>): Line {
        val cx = pts.sumOf { it.x } / pts.size
        val cy = pts.sumOf { it.y } / pts.size
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in pts) {
            sxx += (p.x - cx) * (p.x - cx)
            sxy += (p.x - cx) * (p.y - cy)
            syy += (p.y - cy) * (p.y - cy)
        }
        val t = 0.5 * atan2(2 * sxy, sxx - syy)
        val u = Pt(cos(t), sin(t))
        val rms = sqrt(pts.sumOf { val q = it - Pt(cx, cy); val r = q.x * -u.y + q.y * u.x; r * r } / pts.size)
        return Line(Pt(cx, cy), u, rms)
    }

    private const val MIN_RADIUS_PX = 4.0
    /** Offsets along a side, in px, whose samples average into one profile value. */
    private val ALONG = doubleArrayOf(-1.0, 0.0, 1.0)
}
