package app.lookout.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    /** Draws [text] on a dark band at the bottom center of a copy of [img]. The bottom edge stays clear of grid labels. */
    fun caption(img: Img, text: String): Img {
        val out = copy(img)
        val pad = max(4, img.width / 120)
        var size = max(12f, img.width / 32f)
        val room = img.width - pad * 4
        val tw0 = ops.textWidth(text, size)
        if (tw0 > room) size = max(8f, size * room / tw0)
        val tw = ops.textWidth(text, size)
        val bw = tw + pad * 2
        val bh = (size * 1.4f).roundToInt()
        val bx = max(0, (img.width - bw) / 2)
        val by = max(0, img.height - bh - pad)
        ops.drawRect(out, PixelRect(bx, by, bw, bh), Colors.SHADE, 0f, fill = true)
        ops.drawText(out, text, bx + pad, by + ((bh - size) / 2).roundToInt(), size, Colors.CAPTION)
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

    /**
     * Draws normalized coordinate ruler ticks along the edges of a copy of [img], labelled in
     * full-frame coordinates for [region] (also when [img] is a crop).
     */
    fun ticks(img: Img, region: NRect = NRect.FULL): Img = ticks(img, region.x, region.x + region.w, region.y, region.y + region.h)

    /** Draws ruler ticks along the edges of a copy of [img], labelled from [x0] to [x1] across and [y0] to [y1] down, in any unit. */
    fun ticks(img: Img, x0: Double, x1: Double, y0: Double, y1: Double): Img {
        val out = copy(img)
        val xAxis = Ticks.axis(x0, x1)
        val yAxis = Ticks.axis(y0, y1)
        val size = (min(img.width, img.height) / 40f).coerceIn(9f, 13f)
        val majorLen = max(6, (size * 0.65f).roundToInt())
        val minorLen = max(3, (majorLen * 0.55f).roundToInt())
        val topBandH = min(img.height, majorLen + (size * 1.25f).roundToInt() + 2)
        val maxLeftTw = yAxis.ticks.mapNotNull { it.label }.maxOfOrNull { ops.textWidth(it, size) } ?: 0
        val leftBandW = min(img.width, majorLen + maxLeftTw + 4)

        ops.drawRect(out, PixelRect(0, 0, img.width, topBandH), Colors.SHADE, 0f, fill = true)
        if (img.height > topBandH) {
            ops.drawRect(out, PixelRect(0, topBandH, leftBandW, img.height - topBandH), Colors.SHADE, 0f, fill = true)
        }

        val xMax = max(1, img.width - 1)
        val yMax = max(1, img.height - 1)
        var lastXRight = leftBandW + 2
        for (t in xAxis.ticks) {
            val px = ((t.value - x0) / (x1 - x0) * xMax).roundToInt().coerceIn(0, xMax)
            val len = if (t.major) majorLen else minorLen
            ops.drawLine(out, px, 0, px, len, Colors.WHITE, if (t.major) 1.5f else 1f)
            ops.drawLine(out, px, yMax, px, max(0, yMax - len), Colors.GRID, 1f)
            val label = t.label ?: continue
            val tw = ops.textWidth(label, size)
            val lx = (px - tw / 2).coerceIn(0, max(0, img.width - tw - 1))
            if (lx >= lastXRight) {
                ops.drawText(out, label, lx, majorLen + 1, size, Colors.WHITE)
                lastXRight = lx + tw + 4
            }
        }

        var lastYBottom = topBandH + 2
        val textH = (size * 1.2f).roundToInt()
        for (t in yAxis.ticks) {
            val py = ((t.value - y0) / (y1 - y0) * yMax).roundToInt().coerceIn(0, yMax)
            val len = if (t.major) majorLen else minorLen
            ops.drawLine(out, 0, py, len, py, Colors.WHITE, if (t.major) 1.5f else 1f)
            ops.drawLine(out, xMax, py, max(0, xMax - len), py, Colors.GRID, 1f)
            val label = t.label ?: continue
            val ly = (py - (size * 0.55f).roundToInt()).coerceIn(0, max(0, img.height - textH - 1))
            if (ly >= lastYBottom) {
                ops.drawText(out, label, majorLen + 2, ly, size, Colors.WHITE)
                lastYBottom = ly + textH + 2
            }
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

    /** Lays out [tiles] in a grid, each with its caption, so the sheet fits in [longEdge] × [longEdge]. */
    fun contactSheet(tiles: List<Pair<Img, String>>, longEdge: Int = MAX_EDGE): Img {
        require(tiles.isNotEmpty()) { "No tiles" }
        val l = sheetLayout(tiles.size, tiles[0].first.height.toDouble() / tiles[0].first.width, longEdge)
        val size = l.band / 1.5f
        val out = ops.canvas(l.width, l.height, Colors.DARK_BG)
        tiles.forEachIndexed { i, (img, label) ->
            val x = (i % l.cols) * (l.tileW + GAP)
            val y = (i / l.cols) * (l.tileH + l.band + GAP)
            ops.drawImage(out, ops.scale(img, l.tileW, l.tileH), x, y)
            val (text, fitted) = fitLabel(label, size, l.tileW - 8)
            ops.drawText(out, text, x + 4, y + l.tileH + ((l.band - fitted) / 2).roundToInt(), fitted, Colors.WHITE)
        }
        return out
    }

    /**
     * [label] at a text size that fits in [room] px: smaller text down to [MIN_LABEL_SIZE], then words dropped from
     * the end, then characters. Put the part that must stay first.
     */
    private fun fitLabel(label: String, size: Float, room: Int): Pair<String, Float> {
        val w0 = ops.textWidth(label, size)
        val s = if (w0 <= room) size else max(MIN_LABEL_SIZE, size * room / w0)
        var words = label.split(' ').filter { it.isNotEmpty() }
        var text = label
        while (ops.textWidth(text, s) > room && words.size > 1) {
            words = words.dropLast(1)
            text = words.joinToString(" ")
        }
        while (ops.textWidth(text, s) > room && text.length > 1) text = text.dropLast(if (text.endsWith("…")) 2 else 1) + "…"
        return text to s
    }

    /** A contact sheet grid: tiles of [tileW] × [tileH] px, each with a caption band of [band] px below it. */
    class SheetLayout(val cols: Int, val rows: Int, val tileW: Int, val tileH: Int, val band: Int) {
        val tileEdge: Int get() = max(tileW, tileH)
        val width: Int get() = cols * tileW + GAP * (cols - 1)
        val height: Int get() = rows * (tileH + band) + GAP * (rows - 1)
        val area: Long get() = width.toLong() * height
    }

    /**
     * Labels a time stack: [stack] has one line of pixels per frame, with time running down when [down], else
     * across. The time axis spans [fromMs]..[toMs] and gets tick labels in seconds; each of [marks] (t_ms and
     * label) is a thin line. The space axis keeps its scale unless it does not fit in [longEdge].
     */
    fun timeStack(stack: Img, down: Boolean, fromMs: Long, toMs: Long, marks: List<Pair<Long, String>>, longEdge: Int): Img {
        val edge = longEdge.coerceIn(MIN_STACK_TIME_PX, MAX_EDGE)
        val gutter = if (down) 44 else 20
        val space = min(if (down) stack.width else stack.height, edge - gutter)
        val time = (if (down) stack.height else stack.width).coerceIn(MIN_STACK_TIME_PX, edge)
        val out = if (down) ops.canvas(gutter + space, time, Colors.DARK_BG) else ops.canvas(time, space + gutter, Colors.DARK_BG)
        ops.drawImage(out, if (down) ops.scale(stack, space, time) else ops.scale(stack, time, space), if (down) gutter else 0, 0)
        val span = max(1L, toMs - fromMs)
        fun at(tMs: Long) = ((tMs - fromMs).toDouble() / span * (time - 1)).roundToInt()
        val size = 11f
        val step = TICKS_MS.firstOrNull { span / it <= 8 } ?: TICKS_MS.last()
        var tick = (fromMs + step - 1) / step * step
        while (tick <= toMs) {
            val c = at(tick)
            val text = if (step >= 1000) "${tick / 1000}s" else "${tick / 1000.0}s"
            val tw = ops.textWidth(text, size)
            if (down) {
                ops.drawLine(out, gutter - 5, c, gutter, c, Colors.WHITE, 1f)
                ops.drawText(out, text, 2, (c - 6).coerceIn(0, time - 13), size, Colors.WHITE)
            } else {
                ops.drawLine(out, c, space, c, space + 5, Colors.WHITE, 1f)
                ops.drawText(out, text, (c - tw / 2).coerceIn(0, max(0, time - tw)), space + 6, size, Colors.WHITE)
            }
            tick += step
        }
        for ((tMs, label) in marks) {
            val c = at(tMs)
            val tw = ops.textWidth(label, size)
            val (lx, ly) = if (down) gutter + 2 to (c + 2).coerceAtMost(time - 15) else (c + 2).coerceAtMost(max(0, time - tw - 4)) to 2
            if (down) ops.drawLine(out, gutter, c, gutter + space, c, Colors.CAPTION, 1f) else ops.drawLine(out, c, 0, c, space, Colors.CAPTION, 1f)
            ops.drawRect(out, PixelRect(lx, ly, tw + 4, 15), Colors.SHADE, 0f, fill = true)
            ops.drawText(out, label, lx + 2, ly + 1, size, Colors.CAPTION)
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
        /** The smallest long edge of a contact sheet tile that stays readable. */
        const val MIN_TILE_EDGE = 96
        /** The smallest text size of a tile label. */
        const val MIN_LABEL_SIZE = 9f
        /** The space between contact sheet tiles. */
        const val GAP = 4
        /** The area a sheet of thin strips may use, as a fraction of the sheet edge squared. A default 12-tile 16:9 sheet uses about 0.47. */
        const val MIN_AREA_FRACTION = 0.45
        /** The shortest time axis of a time stack, so a short window still shows its curve. */
        const val MIN_STACK_TIME_PX = 256
        private val TICKS_MS = listOf(100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L)

        /**
         * The grid for [n] tiles of [aspect] (height / width) inside [longEdge] × [longEdge]: the column count
         * that gives the largest tiles. Wide strips stack in few columns, tall strips sit side by side.
         * The token cost of a sheet is its area, so the area stays within that of a square grid
         * (ceil(sqrt(n)) columns), or [MIN_AREA_FRACTION] of the square when that is more, for thin strips.
         */
        fun sheetLayout(n: Int, aspect: Double, longEdge: Int = MAX_EDGE): SheetLayout {
            val edge = longEdge.coerceAtMost(MAX_EDGE)
            val square = if (n <= 3) n else kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
            val budget = max(fit(n, square, aspect, edge, Long.MAX_VALUE).area, (MIN_AREA_FRACTION * edge * edge).toLong())
            return (1..n).map { fit(n, it, aspect, edge, budget) }.maxWith(compareBy({ it.tileW }, { -it.area }))
        }

        /** The largest tiles for [n] tiles in [cols] columns inside [edge] × [edge] and [budget] px of area. */
        private fun fit(n: Int, cols: Int, aspect: Double, edge: Int, budget: Long): SheetLayout {
            val rows = (n + cols - 1) / cols
            fun layout(w: Int) = SheetLayout(cols, rows, w, max(1, (w * aspect).toInt()), band(w))
            val byWidth = (edge - GAP * (cols - 1)) / cols
            // The band shrinks with the tile, so the band of the widest tile keeps the height inside the edge.
            val byHeight = ((edge - GAP * (rows - 1) - rows * band(byWidth)) / (rows * aspect)).toInt()
            var lo = 1
            var hi = max(1, min(byWidth, byHeight))
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (layout(mid).area <= budget) lo = mid else hi = mid - 1
            }
            return layout(lo)
        }

        /** The most tiles of [aspect], up to [cap], that fit in [longEdge] with each at least [MIN_TILE_EDGE] on its long edge. */
        fun maxTiles(aspect: Double, longEdge: Int, cap: Int): Int =
            (cap downTo 1).firstOrNull { sheetLayout(it, aspect, longEdge).tileEdge >= MIN_TILE_EDGE } ?: 0

        /** The caption band under a tile: text of 11 to 16 px, so a row of wide strips does not spend its height on text. */
        private fun band(tileW: Int): Int = ((tileW / 22f).coerceIn(11f, 16f) * 1.5f).roundToInt()
    }
}
