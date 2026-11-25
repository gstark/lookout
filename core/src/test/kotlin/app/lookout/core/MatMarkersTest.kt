package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatMarkersTest {
    /** The mat as printed: assets/measure-mat.svg, frame origin 22.5 mm from the page's top-left. */
    private val svg = File("../assets/measure-mat.svg").readText()
    private val origin = 22.5
    private val cell = MatMarkers.MARKER_MM / 6

    /** Where the frame corners (marker centers 0 to 3) land in a 1600 × 1200 photo: the mat lies turned, seen at an angle. */
    private val frameInPhoto = listOf(Pt(240.0, 1000.0), Pt(262.0, 190.0), Pt(1371.0, 232.0), Pt(1390.0, 1031.0))
    private val toImage = Fixtures.view(MatMarkers.FRAME_W_MM, MatMarkers.FRAME_H_MM, frameInPhoto)

    /** The inner bits of each marker, read from the black cells of the SVG as make_measure_mat.py draws them. */
    private fun svgPatterns(): List<Int> {
        val rect = Regex("""<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="#000"/>""")
        val cells = rect.findAll(svg).map { m -> m.groupValues.drop(1).map(String::toDouble) }.filter { abs(it[2] - (cell + 0.02)) < 1e-3 }.toList()
        return MatMarkers.CENTERS.map { c ->
            val x0 = origin + c.x - MatMarkers.MARKER_MM / 2
            val y0 = origin + c.y - MatMarkers.MARKER_MM / 2
            val black = Array(6) { BooleanArray(6) }
            for (r in cells) {
                val col = Math.round((r[0] - x0) / cell).toInt()
                val row = Math.round((r[1] - y0) / cell).toInt()
                if (col in 0..5 && row in 0..5 && abs(r[0] - (x0 + col * cell)) < 0.01 && abs(r[1] - (y0 + row * cell)) < 0.01) black[row][col] = true
            }
            assertTrue((0..5).all { black[0][it] && black[5][it] && black[it][0] && black[it][5] }, "the marker border is black")
            var bits = 0
            for (row in 1..4) for (col in 1..4) bits = (bits shl 1) or (if (black[row][col]) 0 else 1)
            bits
        }
    }

    /** The SVG drawn at [ppm] px per mm: its rects and lines, in page order. Text is left out. */
    private fun rasterizeSvg(ppm: Double): Pair<BufferedImage, Double> {
        val img = BufferedImage((215.9 * ppm).toInt(), (279.4 * ppm).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(ppm, ppm)
        fun attr(tag: String, name: String) = Regex("""\b$name="([^"]+)"""").find(tag)?.groupValues?.get(1)
        for (tag in Regex("""<(rect|line)\b[^>]*/>""").findAll(svg).map { it.value }) {
            if (tag.startsWith("<rect")) {
                g.color = Color.decode(attr(tag, "fill")!!.let { if (it.length == 4) "#" + it.drop(1).map { c -> "$c$c" }.joinToString("") else it })
                g.fill(Rectangle2D.Double(attr(tag, "x")?.toDouble() ?: 0.0, attr(tag, "y")?.toDouble() ?: 0.0, attr(tag, "width")!!.toDouble(), attr(tag, "height")!!.toDouble()))
            } else {
                g.color = Color.decode(attr(tag, "stroke")!!.let { if (it.length == 4) "#" + it.drop(1).map { c -> "$c$c" }.joinToString("") else it })
                g.stroke = BasicStroke(attr(tag, "stroke-width")!!.toFloat())
                g.draw(Line2D.Double(attr(tag, "x1")!!.toDouble(), attr(tag, "y1")!!.toDouble(), attr(tag, "x2")!!.toDouble(), attr(tag, "y2")!!.toDouble()))
            }
        }
        g.dispose()
        return img to ppm
    }

    /** A photo of the mat whose page looks like [page] (luma at page mm), on a gray desk. */
    private fun photo(page: (Double, Double) -> Double): AwtImg = Fixtures.plane(1600, 1200, toImage.inverse(), 3) { x, y ->
        val px = x + origin
        val py = y + origin
        if (px < 0 || py < 0 || px > 215.9 || py > 279.4) 90.0 else page(px, py)
    }

    /** A mat drawn from [MatMarkers.PATTERNS], with markers [hidden] left white. */
    private fun syntheticPage(hidden: Set<Int> = emptySet()): (Double, Double) -> Double = { px, py ->
        var v = 235.0
        MatMarkers.CENTERS.forEachIndexed { id, c ->
            val u = (px - origin - c.x + MatMarkers.MARKER_MM / 2) / cell
            val w = (py - origin - c.y + MatMarkers.MARKER_MM / 2) / cell
            if (id !in hidden && u >= 0 && w >= 0 && u < 6 && w < 6) {
                val col = u.toInt()
                val row = w.toInt()
                val white = row in 1..4 && col in 1..4 && (MatMarkers.PATTERNS[id] shr (15 - ((row - 1) * 4 + col - 1)) and 1) == 1
                v = if (white) 235.0 else 25.0
            }
        }
        v
    }

    private fun corner(r: Flatten.Result, i: Int) = Pt(r.corners[i].x * 1600, r.corners[i].y * 1200)

    @Test fun theEmbeddedPatternsMatchTheMatDesign() {
        assertEquals(MatMarkers.PATTERNS.toList(), svgPatterns())
    }

    @Test fun aSyntheticMatUnderPerspectiveIsFoundAndScaled() {
        val r = Flatten.run(AwtImageOps, photo(syntheticPage()), Flatten.Mat, Flatten.Options(circles = false))
        assertEquals(listOf(0, 1, 2, 3), r.markers)
        assertEquals(1024.0 / 230, r.pxPerMm, 1e-9)
        assertEquals(757, r.width)
        assertEquals(1024, r.height)
        for (i in 0..3) assertTrue(corner(r, i).dist(frameInPhoto[i]) < 0.7, "marker $i center is ${corner(r, i).dist(frameInPhoto[i])} px off")
        assertTrue(r.fitRmsMm!! < 0.05, "fit ${r.fitRmsMm} mm")
        assertTrue(r.warnings.isEmpty(), "${r.warnings}")
    }

    @Test fun theRealMatDesignIsFoundAndAPartOnItIsMeasuredInMatMm() {
        val (page, ppm) = rasterizeSvg(10.0)
        val pageLuma = Raster.of(page.getRGB(0, 0, page.width, page.height, null, 0, page.width), page.width, page.height)
        // A 12 mm dark washer on the mat, 60 mm right of marker 0 and 100 mm down.
        val r = Flatten.run(AwtImageOps, photo { px, py ->
            if (hypot(px - origin - 60, py - origin - 100) <= 6) 30.0 else pageLuma.at(px * ppm, py * ppm)
        }, Flatten.Mat, Flatten.Options(pxPerMm = 6.0))
        assertEquals(listOf(0, 1, 2, 3), r.markers)
        for (i in 0..3) assertTrue(corner(r, i).dist(frameInPhoto[i]) < 1.0, "marker $i center is ${corner(r, i).dist(frameInPhoto[i])} px off")
        assertTrue(r.fitRmsMm!! < 0.1, "fit ${r.fitRmsMm} mm")
        val washer = r.circles!!.single { it.dMm > 10 }
        assertTrue(hypot(washer.xMm - 60, washer.yMm - 100) < 0.3, "washer at ${washer.xMm},${washer.yMm}")
        assertTrue(abs(washer.dMm - 12) < 0.3, "washer d ${washer.dMm}")
    }

    @Test fun missingMarkersGiveAWarningOrAnError() {
        val three = Flatten.run(AwtImageOps, photo(syntheticPage(setOf(2))), Flatten.Mat, Flatten.Options(circles = false))
        assertEquals(listOf(0, 1, 3), three.markers)
        assertTrue(three.warnings.single().startsWith("Only 3 of 4 mat markers found (missing ID 2)"), "${three.warnings}")
        for (i in listOf(0, 1, 3)) assertTrue(corner(three, i).dist(frameInPhoto[i]) < 1.0)
        val one = Flatten.run(AwtImageOps, photo(syntheticPage(setOf(0, 1, 2))), Flatten.Mat, Flatten.Options(circles = false))
        assertEquals(listOf(3), one.markers)
        assertEquals(null, one.fitRmsMm, "one marker gives an exact map, so no residual")
        val none = assertFailsWith<LookoutError> { Flatten.run(AwtImageOps, photo(syntheticPage(setOf(0, 1, 2, 3))), Flatten.Mat, Flatten.Options()) }
        assertEquals("mat_not_found", none.code)
    }
}
