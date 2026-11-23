package app.lookout.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Crops, resizes and composes images for agents. All outputs stay inside [MAX_EDGE]. */
class Compositor(private val ops: ImageOps) {

    /** Scales [img] down so its long edge is at most [longEdge]. It never scales up. */
    fun fit(img: Img, longEdge: Int): Img {
        val edge = longEdge.coerceIn(16, MAX_EDGE)
        val long = max(img.width, img.height)
        if (long <= edge) return img
        val s = edge.toDouble() / long
        return ops.scale(img, max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()))
    }

    fun crop(img: Img, roi: NRect?): Img =
        if (roi == null || roi.isFull) img else ops.crop(img, roi.toPixels(img.width, img.height))

    /** Adds a footer band with [text] below the image. */
    fun stamp(img: Img, text: String): Img {
        val size = max(12f, img.width / 50f)
        val band = (size * 1.6f).roundToInt()
        val out = ops.canvas(img.width, img.height + band, Colors.BLACK)
        ops.drawImage(out, img, 0, 0)
        ops.drawText(out, text, (size / 2).roundToInt(), img.height + ((band - size) / 2).roundToInt(), size, Colors.WHITE)
        return out
    }

    /** Draws a faint labelled grid over a copy of [img]. */
    fun grid(img: Img, spec: GridSpec): Img {
        val out = copy(img)
        val stroke = max(1f, img.width / 640f)
        for (c in 1 until spec.cols) {
            val x = (c.toDouble() * img.width / spec.cols).roundToInt()
            ops.drawLine(out, x, 0, x, img.height, Colors.GRID, stroke)
        }
        for (r in 1 until spec.rows) {
            val y = (r.toDouble() * img.height / spec.rows).roundToInt()
            ops.drawLine(out, 0, y, img.width, y, Colors.GRID, stroke)
        }
        val size = max(10f, min(img.width / spec.cols, img.height / spec.rows) / 5f)
        for (c in 0 until spec.cols) for (r in 0 until spec.rows) {
            val label = spec.cellLabel(c, r)
            val x = (c.toDouble() * img.width / spec.cols).roundToInt() + 3
            val y = (r.toDouble() * img.height / spec.rows).roundToInt() + 3
            ops.drawRect(out, PixelRect(x - 1, y - 1, ops.textWidth(label, size) + 4, (size * 1.25f).roundToInt()), Colors.SHADE, 0f, fill = true)
            ops.drawText(out, label, x + 1, y, size, Colors.WHITE)
        }
        return out
    }

    /** Outlines [regions] (normalized) in red on a copy of [img]. */
    fun outline(img: Img, regions: List<NRect>): Img {
        if (regions.isEmpty()) return img
        val out = copy(img)
        val stroke = max(2f, img.width / 200f)
        for (r in regions) ops.drawRect(out, r.toPixels(img.width, img.height), Colors.RED, stroke)
        return out
    }

    /** Puts [a] and [b] side by side at the same height, each with a caption. */
    fun sideBySide(a: Img, aLabel: String, b: Img, bLabel: String, regions: List<NRect>, longEdge: Int = MAX_EDGE): Img {
        val gap = 8
        val half = (longEdge.coerceAtMost(MAX_EDGE) - gap) / 2
        val left = outline(fit(a, half), regions)
        val right = outline(fit(b, half), regions)
        val h = max(left.height, right.height)
        val size = max(12f, half / 40f)
        val band = (size * 1.6f).roundToInt()
        val out = ops.canvas(left.width + gap + right.width, h + band, Colors.DARK_BG)
        ops.drawImage(out, left, 0, 0)
        ops.drawImage(out, right, left.width + gap, 0)
        ops.drawText(out, aLabel, 6, h + ((band - size) / 2).roundToInt(), size, Colors.WHITE)
        ops.drawText(out, bLabel, left.width + gap + 6, h + ((band - size) / 2).roundToInt(), size, Colors.WHITE)
        return out
    }

    /** Lays out [tiles] in a grid, each with its caption, so the sheet's long edge is at most [longEdge]. */
    fun contactSheet(tiles: List<Pair<Img, String>>, longEdge: Int = MAX_EDGE): Img {
        require(tiles.isNotEmpty()) { "No tiles" }
        val n = tiles.size
        val cols = if (n <= 3) n else ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n.toDouble() / cols).toInt()
        val gap = 4
        val aspect = tiles[0].first.height.toDouble() / tiles[0].first.width
        val edge = longEdge.coerceAtMost(MAX_EDGE)
        var tileW = (edge - gap * (cols - 1)) / cols
        val size0 = max(11f, tileW / 22f)
        var band = (size0 * 1.5f).roundToInt()
        var tileH = (tileW * aspect).roundToInt()
        val totalH = rows * (tileH + band) + gap * (rows - 1)
        if (totalH > edge) {
            val s = edge.toDouble() / totalH
            tileW = (tileW * s).toInt()
            tileH = (tileW * aspect).roundToInt()
            band = (band * s).roundToInt().coerceAtLeast(12)
        }
        val size = band / 1.5f
        val out = ops.canvas(cols * tileW + gap * (cols - 1), rows * (tileH + band) + gap * (rows - 1), Colors.DARK_BG)
        tiles.forEachIndexed { i, (img, label) ->
            val x = (i % cols) * (tileW + gap)
            val y = (i / cols) * (tileH + band + gap)
            ops.drawImage(out, ops.scale(img, tileW, tileH), x, y)
            ops.drawText(out, label, x + 4, y + tileH + ((band - size) / 2).roundToInt(), size, Colors.WHITE)
        }
        return out
    }

    /** Draws [marks] on a copy of [img], as the phone draws them over its preview. */
    fun marks(img: Img, marks: List<PointMark>): Img {
        if (marks.isEmpty()) return img
        val out = copy(img)
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        val stroke = max(3f, img.width / 160f)
        val textSize = max(14f, img.width / 40f)
        // A dark line under each colored line keeps the mark visible on a light scene.
        fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Int) {
            ops.drawLine(out, x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(), Colors.SHADE, stroke * 2f)
            ops.drawLine(out, x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(), color, stroke)
        }
        fun label(text: String, x: Double, y: Double) {
            val tw = ops.textWidth(text, textSize)
            val bh = (textSize * 1.4f).roundToInt()
            val bx = x.roundToInt().coerceIn(0, max(0, img.width - tw - 8))
            val by = y.roundToInt().coerceIn(0, max(0, img.height - bh))
            ops.drawRect(out, PixelRect(bx, by, tw + 8, bh), Colors.SHADE, 0f, fill = true)
            ops.drawText(out, text, bx + 4, by + ((bh - textSize) / 2).roundToInt(), textSize, Colors.WHITE)
        }
        for (m in marks) {
            val color = Marks.COLORS[m.color] ?: Colors.RED
            val r = m.rect
            val from = m.from
            val to = m.to
            when {
                r != null -> {
                    val (x1, y1, x2, y2) = listOf(r.x * w, r.y * h, (r.x + r.w) * w, (r.y + r.h) * h)
                    line(x1, y1, x2, y1, color); line(x2, y1, x2, y2, color); line(x2, y2, x1, y2, color); line(x1, y2, x1, y1, color)
                    m.label?.let { label(it, x1, if (y1 > textSize * 1.6) y1 - textSize * 1.5 else y2 + 4) }
                }
                from != null && to != null -> {
                    val (tx, ty, hx, hy) = listOf(from.x * w, from.y * h, to.x * w, to.y * h)
                    line(tx, ty, hx, hy, color)
                    Marks.arrowWings(tx, ty, hx, hy, stroke * 6.0).forEach { (ex, ey) -> line(hx, hy, ex, ey, color) }
                    m.label?.let { label(it, tx - ops.textWidth(it, textSize) / 2.0, if (ty < hy) ty - textSize * 1.6 else ty + 4) }
                }
                to != null -> m.label?.let { label(it, to.x * w - ops.textWidth(it, textSize) / 2.0, to.y * h - textSize * 0.7) }
            }
        }
        return out
    }

    private fun copy(img: Img): Img {
        val out = ops.canvas(img.width, img.height, Colors.BLACK)
        ops.drawImage(out, img, 0, 0)
        return out
    }

    companion object {
        /** Vision models downsample larger images, so bigger frames only cost bandwidth. */
        const val MAX_EDGE = 1568
        const val DEFAULT_EDGE = 1024
    }
}
