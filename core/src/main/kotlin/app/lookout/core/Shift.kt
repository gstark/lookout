package app.lookout.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures the translation between two images by normalized cross-correlation (NCC) of luma.
 *
 * [locate] takes a template (a region of image a) and a search window around the same region of image b.
 * Both come to one working scale first: the long edge of the template is at most a given size, and the
 * scale is never finer than the coarser of the two images. So images at different resolutions (a 12 MP
 * still against a 1280 px preview frame) compare directly. The search is coarse to fine: template and
 * window are halved along each axis longer than [COARSE] px, every offset is tried at the coarsest level,
 * then each finer level refines the best offset by ±2 px. At the full working scale a parabola through the
 * NCC of the peak and its two neighbours on each axis gives the sub-pixel position. The score is the NCC at
 * the peak (1 is a perfect match, 0 is no relation).
 *
 * For a content shift, the NCC counts only the template pixels that changed between a and b at the
 * expected place (by more than [CHANGED_LUMA]). Static texture in the region then does not pull the result
 * toward no movement. When too few or nearly all pixels changed, all of them count.
 *
 * It finds no result when the template has no texture, or when the peak lies on the edge of the search
 * range, because the true peak can then lie beyond it.
 *
 * Only translation is measured. Rotation and scale between the frames are out of scope: a small amount
 * lowers the score, and a large amount prevents a match.
 *
 * All offsets are in normalized coordinates of image a (0..1 of its width and height).
 */
object Shift {
    /** A displacement of content from a to b, normalized to the width and height of a, and its NCC score. */
    class Found(val dx: Double, val dy: Double, val score: Double)

    /** The largest camera shift searched, as a fraction of each frame side. */
    const val CAMERA_RANGE = 0.125
    /** The lowest score at which [camera] accepts a shift. Lower means the frames differ too much to align. */
    const val MIN_ALIGN = 0.8
    /** The lowest score at which a content shift counts. */
    const val MIN_CONTENT = 0.6
    /** The lowest score at which a match on the changed pixels alone counts. */
    private const val MIN_PART = 0.85
    const val CAMERA_EDGE = 320
    const val CONTENT_EDGE = 384
    private const val COARSE = 48
    /** Below this luma standard deviation a template has no texture. */
    private const val MIN_STD = 2.0
    private const val MIN_SIDE = 4
    private const val CHANGED_LUMA = 12f
    /** The share of changed template pixels between which only the changed ones count. */
    private const val MIN_MOVING = 0.02
    private const val MAX_MOVING = 0.9
    /** The least share of the camera template outside the roi for the roi to be left out. */
    private const val MIN_OUTSIDE = 0.25

    /** The region of a that [camera] searches: the inner part, so that a shift of [CAMERA_RANGE] keeps it in frame. */
    private val INNER = NRect(CAMERA_RANGE, CAMERA_RANGE, 1 - 2 * CAMERA_RANGE, 1 - 2 * CAMERA_RANGE)

    /**
     * The camera shift of [b] against [a], or null when the frames do not line up with confidence. It leaves out
     * [roi], where a part can move, unless the roi covers most of the frame.
     */
    fun camera(ops: ImageOps, a: Img, b: Img, roi: NRect? = null): Found? =
        locate(ops, a, INNER, b, 0.0, 0.0, CAMERA_RANGE, CAMERA_RANGE, CAMERA_EDGE, exclude = roi)?.takeIf { it.score >= MIN_ALIGN }

    /**
     * How far the content of [region] of [a] moved in [b] beyond the [camera] shift. It searches up to one
     * region width and one region height in each direction, clipped to the frame. The score is 0 when
     * nothing was found.
     */
    fun content(ops: ImageOps, a: Img, region: NRect, b: Img, camera: Found?): Found =
        locate(ops, a, region, b, camera?.dx ?: 0.0, camera?.dy ?: 0.0, region.w, region.h, CONTENT_EDGE, moving = true) ?: Found(0.0, 0.0, 0.0)

    /**
     * [r] in a and the same view in b after the camera shift [s], both clipped so that each lies in its frame.
     * Null when [s] is under half a pixel of the change detector thumbnail, or when no part of [r] stays in view.
     */
    fun overlap(r: NRect, s: Found): Pair<NRect, NRect>? {
        if (abs(s.dx) * ChangeDetector.W < 0.5 && abs(s.dy) * ChangeDetector.H < 0.5) return null
        val x0 = max(r.x, -s.dx)
        val y0 = max(r.y, -s.dy)
        val x1 = min(r.x + r.w, 1 - s.dx)
        val y1 = min(r.y + r.h, 1 - s.dy)
        if (x1 - x0 < 1.0 / ChangeDetector.W || y1 - y0 < 1.0 / ChangeDetector.H) return null
        fun clip(x: Double, y: Double): NRect {
            val cx = x.coerceIn(0.0, 1.0)
            val cy = y.coerceIn(0.0, 1.0)
            return NRect(cx, cy, min(x1 - x0, 1 - cx), min(y1 - y0, 1 - cy))
        }
        return clip(x0, y0) to clip(x0 + s.dx, y0 + s.dy)
    }

    /**
     * Where the content of [region] of [a] lies in [b], relative to [region] moved by ([ex], [ey]). The search
     * covers [mx] and [my] more on each side, clipped to the frame of b. [maxEdge] bounds the long edge of the
     * template in working px. With [moving], only the pixels that changed count. Pixels in [exclude] do not count.
     */
    fun locate(
        ops: ImageOps, a: Img, region: NRect, b: Img, ex: Double, ey: Double, mx: Double, my: Double, maxEdge: Int,
        moving: Boolean = false, exclude: NRect? = null,
    ): Found? {
        val tp = region.toPixels(a.width, a.height)
        val scale = minOf(1.0, maxEdge.toDouble() / max(tp.w, tp.h), b.width.toDouble() / a.width, b.height.toDouble() / a.height)
        val tw = (tp.w * scale).roundToInt()
        val th = (tp.h * scale).roundToInt()
        if (tw < MIN_SIDE || th < MIN_SIDE) return null
        val tx = tp.x.toDouble() / a.width
        val ty = tp.y.toDouble() / a.height
        val sx = tp.w.toDouble() / a.width / tw
        val sy = tp.h.toDouble() / a.height / th

        val wx0 = max(0.0, region.x + ex - mx)
        val wy0 = max(0.0, region.y + ey - my)
        val wx1 = min(1.0, region.x + region.w + ex + mx)
        val wy1 = min(1.0, region.y + region.h + ey + my)
        if (wx1 - wx0 < region.w || wy1 - wy0 < region.h) return null
        val wp = NRect(wx0, wy0, wx1 - wx0, wy1 - wy0).toPixels(b.width, b.height)
        val ww = (wp.w.toDouble() / b.width / sx).roundToInt()
        val wh = (wp.h.toDouble() / b.height / sy).roundToInt()
        if (ww < tw || wh < th) return null
        val wx = wp.x.toDouble() / b.width
        val wy = wp.y.toDouble() / b.height
        val wsx = wp.w.toDouble() / b.width / ww
        val wsy = wp.h.toDouble() / b.height / wh

        val t = luma(ops, a, tp, tw, th)
        val win = luma(ops, b, wp, ww, wh)
        val u0 = (tx + ex - wx) / sx
        val v0 = (ty + ey - wy) / sy
        val whole = match(t, win, u0, v0, exclude?.let { outside(tw, th, tx, ty, sx, sy, it) }) ?: return null
        // A part that moved over a static background: match the changed pixels alone when that fits better.
        val m = if (moving) {
            // Where a and b differ, over the window.
            val aw = luma(ops, a, NRect(wx, wy, wp.w.toDouble() / b.width, wp.h.toDouble() / b.height).toPixels(a.width, a.height), ww, wh)
            val diff = BooleanArray(ww * wh) { abs(aw.px[it] - win.px[it]) > CHANGED_LUMA }
            val at0 = u0.roundToInt().coerceIn(0, ww - tw) to v0.roundToInt().coerceIn(0, wh - th)
            val first = changed(diff, ww, tw, th, listOf(at0))?.let { match(t, win, u0, v0, it) }
            // Then only the pixels whose place in b also changed: the part, without the background it uncovers.
            val second = first?.let { f -> changed(diff, ww, tw, th, listOf(at0, f.u.roundToInt() to f.v.roundToInt()))?.let { match(t, win, u0, v0, it) } }
            // A match on part of the pixels is easier by chance, so it must fit well.
            listOfNotNull(first, second).filter { it.score >= MIN_PART }.maxByOrNull { it.score }?.takeIf { it.score > whole.score } ?: whole
        } else whole
        // The displacement of the template center: its place in the window against its place in a.
        val dx = wx + (m.u + tw / 2.0) * wsx - (tx + tw / 2.0 * sx)
        val dy = wy + (m.v + th / 2.0) * wsy - (ty + th / 2.0 * sy)
        return Found(dx - ex, dy - ey, m.score)
    }

    /** A luma raster, or a weight per pixel. */
    class Luma(val w: Int, val h: Int, val px: FloatArray) {
        /** Halved by 2×2 (or 2×1) box averaging along the axes that [hx] and [hy] name. */
        fun half(hx: Boolean, hy: Boolean): Luma {
            val fx = if (hx) 2 else 1
            val fy = if (hy) 2 else 1
            val nw = w / fx
            val nh = h / fy
            val out = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var s = 0f
                for (j in 0 until fy) for (i in 0 until fx) s += px[(y * fy + j) * w + x * fx + i]
                out[y * nw + x] = s / (fx * fy)
            }
            return Luma(nw, nh, out)
        }
    }

    /** [r] of [img] as luma at [w] × [h]. Halving steps first keep bilinear scaling from aliasing. */
    fun luma(ops: ImageOps, img: Img, r: PixelRect, w: Int, h: Int): Luma {
        var cur = if (r.x == 0 && r.y == 0 && r.w == img.width && r.h == img.height) img else ops.crop(img, r)
        while (cur.width > w * 2 && cur.height > h * 2) cur = ops.scale(cur, cur.width / 2, cur.height / 2)
        val argb = ops.argb(cur, w, h)
        return Luma(w, h, FloatArray(w * h) { ChangeDetector.luma(argb[it]).toFloat() })
    }

    /** Weight 1 on each pixel of a [tw] × [th] template that lies on a changed pixel of [diff] at every one of [at], else 0. */
    private fun changed(diff: BooleanArray, ww: Int, tw: Int, th: Int, at: List<Pair<Int, Int>>): Luma? {
        val q = FloatArray(tw * th) { k -> if (at.all { (u, v) -> diff[(v + k / tw) * ww + u + k % tw] }) 1f else 0f }
        return Luma(tw, th, q).takeIf { share(q) in MIN_MOVING..MAX_MOVING }
    }

    /**
     * Weight 0 on each pixel of a [w] × [h] template at ([x], [y]) with steps ([sx], [sy]) that lies in [r], else 1.
     * Null when less than [MIN_OUTSIDE] of the template lies outside [r].
     */
    private fun outside(w: Int, h: Int, x: Double, y: Double, sx: Double, sy: Double, r: NRect): Luma? {
        val q = FloatArray(w * h) { k ->
            val px = x + (k % w + 0.5) * sx
            val py = y + (k / w + 0.5) * sy
            if (px >= r.x && px < r.x + r.w && py >= r.y && py < r.y + r.h) 0f else 1f
        }
        return Luma(w, h, q).takeIf { share(q) >= MIN_OUTSIDE }
    }

    private fun share(q: FloatArray) = q.sum().toDouble() / q.size

    /** The template offset in the window, in px of the window. */
    class Match(val u: Double, val v: Double, val score: Double)

    /**
     * The offset of [t] in [win] with the highest NCC, with [weights] per template pixel (null: all 1).
     * ([u0], [v0]) is the offset of no movement: a peak on an edge of the offset range counts only on a side
     * with no room to search, which is where ([u0], [v0]) lies.
     */
    fun match(t: Luma, win: Luma, u0: Double, v0: Double, weights: Luma? = null): Match? {
        require(win.w >= t.w && win.h >= t.h) { "The window is smaller than the template" }
        val ts = mutableListOf(t)
        val qs = mutableListOf(weights ?: Luma(t.w, t.h, FloatArray(t.px.size) { 1f }))
        val ws = mutableListOf(win)
        val steps = mutableListOf<Pair<Boolean, Boolean>>()
        while (true) {
            val hx = ts.last().w > COARSE
            val hy = ts.last().h > COARSE
            if (!hx && !hy) break
            ts += ts.last().half(hx, hy)
            qs += qs.last().half(hx, hy)
            ws += ws.last().half(hx, hy)
            steps += hx to hy
        }
        var cor = Correlator(ts.last(), qs.last(), ws.last()) ?: return null
        var bu = 0
        var bv = 0
        var best = Double.NEGATIVE_INFINITY
        for (v in 0..cor.vMax) for (u in 0..cor.uMax) {
            val c = cor.ncc(u, v)
            if (c > best) { best = c; bu = u; bv = v }
        }
        for (l in steps.indices.reversed()) {
            cor = Correlator(ts[l], qs[l], ws[l]) ?: return null
            val cu = if (steps[l].first) bu * 2 else bu
            val cv = if (steps[l].second) bv * 2 else bv
            best = Double.NEGATIVE_INFINITY
            for (v in max(0, cv - 2)..min(cor.vMax, cv + 2)) for (u in max(0, cu - 2)..min(cor.uMax, cu + 2)) {
                val c = cor.ncc(u, v)
                if (c > best) { best = c; bu = u; bv = v }
            }
        }
        if (onLimit(bu, cor.uMax, u0) || onLimit(bv, cor.vMax, v0)) return null
        val du = if (bu in 1 until cor.uMax) vertex(cor.ncc(bu - 1, bv), best, cor.ncc(bu + 1, bv)) else 0.0
        val dv = if (bv in 1 until cor.vMax) vertex(cor.ncc(bu, bv - 1), best, cor.ncc(bu, bv + 1)) else 0.0
        return Match(bu + du, bv + dv, best.coerceAtLeast(0.0))
    }

    private fun onLimit(p: Int, pMax: Int, p0: Double): Boolean {
        val rest = p0.roundToInt().coerceIn(0, pMax)
        return (p == 0 || p == pMax) && p != rest
    }

    /** The offset of the vertex of the parabola through (-1, [m]), (0, [c]), (1, [p]). */
    private fun vertex(m: Double, c: Double, p: Double): Double {
        val d = m - 2 * c + p
        return if (d >= 0) 0.0 else ((m - p) / (2 * d)).coerceIn(-0.5, 0.5)
    }

    /** Weighted NCC of a template at offsets in a window. */
    private class Correlator private constructor(
        private val t: Luma, private val q: FloatArray, private val qt: FloatArray, private val qSum: Double, private val tNorm: Double, private val win: Luma,
    ) {
        val uMax = win.w - t.w
        val vMax = win.h - t.h

        fun ncc(u: Int, v: Int): Double {
            var cross = 0.0
            var s = 0.0
            var sq = 0.0
            for (j in 0 until t.h) {
                val tr = j * t.w
                val wr = (v + j) * win.w + u
                for (i in 0 until t.w) {
                    val p = win.px[wr + i]
                    val qp = q[tr + i] * p
                    cross += qt[tr + i] * p
                    s += qp
                    sq += qp * p
                }
            }
            val varSum = sq - s * s / qSum
            return if (varSum <= 1e-9) 0.0 else cross / (tNorm * sqrt(varSum))
        }

        companion object {
            /** Null when [t] has no texture where [weights] count. */
            operator fun invoke(t: Luma, weights: Luma, win: Luma): Correlator? {
                val q = weights.px
                var qSum = 0.0
                var qtSum = 0.0
                for (i in q.indices) { qSum += q[i]; qtSum += q[i] * t.px[i] }
                if (qSum <= 0) return null
                val mean = (qtSum / qSum).toFloat()
                val qt = FloatArray(q.size) { q[it] * (t.px[it] - mean) }
                var ss = 0.0
                for (i in q.indices) ss += qt[i].toDouble() * (t.px[i] - mean)
                if (sqrt(ss / qSum) < MIN_STD) return null
                return Correlator(t, q, qt, qSum, sqrt(ss), win)
            }
        }
    }
}
