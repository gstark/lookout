package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** An 80 × 50 mm plate with holes of known position and size, photographed under perspective. */
object Plate {
    const val W = 80.0
    const val H = 50.0
    /** x, y, diameter in mm. Holes show the dark desk; the pad is light on the plate. */
    val holes = listOf(Triple(4.0, 4.5, 3.2), Triple(75.5, 4.0, 3.2), Triple(76.0, 46.0, 2.5), Triple(4.0, 45.5, 4.0))
    val pad = Triple(40.0, 25.0, 6.0)
    val corners = listOf(Pt(301.3, 203.4), Pt(1290.7, 241.9), Pt(1338.2, 893.6), Pt(262.5, 851.1))

    fun paint(x: Double, y: Double): Double {
        if (x < 0 || y < 0 || x > W || y > H) return 45.0
        fun inCircle(c: Triple<Double, Double, Double>) = hypot(x - c.first, y - c.second) <= c.third / 2
        if (holes.any(::inCircle)) return 45.0
        if (inCircle(pad)) return 235.0
        // Shapes that are not round: a square, an ellipse 2:1, a triangle and a slot.
        if (abs(x - 18) <= 2 && abs(y - 25) <= 2) return 40.0
        if (((x - 60) / 3).let { it * it } + ((y - 25) / 1.5).let { it * it } <= 1) return 40.0
        if (y in 10.0..14.0 && abs(x - 40) <= (y - 10) / 2) return 40.0
        if (y in 36.0..38.0 && x in 30.0..44.0) return 40.0
        return 150.0
    }

    val toImage = Fixtures.view(W, H, corners)
    val photo: AwtImg by lazy { Fixtures.plane(1600, 1200, toImage.inverse(), 3, ::paint) }

    fun norm(p: Pt) = NPoint(p.x / 1600, p.y / 1200)
}

class FlattenTest {
    private val approx = listOf(Pt(306.0, 199.0), Pt(1284.0, 246.0), Pt(1344.0, 888.0), Pt(256.0, 857.0)).map(Plate::norm)

    @Test fun holesOnAPlateUnderPerspectiveAreMeasuredWithin02Mm() {
        val r = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(approx, Plate.W, Plate.H), Flatten.Options())
        assertEquals(12.8, r.pxPerMm, 1e-9, "the long edge is 1024 px")
        assertEquals(1024, r.width)
        assertEquals(640, r.height)
        val holes = r.circles!!
        assertEquals(5, holes.size, "4 holes and the pad, and none of the other shapes: ${holes.map { "${it.xMm},${it.yMm},${it.dMm}" }}")
        val expected = listOf(Plate.holes[0], Plate.holes[1], Plate.pad, Plate.holes[3], Plate.holes[2])
        holes.zip(expected).forEach { (got, want) ->
            assertTrue(hypot(got.xMm - want.first, got.yMm - want.second) < 0.2, "center ${got.xMm},${got.yMm} for $want")
            assertTrue(abs(got.dMm - want.third) < 0.2, "diameter ${got.dMm} for $want")
            assertTrue(got.rmsMm < 0.05)
        }
        assertEquals(listOf(true, true, false, true, true), holes.map { it.dark })
        r.corners.forEachIndexed { i, c -> assertTrue(hypot(c.x * 1600 - Plate.corners[i].x, c.y * 1200 - Plate.corners[i].y) < 0.5, "corner $i") }
        assertTrue(r.movedMm!!.all { it in 0.2..1.0 }, "the given corners were 5 to 7 px off: ${r.movedMm}")
        assertTrue(r.sides!!.all { it.found })
        assertTrue(r.warnings.isEmpty(), "${r.warnings}")
    }

    @Test fun withoutRefineTheGivenCornersAreUsedAsTheyAre() {
        val exact = Plate.corners.map(Plate::norm)
        val r = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(exact, Plate.W, Plate.H, refine = false), Flatten.Options(circles = false))
        assertEquals(null, r.sides)
        assertEquals(null, r.circles)
        assertTrue(r.movedMm!!.all { it < 1e-6 })
    }

    @Test fun marginWidensTheViewAndKeepsMmFromTheCorner() {
        val r = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(approx, Plate.W, Plate.H), Flatten.Options(pxPerMm = 10.0, marginMm = 5.0))
        assertEquals(900, r.width)
        assertEquals(600, r.height)
        val first = r.circles!!.first()
        assertTrue(hypot(first.xMm - 4.0, first.yMm - 4.5) < 0.2, "mm stay from the top-left corner: ${first.xMm},${first.yMm}")
    }

    @Test fun pxPerMmIsCappedSoTheViewFits() {
        val r = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(approx, Plate.W, Plate.H), Flatten.Options(pxPerMm = 50.0, circles = false))
        assertEquals(Compositor.MAX_EDGE / 80.0, r.pxPerMm, 1e-9)
        assertEquals(Compositor.MAX_EDGE, r.width)
        assertTrue(r.warnings.any { "lowered" in it })
    }

    @Test fun swappedSizesAndOddCornersGiveWarnings() {
        val swapped = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(approx, Plate.H, Plate.W), Flatten.Options(circles = false))
        assertTrue(swapped.warnings.any { "other way round" in it }, "${swapped.warnings}")
        val skew = listOf(NPoint(0.1, 0.1), NPoint(0.9, 0.15), NPoint(0.85, 0.35), NPoint(0.15, 0.9))
        assertTrue(Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(skew, 80.0, 50.0, refine = false), Flatten.Options(circles = false))
            .warnings.any { "far from a rectangle" in it })
        val inside = listOf(NPoint(0.1, 0.1), NPoint(0.9, 0.1), NPoint(0.5, 0.3), NPoint(0.1, 0.9))
        assertEquals("invalid_corners", assertFailsWith<LookoutError> { Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(inside, 80.0, 50.0), Flatten.Options()) }.code)
    }

    @Test fun cornersParseFromPointsCellsAndOneFlatList() {
        assertEquals(listOf(NPoint(0.1, 0.2), NPoint(0.3, 0.4), NPoint(0.0625, 0.0833333333333333), NPoint(0.5, 0.6)).map { round3(it.x) to round3(it.y) },
            Flatten.parseCorners(listOf("0.1,0.2", "0.3, 0.4", "A1", "0.5,0.6")).map { round3(it.x) to round3(it.y) })
        assertEquals(4, Flatten.parseCorners(listOf("0.1", "0.2", "0.3", "0.2", "0.3", "0.4", "0.1", "0.4")).size, "a REST list splits at every comma")
        assertEquals("invalid_param", assertFailsWith<LookoutError> { Flatten.parseCorners(listOf("0.1,0.2", "0.3,0.4")) }.code)
    }

    @Test fun readingOrderIsRowsTopToBottomThenLeftToRight() {
        fun h(x: Double, y: Double) = Flatten.Hole(x, y, 3.0, 0.0, true)
        val order = Flatten.readingOrder(listOf(h(70.0, 5.4), h(5.0, 45.0), h(4.0, 4.6), h(40.0, 25.0), h(75.0, 44.6)))
        assertEquals(listOf(4.0, 70.0, 40.0, 5.0, 75.0), order.map { it.xMm })
    }

    @Test fun theDrawnViewMarksEachCircle() {
        val r = Flatten.run(AwtImageOps, Plate.photo, Flatten.Corners(approx, Plate.W, Plate.H), Flatten.Options())
        val img = Flatten.draw(AwtImageOps, r) as AwtImg
        val c = r.circles!![0]
        val rgb = img.image.getRGB(((c.xMm + 1.0) * r.pxPerMm).roundToInt(), (c.yMm * r.pxPerMm).roundToInt())
        assertTrue((rgb shr 16 and 0xFF) > 200 && (rgb shr 8 and 0xFF) < 120, "a red cross on the first hole: ${Integer.toHexString(rgb)}")
    }
}
