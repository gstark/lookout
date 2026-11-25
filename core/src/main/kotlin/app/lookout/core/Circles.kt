package app.lookout.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds round holes and round features in a top-down view, with a sub-pixel center and diameter.
 *
 * Method:
 * - A local threshold: a pixel is dark when it is more than an offset below the mean of a window around it, and
 *   light when it is more than the offset above. The window is at least twice the largest diameter, so it always
 *   sees the surface around a hole, and uneven light does not move the threshold far. The offset is a tenth of
 *   the luma range of the view, at least [MIN_OFFSET].
 * - Connected regions of dark pixels (holes that show a dark background) and of light pixels (holes that show a
 *   light background, or light round features) are candidates. Thin lines that touch a hole, such as the grid of
 *   the measuring mat, are cut off first (see [detached]). A candidate that touches the border, or whose
 *   size is outside the diameter range, is dropped. A round region fills about π/4 of its bounding box, has an
 *   aspect near 1, and its boundary (counted in pixel edges) is about 4 diameters long. Squares, slots and
 *   irregular blobs fail one of these tests.
 * - The fit: [RAYS] rays from the region's centroid each give one sub-pixel edge point, the centroid of the
 *   luma gradient across the edge (see [Rectify.edges]). A least-squares circle (the algebraic Kåsa fit) through
 *   those points gives the center and diameter; points more than 3 robust standard deviations off are dropped
 *   once. The RMS distance of the points from the circle is the quality. A circle whose RMS is above
 *   [MAX_ROUGHNESS] of its radius is not round and is dropped.
 */
object Circles {
    /** A circle in view px: center ([x], [y]), diameter [d], RMS distance of its edge points [rms], and [dark] inside. */
    class Circle(val x: Double, val y: Double, val d: Double, val rms: Double, val dark: Boolean)

    const val RAYS = 48
    const val MIN_OFFSET = 8.0
    const val MAX_ROUGHNESS = 0.05
    private const val MIN_FILL = 0.68
    private const val MAX_FILL = 0.88
    private const val MAX_ASPECT = 1.25
    private const val MAX_PERIMETER = 1.3
    private const val MIN_RAY_SHARE = 0.6

    /** The circles in [l] with a diameter from [minD] to [maxD] px. */
    fun find(l: Raster, minD: Double, maxD: Double): List<Circle> {
        val w = l.w
        val h = l.h
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += l.v[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val sorted = l.v.copyOf().also { it.sort() }
        val offset = max(MIN_OFFSET, 0.1 * (sorted[(sorted.size * 0.98).toInt()] - sorted[(sorted.size * 0.02).toInt()]))
        val half = ceil(maxD).toInt().coerceIn(8, max(w, h))
        val mean = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h, y + half + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val x1 = min(w, x + half + 1)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                mean[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        val out = ArrayList<Circle>()
        val erode = (minD / 4).toInt().coerceIn(1, 4)
        for (dark in listOf(true, false)) {
            val mask = BooleanArray(w * h) { if (dark) l.v[it] < mean[it] - offset else l.v[it] > mean[it] + offset }
            for (r in regions(detached(mask, w, h, erode), w, h)) {
                if (r.border) continue
                val eq = 2 * sqrt(r.area / PI)
                if (eq < minD * 0.8 || eq > maxD * 1.2) continue
                val bw = r.x1 - r.x0 + 1.0
                val bh = r.y1 - r.y0 + 1.0
                if (max(bw, bh) / min(bw, bh) > MAX_ASPECT) continue
                if (r.area / (bw * bh) !in MIN_FILL..MAX_FILL) continue
                if (r.perimeter / (4 * eq) > MAX_PERIMETER) continue
                val c = fit(l, r.cx, r.cy, eq / 2, dark) ?: continue
                if (c.d in minD..maxD && hypot(c.x - r.cx, c.y - r.cy) < c.d / 8) out += c
            }
        }
        return out
    }

    /**
     * [mask] eroded by a (2 [r] + 1)² square, then dilated by one pixel more and cut back to [mask]. Lines up to
     * 2 [r] px wide, such as the grid of the measuring mat, vanish in the erosion, so they no longer join the holes
     * they touch, while a hole keeps its own outline.
     */
    private fun detached(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val core = spread(spread(mask, w, h, r, true, true), w, h, r, false, true)
        val grown = spread(spread(core, w, h, r + 1, true, false), w, h, r + 1, false, false)
        return BooleanArray(mask.size) { grown[it] && mask[it] }
    }

    /** Each pixel set when all ([all]) or any of the pixels within [r] along x ([alongX]) or y are set. */
    private fun spread(src: BooleanArray, w: Int, h: Int, r: Int, alongX: Boolean, all: Boolean): BooleanArray {
        val out = BooleanArray(src.size)
        val lines = if (alongX) h else w
        val len = if (alongX) w else h
        for (line in 0 until lines) {
            fun at(i: Int) = if (alongX) line * w + i else i * w + line
            var count = 0
            for (i in 0 until min(r, len)) if (src[at(i)]) count++
            for (i in 0 until len) {
                if (i + r < len && src[at(i + r)]) count++
                if (i - r - 1 >= 0 && src[at(i - r - 1)]) count--
                val span = min(len - 1, i + r) - max(0, i - r) + 1
                out[at(i)] = if (all) count == span else count > 0
            }
        }
        return out
    }

    private class Region(val area: Int, val cx: Double, val cy: Double, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val perimeter: Int, val border: Boolean)

    /** The 4-connected regions of [mask], with area, centroid, bounding box and boundary length in pixel edges. */
    private fun regions(mask: BooleanArray, w: Int, h: Int): List<Region> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Region>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var area = 0
            var sx = 0.0
            var sy = 0.0
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            var perimeter = 0
            var border = false
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                area++
                sx += x + 0.5
                sy += y + 0.5
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                for (k in 0 until 4) {
                    val nx = x + DX[k]
                    val ny = y + DY[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) { perimeter++; continue }
                    val j = ny * w + nx
                    if (!mask[j]) { perimeter++; continue }
                    if (!seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            out += Region(area, sx / area, sy / area, x0, y0, x1, y1, perimeter, border)
        }
        return out
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    /** The circle fitted to the edge points on rays from ([cx], [cy]) around radius [r0], or null when it is not round. */
    private fun fit(l: Raster, cx: Double, cy: Double, r0: Double, dark: Boolean): Circle? {
        val from = 0.5 * r0
        val to = 1.5 * r0 + 2
        val step = 0.25
        val n = ((to - from) / step).toInt() + 1
        val pts = ArrayList<Pt>(RAYS)
        for (k in 0 until RAYS) {
            val a = 2 * PI * k / RAYS
            val dx = cos(a)
            val dy = sin(a)
            val profile = DoubleArray(n) { i -> l.at(cx + dx * (from + i * step), cy + dy * (from + i * step)) }
            val e = Rectify.edges(profile, if (dark) 1 else -1).maxByOrNull { it.step } ?: continue
            if (e.step < Rectify.MIN_STEP) continue
            val s = from + e.pos * step
            pts += Pt(cx + dx * s, cy + dy * s)
        }
        if (pts.size < RAYS * MIN_RAY_SHARE) return null
        var c = kasa(pts) ?: return null
        val res = pts.map { abs(it.dist(Pt(c.x, c.y)) - c.d / 2) }
        val tol = max(0.5, 3 * 1.4826 * res.sorted()[res.size / 2])
        val kept = pts.filterIndexed { i, _ -> res[i] <= tol }
        if (kept.size < RAYS * MIN_RAY_SHARE) return null
        c = kasa(kept) ?: return null
        return Circle(c.x, c.y, c.d, c.rms, dark).takeIf { it.rms <= MAX_ROUGHNESS * it.d / 2 }
    }

    /** The least-squares circle through [pts]: x² + y² + D x + E y + F = 0, solved around their centroid. */
    private fun kasa(pts: List<Pt>): Circle? {
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (p in pts) {
            val x = p.x - mx
            val y = p.y - my
            val row = doubleArrayOf(x, y, 1.0)
            val rhs = -(x * x + y * y)
            for (i in 0 until 3) {
                b[i] += row[i] * rhs
                for (j in 0 until 3) a[i][j] += row[i] * row[j]
            }
        }
        val s = Homography.solve(a, b) ?: return null
        val ox = -s[0] / 2
        val oy = -s[1] / 2
        val r2 = ox * ox + oy * oy - s[2]
        if (r2 <= 0) return null
        val r = sqrt(r2)
        val rms = sqrt(pts.sumOf { val e = hypot(it.x - mx - ox, it.y - my - oy) - r; e * e } / pts.size)
        return Circle(ox + mx, oy + my, 2 * r, rms, false)
    }
}
