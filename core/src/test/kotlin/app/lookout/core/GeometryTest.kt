package app.lookout.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryTest {
    private fun close(a: Double, b: Double) = assertEquals(a, b, 1e-9)

    @Test fun gridSpanCoversInclusiveCells() {
        val r = GridSpec.DEFAULT.span("C2", "E4")
        close(2.0 / 8, r.x); close(1.0 / 6, r.y); close(3.0 / 8, r.w); close(3.0 / 6, r.h)
    }

    @Test fun gridSpanAcceptsReversedCorners() {
        assertEquals(GridSpec.DEFAULT.span("C2", "E4"), GridSpec.DEFAULT.span("e4", "c2"))
    }

    @Test fun singleCell() {
        val r = GridSpec.DEFAULT.span("H6", "H6")
        close(7.0 / 8, r.x); close(5.0 / 6, r.y); close(1.0 / 8, r.w)
    }

    @Test fun cellOutsideGridFails() {
        assertEquals("invalid_cell", assertFailsWith<LookoutError> { GridSpec.DEFAULT.parseCell("I1") }.code)
        assertEquals("invalid_cell", assertFailsWith<LookoutError> { GridSpec.DEFAULT.parseCell("A7") }.code)
        assertEquals("invalid_grid", assertFailsWith<LookoutError> { GridSpec.parse("eight") }.code)
    }

    @Test fun rectToPixelsStaysInside() {
        val p = NRect(0.5, 0.5, 0.5, 0.5).toPixels(641, 481)
        assertTrue(p.x + p.w <= 641 && p.y + p.h <= 481)
        assertEquals(PixelRect(0, 0, 640, 480), NRect.FULL.toPixels(640, 480))
    }

    @Test fun invalidRectFails() {
        assertFailsWith<LookoutError> { NRect(0.6, 0.0, 0.5, 0.5) }
        assertFailsWith<LookoutError> { NRect(0.0, 0.0, 0.0, 0.5) }
    }

    @Test fun withinMapsToFullFrame() {
        val r = NRect(0.5, 0.5, 0.5, 0.5).within(NRect(0.2, 0.2, 0.4, 0.4))
        close(0.4, r.x); close(0.4, r.y); close(0.2, r.w)
    }

    @Test fun relativeToPortraitCrop() {
        val crop = NRect(0.125, 0.0, 0.75, 1.0)
        // Inside: exact inverse of within.
        val roi = NRect(0.5, 0.25, 0.15, 0.5)
        val inside = roi.relativeTo(crop)!!
        close(0.5, inside.x); close(0.25, inside.y); close(0.2, inside.w); close(0.5, inside.h)
        val back = inside.within(crop)
        close(roi.x, back.x); close(roi.y, back.y); close(roi.w, back.w); close(roi.h, back.h)

        // Partly outside on left and right.
        val left = NRect(0.0, 0.2, 0.25, 0.4).relativeTo(crop)!!
        close(0.0, left.x); close(0.2, left.y); close(0.125 / 0.75, left.w); close(0.4, left.h)
        val right = NRect(0.8, 0.2, 0.2, 0.4).relativeTo(crop)!!
        close(0.675 / 0.75, right.x); close(0.2, right.y); close(0.075 / 0.75, right.w); close(0.4, right.h)

        // Full look frame maps to full video frame.
        val full = NRect.FULL.relativeTo(crop)!!
        close(0.0, full.x); close(0.0, full.y); close(1.0, full.w); close(1.0, full.h)

        // Fully outside and edge-touching give null.
        assertEquals(null, NRect(0.0, 0.2, 0.1, 0.4).relativeTo(crop))
        assertEquals(null, NRect(0.0, 0.2, 0.125, 0.4).relativeTo(crop))
        assertEquals(null, NRect(0.9, 0.2, 0.1, 0.4).relativeTo(crop))
    }

    @Test fun relativeToLandscapeAndFullCrop() {
        val crop = NRect(0.0, 0.125, 1.0, 0.75)
        val roi = NRect(0.2, 0.5, 0.3, 0.15)
        val inside = roi.relativeTo(crop)!!
        close(0.2, inside.x); close(0.5, inside.y); close(0.3, inside.w); close(0.2, inside.h)
        val back = inside.within(crop)
        close(roi.x, back.x); close(roi.y, back.y); close(roi.w, back.w); close(roi.h, back.h)

        val top = NRect(0.2, 0.0, 0.3, 0.25).relativeTo(crop)!!
        close(0.2, top.x); close(0.0, top.y); close(0.3, top.w); close(0.125 / 0.75, top.h)
        val bottom = NRect(0.2, 0.8, 0.3, 0.2).relativeTo(crop)!!
        close(0.2, bottom.x); close(0.675 / 0.75, bottom.y); close(0.3, bottom.w); close(0.075 / 0.75, bottom.h)

        assertEquals(null, NRect(0.2, 0.0, 0.3, 0.1).relativeTo(crop))
        assertEquals(null, NRect(0.2, 0.9, 0.3, 0.1).relativeTo(crop))
        assertEquals(roi, roi.relativeTo(NRect.FULL))
    }

    @Test fun rotatedTurnsClockwise() {
        val raw = NRect(0.0, 0.125, 1.0, 0.75)
        val r90 = raw.rotated(90)
        close(0.125, r90.x); close(0.0, r90.y); close(0.75, r90.w); close(1.0, r90.h)
        val r180 = raw.rotated(180)
        close(0.0, r180.x); close(0.125, r180.y); close(1.0, r180.w); close(0.75, r180.h)
        val r270 = raw.rotated(270)
        close(0.125, r270.x); close(0.0, r270.y); close(0.75, r270.w); close(1.0, r270.h)

        val a = NRect(0.1, 0.2, 0.3, 0.4)
        val a90 = a.rotated(90)
        close(0.4, a90.x); close(0.1, a90.y); close(0.4, a90.w); close(0.3, a90.h)
        val a180 = a.rotated(180)
        close(0.6, a180.x); close(0.4, a180.y); close(0.3, a180.w); close(0.4, a180.h)
        val a270 = a.rotated(270)
        close(0.2, a270.x); close(0.6, a270.y); close(0.4, a270.w); close(0.3, a270.h)
        val back = a90.rotated(270)
        close(a.x, back.x); close(a.y, back.y); close(a.w, back.w); close(a.h, back.h)
    }

    @Test fun relativeTimes() {
        val now = 1_000_000_000L
        assertEquals(now - 20 * 60_000, parseTime("-20m", now))
        assertEquals(now - 90_000, parseTime("-90s", now))
        assertEquals(now - 2 * 3_600_000, parseTime("-2h", now))
        assertEquals(now, parseTime("now", now))
        assertEquals(1_795_000_000_123L, parseTime(isoTime(1_795_000_000_123L), now))
        assertEquals("invalid_time", assertFailsWith<LookoutError> { parseTime("yesterday", now) }.code)
    }

    @Test fun isoTimeHasMilliseconds() {
        assertEquals("2026-11-20T00:00:00.000Z", isoTime(java.time.Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()))
    }

    @Test fun argsAcceptStringsAndJson() {
        val a = Args(buildJsonObject {
            put("w", "512"); put("n", 3); put("b", "true"); put("rect", "0.1,0.2,0.3,0.4")
            put("arr", buildJsonArray { add(JsonPrimitive(0.1)); add(JsonPrimitive(0.1)); add(JsonPrimitive(0.5)); add(JsonPrimitive(0.5)) })
            put("list", "a, b,c"); put("focus", "manual:2.5")
        })
        assertEquals(512, a.int("w")); assertEquals(3, a.int("n")); assertEquals(true, a.bool("b"))
        assertEquals(NRect(0.1, 0.2, 0.3, 0.4), a.rect("rect")); assertEquals(NRect(0.1, 0.1, 0.5, 0.5), a.rect("arr"))
        assertEquals(listOf("a", "b", "c"), a.strings("list")); assertEquals(FocusSetting("manual", distance = 2.5), a.focus("focus"))
        assertEquals("invalid_param", assertFailsWith<LookoutError> { Args(buildJsonObject { put("b", "maybe") }).bool("b") }.code)
        val quoted = Args(buildJsonObject { put("f", "{\"mode\":\"point\",\"x\":0.7,\"y\":0.5}"); put("r", "{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2}") })
        assertEquals(FocusSetting("point", x = 0.7, y = 0.5), quoted.focus("f"))
        assertEquals(NRect(0.1, 0.1, 0.2, 0.2), quoted.rect("r"))
    }

    @Test fun tokenBucketLimitsBurst() {
        var now = 0L
        val b = TokenBucket(10.0, 10.0) { now }
        assertEquals(10, (1..20).count { b.tryTake() })
        now += 500
        assertEquals(5, (1..20).count { b.tryTake() })
    }
}
