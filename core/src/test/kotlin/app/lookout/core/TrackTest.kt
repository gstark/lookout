package app.lookout.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackTest {
    /**
     * A profile of [n] pixels, each the area mean of a scene that is [lo] before [e] and [hi] after it,
     * with a linear ramp [blur] px wide centered on [e]. So the true edge position is [e].
     */
    private fun step(n: Int, e: Double, lo: Double = 40.0, hi: Double = 200.0, blur: Double = 0.0): DoubleArray = DoubleArray(n) { k ->
        val sub = 200
        var sum = 0.0
        for (i in 0 until sub) {
            val x = k + (i + 0.5) / sub
            val f = if (blur == 0.0) (if (x >= e) 1.0 else 0.0) else ((x - (e - blur / 2)) / blur).coerceIn(0.0, 1.0)
            sum += lo + (hi - lo) * f
        }
        sum / sub
    }

    private fun DoubleArray.noisy(sd: Double, seed: Long): DoubleArray {
        val r = java.util.Random(seed)
        return DoubleArray(size) { this[it] + r.nextGaussian() * sd }
    }

    private fun strongest(p: DoubleArray, edge: Track.Edge = Track.Edge.STRONGEST) = Track.pick(Track.edges(p), edge, null, 10.0)

    @Test fun aSharpOrBlurredEdgeIsFoundToASmallFractionOfAPixel() {
        var worst = 0.0
        for (blur in listOf(0.0, 3.0, 8.0)) {
            var e = 20.0
            while (e < 80.0) {
                val f = assertNotNull(strongest(step(100, e, blur = blur)))
                worst = max(worst, abs(f.pos - e))
                assertTrue(f.rising)
                assertEquals(160.0, f.contrast, 6.0)
                e += 0.137
            }
        }
        assertTrue(worst < 0.06, "worst error $worst px")
    }

    @Test fun noiseKeepsTheErrorUnderAFifthOfAPixel() {
        // Luma noise of 1.5 per profile sample: about 5 per pixel, averaged across a roi 10 px thick.
        var worst = 0.0
        var squares = 0.0
        for (seed in 1L..300L) {
            val e = 30.0 + seed * 0.137
            val f = assertNotNull(strongest(step(120, e, blur = 3.0).noisy(1.5, seed)))
            worst = max(worst, abs(f.pos - e))
            squares += (f.pos - e) * (f.pos - e)
        }
        val rms = kotlin.math.sqrt(squares / 300)
        assertTrue(worst < 0.2, "worst error $worst px")
        assertTrue(rms < 0.05, "rms error $rms px")
    }

    @Test fun edgeChoosesAmongTwoEdges() {
        // A light bar from 30.4 to 70.6; the falling edge is stronger.
        val rise = step(100, 30.4, 40.0, 140.0)
        val fall = step(100, 70.6, 0.0, 120.0)
        val p = DoubleArray(100) { rise[it] - fall[it] }
        fun pos(edge: Track.Edge) = assertNotNull(strongest(p, edge)).pos
        assertEquals(30.4, pos(Track.Edge.FIRST), 0.05)
        assertEquals(70.6, pos(Track.Edge.LAST), 0.05)
        assertEquals(30.4, pos(Track.Edge.RISING), 0.05)
        assertEquals(70.6, pos(Track.Edge.FALLING), 0.05)
        assertEquals(70.6, pos(Track.Edge.STRONGEST), 0.05)
    }

    @Test fun theTrackerKeepsTheEdgeItFollowsWhenAStrongerOneIsFar() {
        val t = Track.Tracker(Track.Edge.STRONGEST)
        assertEquals(20.3, t.next(step(200, 20.3, 100.0, 150.0)).first!!.pos, 0.05)
        // A stronger edge appears far away; the first edge has moved a little.
        val a = step(200, 22.8, 100.0, 150.0)
        val b = step(200, 150.0, 0.0, 100.0)
        val (f, _) = t.next(DoubleArray(200) { a[it] + b[it] })
        assertEquals(22.8, f!!.pos, 0.05)
    }

    @Test fun aFlatProfileIsLost() {
        val flat = DoubleArray(150) { 120.0 }.noisy(2.0, 7)
        assertNull(strongest(flat))
        val (f, best) = Track.Tracker(Track.Edge.STRONGEST).next(flat)
        assertNull(f)
        assertTrue(best < Track.MIN_CONTRAST, "best contrast $best")
    }

    @Test fun theProfileAveragesAcrossTheAxis() {
        // 4 x 2 pixels: columns 0..3 have luma 0, 100, 200, 255.
        val g = intArrayOf(0, 100, 200, 255)
        val argb = IntArray(8) { val v = g[it % 4]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val x = Track.profile(argb, 4, 2, alongX = true)
        assertEquals(listOf(0.0, 100.0, 200.0, 255.0), x.map { Math.round(it).toDouble() })
        val y = Track.profile(argb, 4, 2, alongX = false)
        assertEquals(2, y.size)
        assertEquals(138.75, y[0], 0.5)
        assertEquals(min(y[0], y[1]), max(y[0], y[1]), 1e-9)
    }

    private val frameMs = 1000.0 / 30

    /** Holds and moves of [px] at 30 fps, one position per frame. A null position is a lost frame. */
    private fun analyze(px: List<Double?>): JsonObject {
        val r = java.util.Random(5)
        val points = px.mapIndexed { n, x -> Track.Point((n * frameMs).toLong(), x?.plus(r.nextGaussian() * 0.03), 100.0) }
        return Track.analyze(points, frameMs, emptyList(), null, { it }, raw = false)
    }

    private fun JsonObject.count(key: String) = this[key]!!.jsonArray.size

    @Test fun steadyTravelIsNoHold() {
        val r = analyze(List(60) { it * 1.0 })
        assertEquals(0, r.count("holds"), "holds ${r["holds"]}")
        assertTrue(r["hold_tolerance_px"]!!.jsonPrimitive.double < 0.5)
    }

    @Test fun aBriefExcursionBetweenTwoHoldsAtOnePlaceIsAMove() {
        // A hold at 10 px, two frames at 30 px, then back at 10 px.
        val r = analyze(List(20) { 10.0 } + listOf(20.0, 30.0, 30.0, 20.0) + List(20) { 10.0 })
        assertEquals(2, r.count("holds"), "holds ${r["holds"]}")
        assertEquals(1, r.count("moves"), "the move between them goes out and back")
    }

    @Test fun aGapOfLostFramesSplitsAHold() {
        val r = analyze(List(20) { 10.0 } + List(10) { null } + List(20) { 10.0 })
        assertEquals(10, r["lost"]!!.jsonPrimitive.int)
        assertEquals(2, r.count("holds"), "holds ${r["holds"]}")
    }
}
