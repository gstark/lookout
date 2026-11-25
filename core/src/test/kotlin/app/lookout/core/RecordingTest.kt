package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("lookout-rec").toFile()

    /** One 2 s loop at 5 fps: the LED blinks for six frames, a hand sweeps in, then the scene rests. */
    private val frames: List<Img> = List(6) { Fixtures.scene(ledOn = it % 2 == 0) } +
        listOf(Fixtures.scene(handX = 0.3), Fixtures.scene(handX = 0.6), Fixtures.scene(), Fixtures.scene())
    private val source = ReplayFrameSource(frames, fps = 5.0, ops = AwtImageOps)
    private val core = Fixtures.core(source, recordingsDir = dir).also {
        it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
        it.start(scope)
    }
    private val ctx = CallContext("test")

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

    private suspend fun recordAndWait(vararg extra: Pair<String, Any>): JsonObject {
        val started = call("record", "action" to "start", "fps" to 30, "duration_ms" to 2_500, *extra).json
        val id = started["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content
        val done = call("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 30_000)
        assertTrue(done.json["matched"]!!.jsonPrimitive.boolean)
        return done.json["recording"]!!.jsonObject
    }

    @Test fun recordingReportsFramesFpsAndActivity() = runBlocking<Unit> {
        val started = call("record", "action" to "start", "fps" to 30, "duration_ms" to 2_500, "label" to "sweep").json["recording"]!!.jsonObject
        assertEquals("recording", started["state"]!!.jsonPrimitive.content)
        val id = started["recording_id"]!!.jsonPrimitive.content
        delay(300)
        val mark = call("record", "action" to "mark", "label" to "sent G1").json
        assertTrue(mark["t_ms"]!!.jsonPrimitive.long in 200..1_500)
        val done = call("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 30_000)
        val r = done.json["recording"]!!.jsonObject
        assertEquals("ready", r["state"]!!.jsonPrimitive.content)
        assertTrue(r["frames"]!!.jsonPrimitive.int in 55..80, "frames ${r["frames"]}")
        assertTrue(r["actual_fps"]!!.jsonPrimitive.double in 25.0..31.0, "fps ${r["actual_fps"]}")
        assertEquals("duration", r["ended_by"]!!.jsonPrimitive.content)
        assertEquals("sent G1", r["marks"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(r["activity"]!!.jsonArray.isNotEmpty(), "the hand should register as activity")
        assertNotNull(done.image, "a recording with activity comes with a contact sheet")
    }

    @Test fun aCaptionDuringARecordingMarksTheClipAndShowsInIt() = runBlocking<Unit> {
        val started = call("record", "action" to "start", "fps" to 30, "duration_ms" to 2_000).json["recording"]!!.jsonObject
        val id = started["recording_id"]!!.jsonPrimitive.content
        delay(800)
        val c = call("caption", "text" to "sent G1").json
        assertEquals(id, c["recording_id"]!!.jsonPrimitive.content)
        val done = call("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 30_000).json["recording"]!!.jsonObject
        call("caption", "action" to "clear")
        val mark = done["marks"]!!.jsonArray.single().jsonObject
        assertEquals("sent G1", mark["label"]!!.jsonPrimitive.content)
        val markMs = mark["t_ms"]!!.jsonPrimitive.long
        val before = call("clip", "recording_id" to id, "at_ms" to "${markMs / 4}").image!!.jpeg
        val after = call("clip", "recording_id" to id, "at_ms" to "${markMs + 400}").image!!.jpeg
        assertEquals(0, CaptionsTest.yellowPixels(before), "no caption before it showed")
        assertTrue(CaptionsTest.yellowPixels(after) > 0, "the caption shows on later frames")
    }

    @Test fun oneRecordingAtATimeAndNoStillsWhileRecording() = runBlocking<Unit> {
        call("record", "action" to "start", "fps" to 30, "duration_ms" to 1_500)
        val again = assertFailsWith<LookoutError> { call("record", "action" to "start") }
        assertEquals("recording_active", again.code)
        val still = assertFailsWith<LookoutError> { call("capture") }
        assertEquals("recording_active", still.code)
        assertNotNull(call("look").image, "look keeps working during a recording")
        call("record", "action" to "stop")
        val done = call("wait_for", "condition" to "recording_done", "timeout_ms" to 30_000).json["recording"]!!.jsonObject
        assertEquals("stop", done["ended_by"]!!.jsonPrimitive.content)
        assertEquals(false, done["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun clipReturnsSheetsAndSingleFrames() = runBlocking<Unit> {
        val r = recordAndWait()
        val id = r["recording_id"]!!.jsonPrimitive.content
        val sheet = call("clip", "recording_id" to id, "tiles" to 6)
        assertEquals(6, sheet.json["tiles"]!!.jsonArray.size)
        assertNotNull(sheet.image)
        val motion = call("clip", "recording_id" to id, "pick" to "motion", "tiles" to 4)
        assertTrue(motion.json["tiles"]!!.jsonArray.size in 1..4)
        val one = call("clip", "recording_id" to id, "at_ms" to "1000")
        val tiles = one.json["tiles"]!!.jsonArray
        assertEquals(1, tiles.size)
        assertTrue(kotlin.math.abs(tiles[0].jsonObject["t_ms"]!!.jsonPrimitive.long - 1000) <= 40)
        val img = AwtImageOps.decode(one.image!!.jpeg)
        assertTrue(maxOf(img.width, img.height) in 600..1100, "one frame is large: ${img.width}x${img.height}")
        val at = tiles[0].jsonObject["at"]!!.jsonPrimitive.content
        val byWallTime = call("clip", "recording_id" to id, "at" to at).json["tiles"]!!.jsonArray
        assertEquals(tiles[0].jsonObject["t_ms"], byWallTime[0].jsonObject["t_ms"], "at=$at picks the same frame")
    }

    @Test fun traceFindsTheLedBlink() = runBlocking<Unit> {
        val r = recordAndWait()
        val t = call("clip", "recording_id" to r["recording_id"]!!.jsonPrimitive.content, "mode" to "trace", "roi" to "0.625,0.375,0.05,0.066").json
        assertNull(t["image_id"])
        assertEquals(false, t["steady"]!!.jsonPrimitive.boolean)
        assertTrue(t["transitions"]!!.jsonPrimitive.int >= 3, "transitions ${t["transitions"]}")
        val high = t["high_ms"]!!.jsonObject["median"]!!.jsonPrimitive.long
        assertTrue(high in 120..280, "the LED stays on for one 200 ms replay frame, got $high")
    }

    @Test fun recordingNeedsADirectory() = runBlocking<Unit> {
        val bare = Fixtures.core(ReplayFrameSource(frames, ops = AwtImageOps)).also { it.start(scope) }
        val e = assertFailsWith<LookoutError> { bare.capability("record").call(args("action" to "start"), ctx) }
        assertEquals("recording_unsupported", e.code)
    }

    @Test fun segmentsJoinCloseFramesAndSplitGaps() {
        val pts = List(40) { it * 33_333L }
        val motion = List(40) { if (it in 5..8 || it in 30..32) 0.2 else 0.0 }
        val s = Recordings.segments(ClipIndex(pts, motion), 0.01)
        assertEquals(2, s.size)
        assertEquals(166L, s[0].fromMs)
        assertEquals(999L, s[1].fromMs)
    }
}
