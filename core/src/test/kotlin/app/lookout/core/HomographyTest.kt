package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HomographyTest {
    private val square = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 60.0), Pt(0.0, 60.0))
    private val photo = listOf(Pt(112.3, 80.7), Pt(531.9, 101.2), Pt(560.4, 402.5), Pt(88.1, 371.6))

    @Test fun fourPairsMapExactlyAndBack() {
        val h = Homography.fit(square, photo)
        for (i in 0 until 4) {
            assertTrue(h.map(square[i]).dist(photo[i]) < 1e-9, "corner $i")
            assertTrue(h.inverse().map(photo[i]).dist(square[i]) < 1e-9, "corner $i back")
        }
        val p = Pt(37.0, 21.0)
        assertTrue(h.inverse().map(h.map(p)).dist(p) < 1e-9, "a point inside goes there and back")
        assertTrue((h.inverse() * h).map(p).dist(p) < 1e-9)
    }

    @Test fun manyPairsGiveTheLeastSquaresFitAndItsResidual() {
        val truth = Homography.fit(square, photo)
        val plane = (0..4).flatMap { i -> (0..3).map { j -> Pt(i * 25.0, j * 20.0) } }
        val rnd = java.util.Random(3)
        val seen = plane.map { truth.map(it) + Pt(rnd.nextGaussian() * 0.3, rnd.nextGaussian() * 0.3) }
        val fit = Homography.fit(plane, seen)
        val rms = fit.rms(plane, seen)
        assertTrue(rms in 0.1..0.5, "residual $rms px from 0.3 px noise")
        assertTrue(fit.map(Pt(50.0, 30.0)).dist(truth.map(Pt(50.0, 30.0))) < 0.3)
    }

    @Test fun pointsOnALineAreRejected() {
        val line = listOf(Pt(0.0, 0.0), Pt(1.0, 1.0), Pt(2.0, 2.0), Pt(3.0, 3.0))
        assertEquals("degenerate_points", assertFailsWith<LookoutError> { Homography.fit(line, photo) }.code)
    }

    @Test fun aCheckerboardUnderPerspectiveWarpsBackSquareWithinAPixel() {
        val cell = 20.0
        val toImage = Fixtures.view(160.0, 120.0, photo)
        val board = Fixtures.plane(640, 480, toImage.inverse()) { x, y ->
            if (x < 0 || y < 0 || x >= 160 || y >= 120) 128.0
            else if (((x / cell).toInt() + (y / cell).toInt()) % 2 == 0) 220.0 else 35.0
        }
        val ppm = 4.0
        val src = Rectify.pull(AwtImageOps, board, PixelRect(0, 0, 640, 480), 1.0)
        val view = Rectify.warp(src, toImage * Homography.scaling(1 / ppm, 1 / ppm), 640, 480, 0)
        val l = Raster.of(view, 640, 480)
        var worst = 0.0
        for (k in 0 until 6) {
            val y = ((k + 0.5) * cell * ppm)
            val profile = DoubleArray(640) { l.at(it + 0.5, y) }
            val found = (Rectify.edges(profile, 1) + Rectify.edges(profile, -1)).filter { it.step > 80 && it.pos > 4 && it.pos < profile.size - 5 }.map { it.pos + 0.5 }.sorted()
            assertEquals(7, found.size, "row $k has 7 inner edges")
            found.forEachIndexed { i, x -> worst = maxOf(worst, abs(x - (i + 1) * cell * ppm)) }
        }
        for (k in 0 until 8) {
            val x = ((k + 0.5) * cell * ppm)
            val profile = DoubleArray(480) { l.at(x, it + 0.5) }
            val found = (Rectify.edges(profile, 1) + Rectify.edges(profile, -1)).filter { it.step > 80 && it.pos > 4 && it.pos < profile.size - 5 }.map { it.pos + 0.5 }.sorted()
            assertEquals(5, found.size, "column $k has 5 inner edges: $found")
            found.forEachIndexed { i, yy -> worst = maxOf(worst, abs(yy - (i + 1) * cell * ppm)) }
        }
        assertTrue(worst < 1.0, "worst edge is ${(worst * 100).roundToInt() / 100.0} px off the square grid")
    }
}
