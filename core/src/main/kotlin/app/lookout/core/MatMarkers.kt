package app.lookout.core

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the four ArUco markers of the Lookout measuring mat (scripts/make_measure_mat.py, assets/measure-mat.svg).
 * It knows only what the mat needs: the 4 × 4 bit patterns of IDs 0 to 3 of the DICT_4X4_50 dictionary.
 *
 * Method:
 * - The frame is scaled to at most [WORK_EDGE] px. A pixel is dark when it is [OFFSET] below the mean of a window
 *   of about 1/20 of the frame around it.
 * - Each 8-connected dark region of a plausible size is a candidate. Its 4 corners are the extreme points of the
 *   region: the point farthest from its centroid, the point farthest from that one, and the points farthest on
 *   each side of the line between them. They must form a convex quad that the region fills well.
 * - A homography from the 6 × 6 cell grid of a marker to the quad gives the mean luma at the middle of each cell.
 *   The border cells must be dark. The inner 16 cells, read from each of the 4 corners as the first, must match a
 *   known pattern with at most [MAX_BIT_ERRORS] wrong bits. The corner that matches is the marker's top-left.
 * - Each side of a found marker is then snapped to its outer edge at full resolution (see [Rectify.refine]).
 */
object MatMarkers {
    /** The mat frame, marker center to marker center, in mm. */
    const val FRAME_W_MM = 170.0
    const val FRAME_H_MM = 230.0
    /** Marker size with its black border, in mm. */
    const val MARKER_MM = 25.0

    /**
     * The inner 4 × 4 bits of markers 0 to 3, row by row from the top-left, most significant bit first, 1 for white.
     * They come from DICT_4X4_50 through scripts/make_measure_mat.py, read back from the cells of
     * assets/measure-mat.svg. MatMarkersTest checks them against that file.
     */
    val PATTERNS = intArrayOf(0xB532, 0x0F9A, 0x332D, 0x9946)

    /** The centers of markers 0 to 3 in mat mm: clockwise from the top left. */
    val CENTERS = listOf(Pt(0.0, 0.0), Pt(FRAME_W_MM, 0.0), Pt(FRAME_W_MM, FRAME_H_MM), Pt(0.0, FRAME_H_MM))

    /** The corners of marker [id] in mat mm: its top-left, top-right, bottom-right and bottom-left. */
    fun cornersMm(id: Int): List<Pt> {
        val c = CENTERS[id]
        val h = MARKER_MM / 2
        return listOf(Pt(c.x - h, c.y - h), Pt(c.x + h, c.y - h), Pt(c.x + h, c.y + h), Pt(c.x - h, c.y + h))
    }

    /** A found marker: its [id] and its corners in source px, in the order of [cornersMm]. */
    class Marker(val id: Int, val corners: List<Pt>)

    const val WORK_EDGE = 1600
    const val OFFSET = 7.0
    const val MAX_BIT_ERRORS = 1
    /** The smallest marker side that can be read, in working px. */
    private const val MIN_SIDE = 16
    /** The marker region must fill at least this share of its quad (the border alone fills 0.56). */
    private const val MIN_FILL = 0.45
    /** At full resolution, a side is searched within this fraction of its length: well inside the 3 mm quiet zone. */
    private const val REFINE_RADIUS = 0.07
    /** The full-resolution patch of one marker is scaled to at most this many px on its long edge. */
    private const val REFINE_EDGE = 600

    /** The mat markers in [img], at most one of each ID. */
    fun find(ops: ImageOps, img: Img): List<Marker> {
        val s = min(1.0, WORK_EDGE.toDouble() / max(img.width, img.height))
        val work = Rectify.pull(ops, img, PixelRect(0, 0, img.width, img.height), s)
        val l = work.luma()
        val toSource = work.toSource
        val best = HashMap<Int, Pair<Int, List<Pt>>>()
        for (quad in quads(l)) {
            val (id, errors, corners) = decode(l, quad) ?: continue
            if ((best[id]?.first ?: Int.MAX_VALUE) > errors) best[id] = errors to corners
        }
        return best.toSortedMap().map { (id, found) ->
            Marker(id, refine(ops, img, found.second.map(toSource::map)))
        }
    }

    /** [quad] (source px) snapped to the outer edges of the marker in a full-resolution patch of [img]. */
    private fun refine(ops: ImageOps, img: Img, quad: List<Pt>): List<Pt> {
        val side = quad.indices.maxOf { quad[it].dist(quad[(it + 1) % 4]) }
        val box = Rectify.box(quad, side * 0.2, img.width, img.height) ?: return quad
        val patch = Rectify.pull(ops, img, box, min(1.0, REFINE_EDGE.toDouble() / max(box.w, box.h)))
        val refined = Rectify.refine(patch.luma(), quad.map(patch.toWork::map), REFINE_RADIUS)
        val back = patch.toSource
        return refined.corners.map(back::map)
    }

    /** Convex dark quads in [l] that could be markers, each clockwise on the image. */
    private fun quads(l: Raster): List<List<Pt>> {
        val w = l.w
        val h = l.h
        val half = max(5, max(w, h) / 40)
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += l.v[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val dark = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val x0 = max(0, x - half); val x1 = min(w, x + half + 1)
            val y0 = max(0, y - half); val y1 = min(h, y + half + 1)
            val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            l.v[i] < s / ((x1 - x0) * (y1 - y0)) - OFFSET
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<List<Pt>>()
        val maxSide = max(w, h) / 3
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            val members = ArrayList<Int>()
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (top > 0) {
                val i = stack[--top]
                members += i
                val x = i % w
                val y = i / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (dark[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < MIN_SIDE || max(bw, bh) > maxSide) continue
            val quad = extremeQuad(members.map { Pt(it % w + 0.5, it / w + 0.5) }) ?: continue
            if (!Rectify.convex(quad)) continue
            val sides = quad.indices.map { quad[it].dist(quad[(it + 1) % 4]) }
            if (sides.min() < MIN_SIDE || sides.max() > 4 * sides.min()) continue
            if (members.size < MIN_FILL * area(quad)) continue
            // The pixel centers lie half a pixel inside the region's outline.
            val c = Pt(quad.sumOf { it.x } / 4, quad.sumOf { it.y } / 4)
            out += quad.map { p -> p + (p - c) * (0.7 / p.dist(c)) }
        }
        return out
    }

    /** The 4 extreme points of [pts], clockwise on the image, or null when they do not span a quad. */
    private fun extremeQuad(pts: List<Pt>): List<Pt>? {
        val c = Pt(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)
        val a = pts.maxBy { it.dist(c) }
        val b = pts.maxBy { it.dist(a) }
        fun side(p: Pt) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        val p = pts.maxBy { side(it) }
        val q = pts.minBy { side(it) }
        val len = a.dist(b)
        if (side(p) < 0.2 * len * len / 2 || -side(q) < 0.2 * len * len / 2) return null
        return listOf(a, p, b, q).sortedBy { atan2(it.y - c.y, it.x - c.x) }
    }

    private fun area(q: List<Pt>) = kotlin.math.abs(q.indices.sumOf { q[it].x * q[(it + 1) % 4].y - q[(it + 1) % 4].x * q[it].y }) / 2

    /** The (id, bit errors, corners from the marker's top-left) that [quad] shows, or null when it is no mat marker. */
    private fun decode(l: Raster, quad: List<Pt>): Triple<Int, Int, List<Pt>>? {
        var found: Triple<Int, Int, List<Pt>>? = null
        for (k in 0 until 4) {
            val q = List(4) { quad[(k + it) % 4] }
            val cells = cells(l, q)
            val lo = cells.min()
            val hi = cells.max()
            if (hi - lo < 4 * OFFSET) return null
            val t = (lo + hi) / 2
            val borderLight = (0 until 36).count { i -> (i / 6 == 0 || i / 6 == 5 || i % 6 == 0 || i % 6 == 5) && cells[i] > t }
            if (borderLight > 1) return null
            var bits = 0
            for (r in 1..4) for (c in 1..4) bits = (bits shl 1) or (if (cells[r * 6 + c] > t) 1 else 0)
            for (id in PATTERNS.indices) {
                val errors = Integer.bitCount(bits xor PATTERNS[id])
                if (errors <= MAX_BIT_ERRORS && (found == null || errors < found.second)) found = Triple(id, errors, q)
            }
        }
        return found
    }

    /** The mean luma at the middle of each of the 6 × 6 cells of the marker [q] (clockwise from its top-left). */
    private fun cells(l: Raster, q: List<Pt>): DoubleArray {
        val toImage = Homography.fit(listOf(Pt(0.0, 0.0), Pt(6.0, 0.0), Pt(6.0, 6.0), Pt(0.0, 6.0)), q)
        return DoubleArray(36) { i ->
            val r = i / 6
            val c = i % 6
            var sum = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val p = toImage.map(c + 0.5 + dx * 0.2, r + 0.5 + dy * 0.2)
                sum += l.at(p.x, p.y)
            }
            sum / 9
        }
    }
}
