package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
                is JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

    /** The [name] column of the tiles of a clip result. */
    private fun JsonObject.tileColumn(name: String): List<JsonPrimitive> {
        val i = this["tile_columns"]!!.jsonArray.map { it.jsonPrimitive.content }.indexOf(name)
        assertTrue(i >= 0, "tile_columns has $name: ${this["tile_columns"]}")
        return this["tiles"]!!.jsonArray.map { it.jsonArray[i].jsonPrimitive }
    }

    private fun JsonObject.tileTimes() = tileColumn("t_ms").map { it.long }

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
        val mark = call("record", "action" to "mark", "label" to "sent G1").json["marks"]!!.jsonArray.single().jsonObject
        assertTrue(mark["t_ms"]!!.jsonPrimitive.long in 200..1_500)
        val done = call("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 30_000)
        val r = done.json["recording"]!!.jsonObject
        assertEquals("ready", r["state"]!!.jsonPrimitive.content)
        assertTrue(r["frames"]!!.jsonPrimitive.int in 55..80, "frames ${r["frames"]}")
        assertTrue(r["actual_fps"]!!.jsonPrimitive.double in 25.0..31.0, "fps ${r["actual_fps"]}")
        assertNull(r["ended_by"], "a recording that ran its full duration leaves out ended_by")
        assertEquals("sent G1", r["marks"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(r["activity"]!!.jsonArray.isNotEmpty(), "the hand should register as activity")
        assertEquals(listOf("from_ms", "to_ms", "peak", "peak_ms"), r["activity_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(r["activity"]!!.jsonArray.all { it.jsonArray.size == 4 })
        assertNull(r["quiet"])
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
        val still = assertFailsWith<LookoutError> { call("look", "detail" to "full") }
        assertEquals("recording_active", still.code)
        assertNotNull(call("look").image, "look with detail=fast keeps working during a recording")
        call("record", "action" to "stop")
        val done = call("wait_for", "condition" to "recording_done", "timeout_ms" to 30_000).json["recording"]!!.jsonObject
        assertEquals("stop", done["ended_by"]!!.jsonPrimitive.content)
        assertNull(done["truncated"])
        assertEquals(1_500L, done["requested"]!!.jsonObject["duration_ms"]!!.jsonPrimitive.long, "a stopped recording reports the duration it did not reach")
    }

    @Test fun clipReturnsSheetsAndSingleFrames() = runBlocking<Unit> {
        val r = recordAndWait()
        val id = r["recording_id"]!!.jsonPrimitive.content
        val sheet = call("clip", "recording_id" to id, "tiles" to 6)
        assertEquals(listOf("t_ms", "index", "motion"), sheet.json["tile_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(6, sheet.json["tiles"]!!.jsonArray.size)
        assertTrue(sheet.json["tiles"]!!.jsonArray.all { it.jsonArray.size == 3 })
        assertNotNull(sheet.image)
        val motion = call("clip", "recording_id" to id, "pick" to "motion", "tiles" to 4)
        assertTrue(motion.json["tiles"]!!.jsonArray.size in 1..4)
        val one = call("clip", "recording_id" to id, "at_ms" to "1000")
        val t = one.json.tileTimes()
        assertEquals(1, t.size)
        assertTrue(kotlin.math.abs(t[0] - 1000) <= 40)
        val img = AwtImageOps.decode(one.image!!.jpeg)
        assertTrue(maxOf(img.width, img.height) in 600..1100, "one frame is large: ${img.width}x${img.height}")
        val at = isoTime(parseIso(one.json["started_at"]!!.jsonPrimitive.content)!! + t[0])
        val byWallTime = call("clip", "recording_id" to id, "at" to at).json.tileTimes()
        assertEquals(t[0], byWallTime[0], "at=$at (started_at + t_ms) picks the same frame")
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

    @Test fun waitForRecordingDoneSheetAndClip() = runBlocking<Unit> {
        val r = recordAndWait()
        val id = r["recording_id"]!!.jsonPrimitive.content

        val defaultSheet = call("wait_for", "condition" to "recording_done", "recording_id" to id)
        assertNotNull(defaultSheet.image, "default returns the contact sheet")
        assertTrue("tiles" in defaultSheet.json && "tile_columns" in defaultSheet.json && "clip" !in defaultSheet.json)

        val noSheet = call("wait_for", "condition" to "recording_done", "recording_id" to id, "sheet" to false)
        assertTrue(noSheet.json["matched"]!!.jsonPrimitive.boolean)
        assertNull(noSheet.image, "sheet=false returns no image")
        assertNull(noSheet.json["image_id"])
        assertNull(noSheet.json["clip"])

        val atClip = call(
            "wait_for",
            "condition" to "recording_done",
            "recording_id" to id,
            "sheet" to false,
            "clip" to buildJsonObject { putJsonArray("at_ms") { add(400); add(1200) } },
        )
        assertNotNull(atClip.image, "clip wins over sheet=false")
        assertNull(atClip.json["tiles"], "clip fields stay under the clip key")
        val clipJson = atClip.json["clip"]!!.jsonObject
        val times = clipJson.tileTimes()
        assertEquals(2, times.size)
        assertTrue(kotlin.math.abs(times[0] - 400) <= 40)
        assertTrue(kotlin.math.abs(times[1] - 1200) <= 40)
        assertEquals(clipJson["image_id"]!!.jsonPrimitive.content, atClip.image.id)

        val traceClip = call(
            "wait_for",
            "condition" to "recording_done",
            "recording_id" to id,
            "clip" to buildJsonObject {
                put("mode", "trace")
                put("roi", "0.625,0.375,0.05,0.066")
            },
        )
        assertNull(traceClip.image, "mode=trace returns no image")
        val traceJson = traceClip.json["clip"]!!.jsonObject
        assertEquals("trace", traceJson["mode"]!!.jsonPrimitive.content)
        assertNull(traceJson["image_id"])
        assertEquals(false, traceJson["steady"]!!.jsonPrimitive.boolean)
        assertTrue(traceJson["transitions"]!!.jsonPrimitive.int >= 3)
    }

    @Test fun waitForRecordingDoneValidatesClipBeforeWaitingAndIgnoresOnTimeout() = runBlocking<Unit> {
        val started = call("record", "action" to "start", "fps" to 30, "duration_ms" to 5_000).json["recording"]!!.jsonObject
        val id = started["recording_id"]!!.jsonPrimitive.content

        val t0 = System.currentTimeMillis()
        val badMode = assertFailsWith<LookoutError> {
            call("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 10_000, "clip" to buildJsonObject { put("mode", "bogus") })
        }
        assertEquals("invalid_param", badMode.code)
        assertTrue(System.currentTimeMillis() - t0 < 1_000, "invalid clip fails before waiting")

        val badAtMs = assertFailsWith<LookoutError> {
            call("wait_for", "condition" to "recording_done", "recording_id" to id, "clip" to buildJsonObject { putJsonArray("at_ms") { add("nope") } })
        }
        assertEquals("invalid_param", badAtMs.code)

        val badThreshold = assertFailsWith<LookoutError> {
            call("wait_for", "condition" to "recording_done", "recording_id" to id, "clip" to buildJsonObject { put("threshold", 300) })
        }
        assertEquals("invalid_param", badThreshold.code)

        val unknownKey = assertFailsWith<LookoutError> {
            call("wait_for", "condition" to "recording_done", "recording_id" to id, "clip" to buildJsonObject { put("tils", 4) })
        }
        assertEquals("unknown_param", unknownKey.code)

        val timedOut = call(
            "wait_for",
            "condition" to "recording_done",
            "recording_id" to id,
            "timeout_ms" to 100,
            "clip" to buildJsonObject { putJsonArray("at_ms") { add(200) } },
        )
        assertFalse(timedOut.json["matched"]!!.jsonPrimitive.boolean)
        assertNull(timedOut.image)
        assertNull(timedOut.json["clip"])

        call("record", "action" to "stop")
        call("wait_for", "condition" to "recording_done", "recording_id" to id, "sheet" to false, "timeout_ms" to 30_000)
    }

    private fun marksOf(id: String): List<Pair<String, Long>> = runBlocking {
        call("wait_for", "condition" to "recording_done", "recording_id" to id, "sheet" to false).json["recording"]!!.jsonObject["marks"]
            ?.jsonArray?.map { it.jsonObject["label"]!!.jsonPrimitive.content to it.jsonObject["t_ms"]!!.jsonPrimitive.long } ?: emptyList()
    }

    private fun marksArg(vararg marks: Pair<String, Any>) = buildJsonArray {
        marks.forEach { (label, at) -> add(buildJsonObject { put("label", label); if (at is Number) put("at", at) else put("at", at.toString()) }) }
    }

    private fun addedTMs(result: JsonObject) = result["marks"]!!.jsonArray.map { it.jsonObject["t_ms"]!!.jsonPrimitive.long }

    @Test fun aMarkTakesAtAsTMsOrIsoTimeAndStartGivesServerTime() = runBlocking<Unit> {
        val before = System.currentTimeMillis()
        val startJson = call("record", "action" to "start", "fps" to 30, "duration_ms" to 2_000).json
        val serverTime = parseIso(startJson["server_time"]!!.jsonPrimitive.content)!!
        assertTrue(serverTime in before..System.currentTimeMillis(), "server_time is the phone clock at the reply")
        val started = startJson["recording"]!!.jsonObject
        val id = started["recording_id"]!!.jsonPrimitive.content
        val startedAt = parseIso(started["started_at"]!!.jsonPrimitive.content)!!
        delay(400)
        assertEquals(listOf(100L), addedTMs(call("record", "action" to "mark", "label" to "a", "at" to 100).json))
        assertEquals(listOf(250L), addedTMs(call("record", "action" to "mark", "label" to "b", "at" to isoTime(startedAt + 250)).json))
        val future = assertFailsWith<LookoutError> { call("record", "action" to "mark", "label" to "c", "at" to 60_000) }
        assertEquals("invalid_param", future.code)
        assertEquals(listOf("a" to 100L, "b" to 250L), marksOf(id))
    }

    @Test fun aListOfMarksGoesOnAFinishedRecordingInOrder() = runBlocking<Unit> {
        val id = recordAndWait()["recording_id"]!!.jsonPrimitive.content
        val r = call("record", "action" to "mark", "recording_id" to id, "marks" to marksArg("late" to 1_500, "early" to "500")).json
        assertEquals(id, r["recording_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(1_500L, 500L), addedTMs(r))
        assertNull(r["clock_offset_ms"])
        assertEquals(listOf("early" to 500L, "late" to 1_500L), marksOf(id))
        val live = assertFailsWith<LookoutError> { call("record", "action" to "mark", "label" to "now") }
        assertEquals("no_recording", live.code)
        assertTrue("at" in live.message!!, live.message)
    }

    @Test fun clientNowShiftsIsoTimesByTheClockOffset() = runBlocking<Unit> {
        val r = recordAndWait()
        val id = r["recording_id"]!!.jsonPrimitive.content
        val startedAt = parseIso(r["started_at"]!!.jsonPrimitive.content)!!
        // The client clock is 2 s behind the phone.
        val result = call(
            "record", "action" to "mark",
            "marks" to marksArg("iso" to isoTime(startedAt + 1_000 - 2_000), "timeline" to 300),
            "client_now" to isoTime(System.currentTimeMillis() - 2_000),
        ).json
        val offset = result["clock_offset_ms"]!!.jsonPrimitive.long
        assertTrue(offset in 2_000..2_200, "offset $offset")
        val (iso, timeline) = addedTMs(result)
        assertEquals(1_000 + offset - 2_000, iso)
        assertEquals(300L, timeline, "t_ms needs no correction")
    }

    @Test fun aMarkOutsideTheRecordingIsRejectedAndNothingIsAdded() = runBlocking<Unit> {
        val id = recordAndWait()["recording_id"]!!.jsonPrimitive.content
        val after = assertFailsWith<LookoutError> {
            call("record", "action" to "mark", "recording_id" to id, "marks" to marksArg("ok" to 500, "late" to 99_000))
        }
        assertEquals("invalid_param", after.code)
        assertTrue("0.." in after.message!!, "the message gives the valid range: ${after.message}")
        val before = assertFailsWith<LookoutError> { call("record", "action" to "mark", "recording_id" to id, "label" to "x", "at" to -5) }
        assertEquals("invalid_param", before.code)
        assertEquals(emptyList(), marksOf(id))
    }

    @Test fun clipAtMarksShowsATileForEachMarkWithItsLabel() = runBlocking<Unit> {
        val id = recordAndWait()["recording_id"]!!.jsonPrimitive.content
        val none = assertFailsWith<LookoutError> { call("clip", "recording_id" to id, "at_marks" to true) }
        assertEquals("invalid_param", none.code)
        call("record", "action" to "mark", "recording_id" to id, "marks" to marksArg("sent G1" to 400, "a long label that does not fit on a tile" to 1_200))
        val clip = call("clip", "recording_id" to id, "at_marks" to true, "offset_ms" to 100)
        assertNotNull(clip.image)
        assertEquals(listOf("t_ms", "index", "motion", "label"), clip.json["tile_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("sent G1", "a long label that does not fit on a tile"), clip.json.tileColumn("label").map { it.content })
        val times = clip.json.tileTimes()
        assertTrue(kotlin.math.abs(times[0] - 500) <= 20, "$times")
        assertTrue(kotlin.math.abs(times[1] - 1_300) <= 20, "$times")
        val viaWait = call(
            "wait_for", "condition" to "recording_done", "recording_id" to id,
            "clip" to buildJsonObject { put("at_marks", true); put("offset_ms", -100) },
        ).json["clip"]!!.jsonObject.tileTimes()
        assertTrue(kotlin.math.abs(viaWait[0] - 300) <= 20)
    }

    @Test fun fortyThinStripsFitInOneCallAndTooManyTilesGiveTheCount() = runBlocking<Unit> {
        val id = recordAndWait()["recording_id"]!!.jsonPrimitive.content
        val times = buildJsonArray { repeat(40) { add(100L + it * 50) } }
        val strips = call("clip", "recording_id" to id, "roi" to "0,0.45,1,0.05", "at_ms" to times)
        assertEquals(40, strips.json["tiles"]!!.jsonArray.size)
        assertNoLongDecimals(strips.json)
        val sheet = AwtImageOps.decode(strips.image!!.jpeg)
        assertTrue(sheet.width <= Compositor.MAX_EDGE && sheet.height <= Compositor.MAX_EDGE, "${sheet.width}x${sheet.height}")
        val tooMany = assertFailsWith<LookoutError> { call("clip", "recording_id" to id, "at_ms" to times, "width" to 400) }
        assertEquals("invalid_param", tooMany.code)
        assertTrue(Regex("at most \\d+ fit").containsMatchIn(tooMany.message!!), tooMany.message)
        val overCap = assertFailsWith<LookoutError> { call("clip", "recording_id" to id, "at_ms" to buildJsonArray { repeat(Tools.MAX_CLIP_TILES + 1) { add(it) } }) }
        assertEquals("invalid_param", overCap.code)
    }

    @Test fun summaryLeavesOutUsualValues() {
        val ready = RecordingInfo(
            id = "r1", state = "ready", file = "r1.mp4", fps = 30, resolution = "1080p", startedAt = 0, plannedMs = 2_500,
            width = 1080, height = 1920, durationMs = 2_510, frames = 75, actualFps = 29.97, dropped = 0, endedBy = "duration",
        )
        val full = core.tools.recordingJson(ready)
        for (k in listOf("requested", "dropped", "truncated", "ended_by", "activity", "activity_columns")) assertNull(full[k], "$k is left out: $full")
        assertEquals(true, full["quiet"]!!.jsonPrimitive.boolean)
        assertNotNull(full["video_url"])
        val stopped = core.tools.recordingJson(ready.copy(durationMs = 1_200, dropped = 3, endedBy = "stop", actualFps = 24.0))
        val requested = stopped["requested"]!!.jsonObject
        assertEquals(setOf("fps", "duration_ms"), requested.keys, "only the values that differ: $requested")
        assertEquals(3, stopped["dropped"]!!.jsonPrimitive.int)
        assertEquals("stop", stopped["ended_by"]!!.jsonPrimitive.content)
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
