package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionsTest {
    private var now = 1_000L
    private val captions = Captions { now }

    @Test fun aCaptionShowsUntilItsDuration() {
        captions.show("sent M106", 500)
        assertEquals("sent M106", captions.at(1_000)?.text)
        assertEquals("sent M106", captions.at(1_499)?.text)
        assertNull(captions.at(1_500))
        assertNull(captions.at(999))
    }

    @Test fun aNewCaptionEndsTheOldOne() {
        captions.show("first", null)
        now = 2_000
        captions.show("second", null)
        assertEquals("first", captions.at(1_500)?.text)
        assertEquals("second", captions.at(2_000)?.text)
        assertEquals("second", captions.at(99_000)?.text, "no duration shows until cleared")
    }

    @Test fun clearEndsTheCaptionButKeepsItsPast() {
        val c = captions.show("first", null)
        now = 2_000
        assertNull(captions.clear(c.id + 1), "a stale id clears nothing")
        assertEquals(c.id, captions.current.value?.id)
        assertEquals(2_000L, captions.clear()?.untilMs)
        assertNull(captions.current.value)
        assertEquals("first", captions.at(1_500)?.text)
        assertNull(captions.at(2_000))
    }

    @Test fun clearAfterTheDurationLeavesTheEndAsItWas() {
        val c = captions.show("brief", 100)
        now = 5_000
        assertNull(captions.clear(c.id), "it already ended")
        assertNull(captions.current.value, "the preview still drops it")
        assertNull(captions.at(1_100))
    }

    @Test fun theCompositorDrawsTheTextInsideTheImage() {
        val img = Fixtures.scene()
        val out = Compositor(AwtImageOps).caption(img, "a long caption that is wider than a small image can show")
        assertEquals(img.width, out.width)
        assertEquals(img.height, out.height)
        assertTrue(yellowPixels(out, fromRow = img.height * 3 / 4) > 0)
    }

    companion object {
        /** Pixels in the caption yellow, from [fromRow] down. */
        fun yellowPixels(img: Img, fromRow: Int = 0): Int {
            val px = AwtImageOps.argb(img, img.width, img.height)
            var n = 0
            for (y in fromRow until img.height) for (x in 0 until img.width) {
                val p = px[y * img.width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 200 && g > 170 && b < 90) n++
            }
            return n
        }

        fun yellowPixels(jpeg: ByteArray): Int = AwtImageOps.decode(jpeg).let { yellowPixels(it, it.height * 3 / 4) }
    }
}
