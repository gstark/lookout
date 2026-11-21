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
