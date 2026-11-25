package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftTest {
    private val ops = AwtImageOps
    private val a = Fixtures.texture()

    @Test fun cameraShiftHasSubPixelPrecision() {
        for ((dx, dy) in listOf(3.3 to -1.7, -0.4 to 0.25, 11.5 to 6.8)) {
            val s = assertNotNull(Shift.camera(ops, a, Fixtures.texture(dx = dx, dy = dy)))
            assertEquals(dx, s.dx * 640, 0.1, "dx for $dx")
            assertEquals(dy, s.dy * 480, 0.1, "dy for $dy")
            assertTrue(s.score > 0.95)
        }
    }

    @Test fun cameraShiftOnADownscaledFrame() {
        val big = Fixtures.texture(1280, 960)
        val s = assertNotNull(Shift.camera(ops, big, Fixtures.texture(1280, 960, dx = 7.3, dy = -2.6)))
        assertEquals(7.3, s.dx * 1280, 0.3)
        assertEquals(-2.6, s.dy * 960, 0.3)
    }

    @Test fun contentShiftWithinAThinRoi() {
        val roi = NRect(0.3, 0.45, 0.3, 0.1)
        for (dx in listOf(0.0, 2.4, -40.6, 128.2)) {
            val c = Shift.content(ops, a, roi, Fixtures.texture(dx = dx), null)
            assertEquals(dx, c.dx * 640, 0.1, "dx for $dx")
            assertEquals(0.0, c.dy * 480, 0.1)
            assertTrue(c.score > 0.95)
        }
    }

    @Test fun contentShiftIsMeasuredAfterTheCameraShift() {
        val roi = NRect(0.3, 0.45, 0.3, 0.1)
        val camera = Shift.Found(5.0 / 640, -3.0 / 480, 1.0)
        val c = Shift.content(ops, a, roi, Fixtures.texture(dx = 25.0, dy = -3.0), camera)
        assertEquals(20.0, c.dx * 640, 0.1)
        assertEquals(0.0, c.dy * 480, 0.1)
    }

    @Test fun aMoveBeyondTheRangeHasLowConfidence() {
        // The search reaches one roi width (192 px) to each side.
        val c = Shift.content(ops, a, NRect(0.3, 0.45, 0.3, 0.1), Fixtures.texture(dx = 260.0), null)
        assertTrue(c.score < Shift.MIN_CONTENT, "score ${c.score}")
    }

    @Test fun noTextureHasLowConfidence() {
        val flat = Fixtures.render(640, 480) { _, _ -> 120.0 }
        assertNull(Shift.camera(ops, flat, flat))
        assertEquals(0.0, Shift.content(ops, flat, NRect(0.3, 0.3, 0.3, 0.3), flat, null).score)
    }

    @Test fun unrelatedImagesHaveLowConfidence() {
        val other = Fixtures.texture(seed = 2)
        assertNull(Shift.camera(ops, a, other))
        val c = Shift.content(ops, a, NRect(0.3, 0.3, 0.3, 0.2), other, null)
        assertTrue(c.score < Shift.MIN_CONTENT, "score ${c.score}")
    }

    @Test fun overlapClipsBothViewsToTheirFrames() {
        val (ra, rb) = assertNotNull(Shift.overlap(NRect.FULL, Shift.Found(0.05, -0.02, 1.0)))
        assertEquals(NRect(0.0, 0.02, 0.95, 0.98).toString(), round(ra).toString())
        assertEquals(NRect(0.05, 0.0, 0.95, 0.98).toString(), round(rb).toString())
        assertNull(Shift.overlap(NRect.FULL, Shift.Found(0.001, 0.001, 1.0)), "under half a thumbnail pixel")
    }

    private fun round(r: NRect) = NRect(round3(r.x), round3(r.y), round3(r.w), round3(r.h))
}
