package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RectifyTest {
    private val truth = listOf(Pt(150.4, 101.7), Pt(492.2, 120.3), Pt(510.8, 371.9), Pt(131.6, 352.2))
    private val toImage = Fixtures.view(80.0, 50.0, truth)

    private fun photo(topEdge: Boolean = true) = Fixtures.plane(640, 480, toImage.inverse()) { x, y ->
        val inside = x in 0.0..80.0 && y in 0.0..50.0
        if (inside || (!topEdge && y < 0 && x in 0.0..80.0)) 200.0 else 50.0
    }

    @Test fun cornersComeInOrderWhateverTheInputOrder() {
        for (perm in listOf(listOf(0, 1, 2, 3), listOf(2, 0, 3, 1), listOf(3, 2, 1, 0), listOf(1, 3, 0, 2))) {
            assertEquals(truth, Rectify.order(perm.map { truth[it] }), "order $perm")
        }
    }

    @Test fun theTopIsTheSideNearerHorizontal() {
        // A tall rectangle turned 30 degrees: its short sides are nearer horizontal.
        val c = Pt(320.0, 240.0)
        val a = Math.toRadians(30.0)
        fun at(x: Double, y: Double) = Pt(c.x + x * kotlin.math.cos(a) - y * kotlin.math.sin(a), c.y + x * kotlin.math.sin(a) + y * kotlin.math.cos(a))
        val q = listOf(at(-50.0, -100.0), at(50.0, -100.0), at(50.0, 100.0), at(-50.0, 100.0))
        assertEquals(q, Rectify.order(listOf(q[2], q[0], q[3], q[1])))
    }

    @Test fun aQuadThatIsNotConvexIsRejected() {
        val q = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(30.0, 30.0), Pt(0.0, 100.0))
        assertEquals("invalid_corners", assertFailsWith<LookoutError> { Rectify.order(q) }.code)
        assertFalse(Rectify.convex(q))
    }

    @Test fun approximateCornersSnapToTheEdges() {
        val img = photo()
        val approx = listOf(Pt(155.0, 97.0), Pt(487.0, 125.0), Pt(516.0, 366.0), Pt(126.0, 357.0))
        val r = Rectify.refine(Raster.of(AwtImageOps.argb(img, 640, 480), 640, 480), approx, Flatten.REFINE_RADIUS)
        assertTrue(r.sides.all { it.found })
        r.corners.forEachIndexed { i, p ->
            assertTrue(approx[i].dist(truth[i]) > 5.0, "corner $i starts off")
            assertTrue(p.dist(truth[i]) < 0.5, "corner $i is ${p.dist(truth[i])} px from the truth")
        }
        assertTrue(r.sides.all { (it.rmsPx ?: 1.0) < 0.3 }, "edge points lie on a line: ${r.sides.map { it.rmsPx }}")
    }

    @Test fun aSideWithNoEdgeKeepsItsCornersAndSaysSo() {
        val img = photo(topEdge = false)
        val approx = listOf(Pt(155.0, 97.0), Pt(487.0, 125.0), Pt(516.0, 366.0), Pt(126.0, 357.0))
        val r = Rectify.refine(Raster.of(AwtImageOps.argb(img, 640, 480), 640, 480), approx, Flatten.REFINE_RADIUS)
        assertEquals(listOf(false, true, true, true), r.sides.map { it.found })
        assertEquals(approx[0], r.corners[0])
        assertEquals(approx[1], r.corners[1])
        assertTrue(r.corners[2].dist(truth[2]) < 0.5)
        assertTrue(r.corners[3].dist(truth[3]) < 0.5)
    }
}
