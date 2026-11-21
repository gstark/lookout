package app.lookout.core

import app.lookout.awt.AwtImageOps
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
        val twelve = comp.contactSheet(List(12) { Fixtures.scene() to "t" })
        assertTrue(twelve.width > twelve.height, "4x3 layout is landscape")
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
