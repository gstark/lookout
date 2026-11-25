package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionTest {
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val image = Fixtures.scene(400, 300)
    private val jpegBytes = AwtImageOps.encodeJpeg(image, FrameStore.KEEP_QUALITY).size.toLong()
    private val decodedBytes = 400L * 300 * 4

    private fun store(keepBytes: Long) = FrameStore(AwtImageOps, ChangeDetector(AwtImageOps), clock, keepBytes = keepBytes)

    /** Keeps a new still and waits for its JPEG, so its size counts as the JPEG. */
    private suspend fun FrameStore.keepStill(): Long {
        val f = registerStill(RawFrame(image, now, CameraMeta()))
        keep(f)
        find(f.id).jpeg()
        return f.id
    }

    @Test fun theByteCapDropsTheOldestFramesAndTheErrorSaysWhy() = runBlocking {
        // Room for the new frame while it encodes, plus two and a half JPEGs.
        val s = store(decodedBytes + jpegBytes * 5 / 2)
        val ids = List(4) { s.keepStill() }
        val e = assertFailsWith<LookoutError> { s.find(ids[0]) }
        assertEquals("frame_dropped", e.code)
        assertTrue("free memory" in e.message!!, e.message)
        for (id in ids.drop(1)) assertEquals(400, s.find(id).image().width)
        assertEquals("still", s.find(ids[1]).source)
    }

    @Test fun aKeptFrameStaysForItsTimeThenGoes() = runBlocking {
        val s = store(Retention.FRAME_BYTES)
        val first = s.keepStill()
        now += Retention.URL_LIFE_MS
        s.keepStill()
        assertEquals(400, s.find(first).image().width, "still there when its URL expires")
        now += Retention.KEEP_MS - Retention.URL_LIFE_MS + 1
        s.keepStill()
        val e = assertFailsWith<LookoutError> { s.find(first) }
        assertEquals("frame_not_found", e.code)
        assertFalse("free memory" in e.message!!, "time, not memory, dropped it: ${e.message}")
    }

    @Test fun compositesLiveAsLongAsTheirUrl() {
        val c = CompositeStore(clock)
        val first = c.put(ByteArray(10))
        now += Retention.URL_LIFE_MS
        repeat(100) { c.put(ByteArray(10)) }
        assertTrue(c.get(first) != null, "kept for the life of its URL, past the old 64-entry limit")
        now += Retention.KEEP_MS - Retention.URL_LIFE_MS + 1
        c.put(ByteArray(10))
        assertEquals(null, c.get(first))
        assertFalse(c.droppedForMemory(first))
    }

    @Test fun theCompositeByteCapDropsTheOldestAndRemembersIt() {
        val c = CompositeStore(clock, maxBytes = 10)
        val ids = List(3) { c.put(ByteArray(4)) }
        assertEquals(null, c.get(ids[0]))
        assertTrue(c.droppedForMemory(ids[0]))
        assertTrue(c.get(ids[1]) != null && c.get(ids[2]) != null)
    }
}
