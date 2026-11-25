package app.lookout.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in normalized coordinates (0..1) of a frame. */
@Serializable
data class NRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    init {
        if (x < 0.0 || y < 0.0 || w <= 0.0 || h <= 0.0 || x + w > 1.0 + EPS || y + h > 1.0 + EPS) {
            throw LookoutError("invalid_rect", 400, "Rect must lie inside 0..1 with positive size: $this")
        }
    }

    fun toPixels(width: Int, height: Int): PixelRect {
        val px = (x * width).roundToInt().coerceIn(0, width - 1)
        val py = (y * height).roundToInt().coerceIn(0, height - 1)
        val pw = (w * width).roundToInt().coerceIn(1, width - px)
        val ph = (h * height).roundToInt().coerceIn(1, height - py)
        return PixelRect(px, py, pw, ph)
    }

    /** Maps this rect, given relative to [outer], into the coordinates of the full frame. */
    fun within(outer: NRect): NRect = NRect(
        outer.x + x * outer.w,
        outer.y + y * outer.h,
        min(w * outer.w, 1.0 - (outer.x + x * outer.w)),
        min(h * outer.h, 1.0 - (outer.y + y * outer.h)),
    )

    /**
     * Maps this rect from full-frame coordinates into the coordinates of [crop], clipping to the
     * overlap. Returns null when this rect lies completely outside [crop].
     */
    fun relativeTo(crop: NRect): NRect? {
        if (crop.isFull) return this
        val x0 = max(x, crop.x)
        val y0 = max(y, crop.y)
        val x1 = min(x + w, crop.x + crop.w)
        val y1 = min(y + h, crop.y + crop.h)
        if (x1 - x0 <= EPS || y1 - y0 <= EPS) return null
        val nx = ((x0 - crop.x) / crop.w).coerceIn(0.0, 1.0)
        val ny = ((y0 - crop.y) / crop.h).coerceIn(0.0, 1.0)
        val nx1 = ((x1 - crop.x) / crop.w).coerceIn(0.0, 1.0)
        val ny1 = ((y1 - crop.y) / crop.h).coerceIn(0.0, 1.0)
        val nw = nx1 - nx
        val nh = ny1 - ny
        if (nw <= EPS || nh <= EPS) return null
        return NRect(nx, ny, min(nw, 1.0 - nx), min(nh, 1.0 - ny))
    }

    /** Rotates this rect clockwise by [degrees] (a multiple of 90), matching upright image rotation. */
    fun rotated(degrees: Int): NRect = when (((degrees % 360) + 360) % 360) {
        90 -> {
            val nx = (1.0 - (y + h)).coerceIn(0.0, 1.0)
            NRect(nx, x, min(h, 1.0 - nx), w)
        }
        180 -> {
            val nx = (1.0 - (x + w)).coerceIn(0.0, 1.0)
            val ny = (1.0 - (y + h)).coerceIn(0.0, 1.0)
            NRect(nx, ny, min(w, 1.0 - nx), min(h, 1.0 - ny))
        }
        270 -> {
            val ny = (1.0 - (x + w)).coerceIn(0.0, 1.0)
            NRect(y, ny, h, min(w, 1.0 - ny))
        }
        else -> this
    }

    val isFull: Boolean get() = x <= EPS && y <= EPS && w >= 1.0 - EPS && h >= 1.0 - EPS

    companion object {
        const val EPS = 1e-6
        val FULL = NRect(0.0, 0.0, 1.0, 1.0)

        fun clamped(x: Double, y: Double, w: Double, h: Double): NRect {
            val cx = x.coerceIn(0.0, 1.0 - 0.001)
            val cy = y.coerceIn(0.0, 1.0 - 0.001)
            return NRect(cx, cy, w.coerceIn(0.001, 1.0 - cx), h.coerceIn(0.001, 1.0 - cy))
        }
    }
}

data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** A labelled grid: columns are letters A.., rows are numbers 1.. . */
data class GridSpec(val cols: Int, val rows: Int) {
    init {
        if (cols !in 1..26 || rows !in 1..50) throw LookoutError("invalid_grid", 400, "Grid must be 1..26 columns by 1..50 rows")
    }

    fun columnLabel(index: Int): String = ('A' + index).toString()

    fun cellLabel(col: Int, row: Int): String = columnLabel(col) + (row + 1)

    /** Parses a cell like "C2" into a zero-based (col, row) pair. */
    fun parseCell(cell: String): Pair<Int, Int> {
        val m = CELL.matchEntire(cell.trim().uppercase())
            ?: throw LookoutError("invalid_cell", 400, "Cell must look like C2, got '$cell'")
        val col = m.groupValues[1][0] - 'A'
        val row = m.groupValues[2].toInt() - 1
        if (col !in 0 until cols || row !in 0 until rows) {
            throw LookoutError("invalid_cell", 400, "Cell '$cell' is outside the ${cols}x$rows grid")
        }
        return col to row
    }

    /** The rect that covers the cells from [from] to [to], inclusive, in any order. */
    fun span(from: String, to: String): NRect {
        val (c1, r1) = parseCell(from)
        val (c2, r2) = parseCell(to)
        val c0 = min(c1, c2)
        val r0 = min(r1, r2)
        val cn = max(c1, c2) - c0 + 1
        val rn = max(r1, r2) - r0 + 1
        return NRect(c0.toDouble() / cols, r0.toDouble() / rows, cn.toDouble() / cols, rn.toDouble() / rows)
    }

    override fun toString(): String = "${cols}x$rows"

    companion object {
        private val CELL = Regex("([A-Z])([0-9]{1,2})")
        private val SPEC = Regex("([0-9]{1,2})[xX]([0-9]{1,2})")
        val DEFAULT = GridSpec(8, 6)

        fun parse(spec: String): GridSpec {
            val m = SPEC.matchEntire(spec.trim())
                ?: throw LookoutError("invalid_grid", 400, "Grid must look like 8x6, got '$spec'")
            return GridSpec(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    }
}
