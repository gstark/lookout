package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagingTest {
    private val detector = ChangeDetector(AwtImageOps)
    private val comp = Compositor(AwtImageOps)

    @Test fun staticSceneWithNoiseScoresNearZero() {
        val a = detector.thumb(Fixtures.scene(noiseSeed = 1))
        val b = detector.thumb(Fixtures.scene(noiseSeed = 2))
        val r = detector.compare(a, b)
        assertTrue(r.score < 0.001, "score ${r.score}")
        assertTrue(r.regions.isEmpty())
    }

    @Test fun handEnteringIsDetectedOnTheLeft() {
        val r = detector.compare(detector.thumb(Fixtures.scene()), detector.thumb(Fixtures.scene(handX = 0.3)))
        assertTrue(r.score > 0.05, "score ${r.score}")
        val box = r.regions.first()
        assertTrue(box.x < 0.05 && box.x + box.w in 0.25..0.4, "box $box")
        assertTrue(box.y in 0.25..0.4, "box $box")
    }

    @Test fun ledToggleIsVisibleOnlyInsideItsRoi() {
        val led = NRect(0.6, 0.35, 0.1, 0.15)
        val whole = detector.compare(detector.thumb(Fixtures.scene()), detector.thumb(Fixtures.scene(ledOn = true)))
        val inRoi = detector.compare(detector.thumb(Fixtures.scene(), led), detector.thumb(Fixtures.scene(ledOn = true), led))
        assertTrue(whole.score < 0.02, "whole ${whole.score}")
        assertTrue(inRoi.score > 0.1, "roi ${inRoi.score}")
    }

    @Test fun fitNeverUpscalesAndCaps() {
        assertEquals(640, comp.fit(Fixtures.scene(), 1024).width)
        val big = Fixtures.scene(4000, 3000)
        assertEquals(1568, comp.fit(big, 5000).width)
        assertEquals(1024, comp.fit(big, 1024).width)
        assertEquals(768, comp.fit(big, 1024).height)
    }

    @Test fun contactSheetFitsInsideMaxEdge() {
        for (n in listOf(1, 2, 5, 12, 36)) {
            val sheet = comp.contactSheet(List(n) { Fixtures.scene() to "12:00:0$it" })
            assertTrue(sheet.width <= Compositor.MAX_EDGE && sheet.height <= Compositor.MAX_EDGE, "n=$n ${sheet.width}x${sheet.height}")
        }
    }

    @Test fun sheetColumnsFollowTheTileShape() {
        val wide = Compositor.sheetLayout(40, 0.02)
        assertTrue(wide.cols in 1..2, "40 wide strips stack, got ${wide.cols} columns")
        val tall = Compositor.sheetLayout(40, 50.0)
        assertEquals(1, tall.rows, "40 tall strips sit side by side, got ${tall.cols} columns")
        for (l in listOf(wide, tall)) assertTrue(l.tileEdge >= Compositor.MIN_TILE_EDGE)
        val strips = comp.contactSheet(List(40) { Fixtures.scene(640, 24) to "t" })
        assertTrue(strips.width <= Compositor.MAX_EDGE && strips.height <= Compositor.MAX_EDGE, "${strips.width}x${strips.height}")
        assertTrue(Compositor.maxTiles(0.5625, 400, 200) in 10..30, "a narrow sheet fits fewer full-frame tiles")
    }

    /** The layout before the area budget: ceil(sqrt(n)) columns, tile width W / cols, scaled down to fit the height. Gives the sheet area and tile width. */
    private fun oldSheet(n: Int, aspect: Double): Pair<Long, Int> {
        val e = Compositor.MAX_EDGE
        val cols = if (n <= 3) n else kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
        val rows = (n + cols - 1) / cols
        var tileW = (e - 4 * (cols - 1)) / cols
        var band = (maxOf(11f, tileW / 22f) * 1.5f).roundToInt()
        var tileH = (tileW * aspect).roundToInt()
        val totalH = rows * (tileH + band) + 4 * (rows - 1)
        if (totalH > e) {
            val s = e.toDouble() / totalH
            tileW = (tileW * s).toInt()
            tileH = (tileW * aspect).roundToInt()
            band = (band * s).roundToInt().coerceAtLeast(12)
        }
        val area = (cols * tileW + 4 * (cols - 1)).toLong() * (rows * (tileH + band) + 4 * (rows - 1))
        return area to tileW
    }

    @Test fun defaultSheetsCostNoMoreThanBefore() {
        for (aspect in listOf(0.75, 9.0 / 16, 16.0 / 9)) {
            val (oldArea, oldW) = oldSheet(12, aspect)
            val l = Compositor.sheetLayout(12, aspect)
            assertTrue(l.area <= oldArea, "aspect $aspect: area ${l.area} > old $oldArea")
            assertTrue(l.tileW >= oldW * 0.97, "aspect $aspect: tile ${l.tileW} px wide, old $oldW")
            assertTrue(l.width <= Compositor.MAX_EDGE && l.height <= Compositor.MAX_EDGE)
        }
    }

    @Test fun aTileLabelIsNeverWiderThanItsTile() {
        val drawn = mutableListOf<Pair<String, Float>>()
        val ops = object : ImageOps by AwtImageOps {
            override fun drawText(dst: Img, text: String, x: Int, y: Int, sizePx: Float, color: Int) {
                drawn += text to sizePx
                AwtImageOps.drawText(dst, text, x, y, sizePx, color)
            }
        }
        val tall = Compositor(ops).contactSheet(List(40) { Fixtures.scene(24, 480) to "t=12345ms #67  sent a long command" }, Compositor.MAX_EDGE)
        val l = Compositor.sheetLayout(40, 480.0 / 24)
        assertTrue(tall.width <= Compositor.MAX_EDGE)
        for ((text, size) in drawn) {
            assertTrue(AwtImageOps.textWidth(text, size) <= l.tileW - 8, "'$text' at $size px is wider than a ${l.tileW} px tile")
            assertTrue(size >= Compositor.MIN_LABEL_SIZE)
            assertTrue(text.startsWith("t="), "the time stays first: '$text'")
        }
    }

    @Test fun sideBySideAndGridAndStampKeepSizes() {
        val s = comp.sideBySide(Fixtures.scene(), "a", Fixtures.scene(handX = 0.3), "b", listOf(NRect(0.0, 0.3, 0.3, 0.25)))
        assertTrue(s.width <= Compositor.MAX_EDGE)
        val g = comp.grid(Fixtures.scene(), GridSpec.DEFAULT)
        assertEquals(640, g.width)
        assertTrue(comp.stamp(Fixtures.scene(), "12:00:00 #1").height > 480)
    }

    @Test fun jpegRoundTrip() {
        val jpeg = AwtImageOps.encodeJpeg(Fixtures.scene(), 80)
        assertTrue(jpeg.size in 2_000..200_000)
        assertEquals(640, AwtImageOps.decode(jpeg).width)
    }
}
