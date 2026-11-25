package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import kotlin.math.roundToInt
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
        runBlocking { source.close() }
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

    @Test fun aFailureAfterTheStartStopsTheMotionCollector() = runBlocking<Unit> {
        val failDir = Files.createTempDirectory("lookout-rec-fail").toFile()
        // A folder in the place of the info file of r1, so its first save fails.
        java.io.File(failDir, "r1.json").mkdir()
        val analyzed = kotlinx.coroutines.flow.MutableSharedFlow<AnalyzedFrame>()
        val rec = Recordings(source, core.session, core.device, core.events, core.detector, analyzed, LkClip.Decoder(AwtImageOps), Clock.SYSTEM, failDir, { 0.01 })
        rec.start(scope)
        try {
            assertFailsWith<java.io.IOException> { rec.start(RecordSpec(30, VideoResolution.FHD), 1_000, null, null, null, null) }
            kotlinx.coroutines.withTimeout(5_000) { analyzed.subscriptionCount.first { it == 0 } }
        } finally {
            runCatching { source.stopRecording() }
            failDir.deleteRecursively()
        }
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

    @Test fun aSheetOfThinTilesPointsAtTrack() = runBlocking<Unit> {
        val id = recordAndWait()["recording_id"]!!.jsonPrimitive.content
        assertNull(call("clip", "recording_id" to id, "tiles" to 8).json["hint"], "tiles of the whole frame")
        assertNull(call("clip", "recording_id" to id, "tiles" to 5, "roi" to "0.1,0.45,0.8,0.05").json["hint"], "too few tiles")
        assertEquals(Hints.THIN_TILES, call("clip", "recording_id" to id, "tiles" to 8, "roi" to "0.1,0.45,0.8,0.05").json["hint"]!!.jsonPrimitive.content)
        assertNull(call("clip", "recording_id" to id, "tiles" to 8, "roi" to "0.1,0.45,0.8,0.05").json["hint"], "once per window")
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
        val full = core.tools.recordingJson(ready, ctx)
        for (k in listOf("requested", "dropped", "truncated", "ended_by", "activity", "activity_columns", "video_crop")) assertNull(full[k], "$k is left out: $full")
        assertEquals(true, full["quiet"]!!.jsonPrimitive.boolean)
        assertNotNull(full["video_url"])
        val stopped = core.tools.recordingJson(ready.copy(durationMs = 1_200, dropped = 3, endedBy = "stop", actualFps = 24.0), ctx)
        val requested = stopped["requested"]!!.jsonObject
        assertEquals(setOf("fps", "duration_ms"), requested.keys, "only the values that differ: $requested")
        assertEquals(3, stopped["dropped"]!!.jsonPrimitive.int)
        assertEquals("stop", stopped["ended_by"]!!.jsonPrimitive.content)
    }

    @Test fun recordingInfoWithoutVideoCropDefaultsToFullFrame() {
        val oldJson = """{"id":"r1","state":"ready","file":"r1.mp4","fps":30,"resolution":"1080p","startedAt":0,"plannedMs":1000,"width":1080,"height":1920}"""
        val info = LookoutJson.decodeFromString(RecordingInfo.serializer(), oldJson)
        assertEquals(NRect.FULL, info.videoCrop)
        val roi = NRect(0.1, 0.2, 0.3, 0.4)
        assertEquals(roi, info.videoRoi(roi))
        assertNull(core.tools.recordingJson(info, ctx)["video_crop"])
    }

    @Test fun croppedVideoConvertsLookRoiForClipTraceMotionAndErrors() = runBlocking<Unit> {
        val crop = NRect(0.125, 0.0, 0.75, 1.0)
        // Portrait 3:4 look frames (480x640); video is the centered 9:16 strip (360x640, x=0.125..0.875).
        // Put a bright red marker at look-frame x=0.15..0.25, y=0.40..0.50 (blinking on frames 0, 2, 4).
        fun markerFrame(on: Boolean): Img {
            val img = java.awt.image.BufferedImage(480, 640, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(25, 25, 30)
            g.fillRect(0, 0, 480, 640)
            if (on) {
                g.color = java.awt.Color(245, 20, 20)
                g.fillRect((0.15 * 480).toInt(), (0.40 * 640).toInt(), (0.10 * 480).toInt(), (0.10 * 640).toInt())
            }
            g.dispose()
            return app.lookout.awt.AwtImg(img)
        }
        val cropDir = Files.createTempDirectory("lookout-crop").toFile()
        val cropSource = CroppedReplaySource(listOf(markerFrame(true), markerFrame(false)), videoCrop = crop, fps = 5.0)
        val cropCore = Fixtures.core(cropSource, recordingsDir = cropDir).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun ccall(tool: String, vararg pairs: Pair<String, Any>) = cropCore.capability(tool).call(args(*pairs), ctx)
        try {
            // ROI completely outside video fails at record start and leaves no recording.
            val badStart = assertFailsWith<LookoutError> {
                ccall("record", "action" to "start", "fps" to 30, "duration_ms" to 1_000, "roi" to "0.0,0.4,0.1,0.2")
            }
            assertEquals("invalid_param", badStart.code)
            assertTrue("0.125" in badStart.message!! && "0.875" in badStart.message!!, badStart.message)
            val emptyList = ccall("record", "action" to "list").json
            assertNull(emptyList["active"])
            assertEquals(0, emptyList["recordings"]!!.jsonArray.size)

            // Record with look-frame ROI on the blinking red marker.
            val startJson = ccall("record", "action" to "start", "fps" to 30, "duration_ms" to 1_200, "roi" to "0.15,0.40,0.10,0.10").json
            val startRec = startJson["recording"]!!.jsonObject
            val startCrop = startRec["video_crop"]!!.jsonObject
            assertEquals(0.125, startCrop["x"]!!.jsonPrimitive.double, 1e-3)
            assertEquals(0.0, startCrop["y"]!!.jsonPrimitive.double, 1e-3)
            assertEquals(0.75, startCrop["w"]!!.jsonPrimitive.double, 1e-3)
            assertEquals(1.0, startCrop["h"]!!.jsonPrimitive.double, 1e-3)
            val id = startRec["recording_id"]!!.jsonPrimitive.content

            val done = ccall("wait_for", "condition" to "recording_done", "recording_id" to id, "timeout_ms" to 30_000)
            val doneRec = done.json["recording"]!!.jsonObject
            assertEquals(startCrop, doneRec["video_crop"]!!.jsonObject)
            assertTrue(doneRec["activity"]!!.jsonArray.isNotEmpty(), "motion analysis converts roi and sees the blinking marker")
            assertNotNull(done.image, "default motion sheet renders with converted roi")

            // Single-frame clip at t=50 ms (frame 0, marker ON) must show the red marker, not dark background.
            val tile = ccall("clip", "recording_id" to id, "at_ms" to "50")
            val decoded = AwtImageOps.decode(tile.image!!.jpeg)
            val px = AwtImageOps.argb(decoded, decoded.width, decoded.height)
            // Sample the center of the image (above the footer band).
            val c = px[(decoded.height / 3) * decoded.width + decoded.width / 2]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue(r > 180 && g < 80 && b < 80, "expected red marker in cropped tile, got rgb($r,$g,$b)")

            // Trace with the same look-frame ROI sees the blink.
            val trace = ccall("clip", "recording_id" to id, "mode" to "trace").json
            assertEquals(false, trace["steady"]!!.jsonPrimitive.boolean)
            assertTrue(trace["transitions"]!!.jsonPrimitive.int >= 2, "transitions ${trace["transitions"]}")

            // Partly outside ROI is clipped to the overlap and still contains the red marker on its right side.
            val partial = ccall("clip", "recording_id" to id, "roi" to "0.05,0.40,0.20,0.10", "at_ms" to "50")
            val pImg = AwtImageOps.decode(partial.image!!.jpeg)
            val pPx = AwtImageOps.argb(pImg, pImg.width, pImg.height)
            val pc = pPx[(pImg.height / 3) * pImg.width + pImg.width * 3 / 4]
            assertTrue(((pc shr 16) and 0xFF) > 180, "clipped partial roi still contains the red marker")

            // "0,0,1,1" returns the whole video frame (9:16 portrait).
            val whole = ccall("clip", "recording_id" to id, "roi" to "0,0,1,1", "at_ms" to "50")
            val wImg = AwtImageOps.decode(whole.image!!.jpeg)
            assertTrue(wImg.width.toDouble() / wImg.height < 0.65, "whole video frame is 9:16, got ${wImg.width}x${wImg.height}")

            // Fully outside ROI in clip and wait_for(clip=...) fails with invalid_param.
            val badClip = assertFailsWith<LookoutError> { ccall("clip", "recording_id" to id, "roi" to "0.0,0.4,0.1,0.2") }
            assertEquals("invalid_param", badClip.code)
            assertTrue("0.125" in badClip.message!! && "0.875" in badClip.message!!, badClip.message)

            val badWait = assertFailsWith<LookoutError> {
                ccall("wait_for", "condition" to "recording_done", "recording_id" to id, "clip" to buildJsonObject { put("roi", "0.9,0.4,0.1,0.2") })
            }
            assertEquals("invalid_param", badWait.code)
        } finally {
            cropSource.close()
            cropDir.deleteRecursively()
        }
    }

    /** A 320 x 240 frame: light on the left, dark from [edge] px on, area sampled so the edge is at [edge] exactly. */
    private fun edgeFrame(edge: Double): Img {
        val img = java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 320) {
            val dark = (x + 1 - edge).coerceIn(0.0, 1.0)
            val v = (200 - 160 * dark).roundToInt()
            for (y in 0 until 240) img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return app.lookout.awt.AwtImg(img)
    }

    /**
     * The edge at frame [n] of 100 at 30 fps: holds at 100.25 to frame 30 (1000 ms), moves until frame 37 (1233 ms),
     * holds at 160.75 to frame 69 (2300 ms), moves until frame 73 (2433 ms) and holds at 130.5.
     */
    private fun edgeAt(n: Int): Double = when {
        n <= 30 -> 100.25
        n < 37 -> 100.25 + (160.75 - 100.25) * (n - 30) / 7
        n < 70 -> 160.75
        n < 73 -> 160.75 + (130.5 - 160.75) * (n - 69) / 4
        else -> 130.5
    }

    @Test fun trackMeasuresHoldsMovesAndMarks() = runBlocking<Unit> {
        val trackDir = Files.createTempDirectory("lookout-track").toFile()
        val src = ScriptedClipSource(100) { edgeFrame(edgeAt(it)) }
        val tc = Fixtures.core(src, recordingsDir = trackDir).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun tcall(tool: String, vararg pairs: Pair<String, Any>) = tc.capability(tool).call(args(*pairs), ctx)
        try {
            val id = tcall("record", "action" to "start", "fps" to 30, "duration_ms" to 500).json["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content
            tcall("wait_for", "condition" to "recording_done", "recording_id" to id, "sheet" to false, "timeout_ms" to 30_000)
            tcall("record", "action" to "mark", "recording_id" to id, "marks" to marksArg("go 1" to 1_000, "go 2" to 2_300))

            val r = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1")
            assertNull(r.image)
            val j = r.json
            assertNoLongDecimals(j)
            assertEquals("x", j["axis"]!!.jsonPrimitive.content)
            assertEquals(100, j["frames"]!!.jsonPrimitive.int)
            assertEquals(0, j["lost"]!!.jsonPrimitive.int)
            assertEquals(320, j["length_px"]!!.jsonPrimitive.int)
            val px = j["px"]!!.jsonObject
            assertEquals(60.5, px["travel"]!!.jsonPrimitive.double, 0.2)

            fun rows(key: String) = j[key]!!.jsonArray.map { row -> row.jsonArray }
            fun col(key: String, name: String) = j["${key}_columns"]!!.jsonArray.map { it.jsonPrimitive.content }.indexOf(name).also { assertTrue(it >= 0, "$key has $name") }
            val holds = rows("holds")
            assertEquals(3, holds.size, "holds ${j["holds"]}")
            val mean = col("holds", "mean_px")
            holds.zip(listOf(100.25, 160.75, 130.5)).forEach { (h, want) -> assertEquals(want, h[mean].jsonPrimitive.double, 0.2) }
            assertEquals(0L, holds[0][col("holds", "from_ms")].jsonPrimitive.long)
            assertEquals(100.25 / 320, holds[0][col("holds", "look")].jsonPrimitive.double, 0.002)

            val moves = rows("moves")
            assertEquals(2, moves.size)
            assertEquals(60.5, moves[0][col("moves", "distance_px")].jsonPrimitive.double, 0.3)
            assertEquals(-30.25, moves[1][col("moves", "distance_px")].jsonPrimitive.double, 0.3)
            // 60.5 px over 7 frames at 30 fps.
            assertEquals(60.5 / 7 * 30, moves[0][col("moves", "peak_px_s")].jsonPrimitive.double, 15.0)

            val marks = rows("marks")
            assertEquals(listOf("go 1", "go 2"), marks.map { it[0].jsonPrimitive.content })
            assertEquals(100.25, marks[0][col("marks", "at_px")].jsonPrimitive.double, 0.2)
            assertEquals(160.75, marks[0][col("marks", "settled_px")].jsonPrimitive.double, 0.2)
            assertEquals(60.5, marks[0][col("marks", "moved_px")].jsonPrimitive.double, 0.3)
            assertEquals(233L, marks[0][col("marks", "settle_ms")].jsonPrimitive.long)
            assertEquals(130.5, marks[1][col("marks", "settled_px")].jsonPrimitive.double, 0.2)

            val mm = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1", "mm_per_px" to 0.2, "raw" to true).json
            assertEquals(0.2, mm["mm_per_px"]!!.jsonPrimitive.double)
            assertEquals(12.1, mm["mm"]!!.jsonObject["travel"]!!.jsonPrimitive.double, 0.05)
            val moveCols = mm["moves_columns"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("from_mm", "to_mm", "distance_mm", "peak_mm_s"), moveCols.takeLast(4))
            assertEquals(12.1, mm["moves"]!!.jsonArray[0].jsonArray[moveCols.indexOf("distance_mm")].jsonPrimitive.double, 0.06)
            val raw = mm["raw"]!!.jsonArray
            assertEquals(100, raw.size)
            assertEquals(listOf("t_ms", "pos_px", "contrast", "pos_mm"), mm["raw_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(100.25, raw[5].jsonArray[1].jsonPrimitive.double, 0.2)

            val window = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1", "from_ms" to 1_500).json
            assertEquals(2, window["holds"]!!.jsonArray.size, "the window starts in the second hold")
            assertEquals(listOf("go 2"), window["marks"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content })

            val withImage = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1", "image" to true)
            assertEquals("down", withImage.json["image_time"]!!.jsonPrimitive.content)
            val stack = AwtImageOps.decode(withImage.image!!.jpeg)
            // 320 px of space plus the 44 px time gutter; 100 frames stretch to the 256 px minimum.
            assertEquals(364 to 256, stack.width to stack.height)
            val linked = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1", "image" to true, "inline" to false)
            assertNull(linked.image)
            assertTrue(linked.json["image_url"]!!.jsonPrimitive.content.isNotEmpty())

            val viaWait = tcall(
                "wait_for", "condition" to "recording_done", "recording_id" to id,
                "clip" to buildJsonObject { put("mode", "track"); put("roi", "0,0.45,1,0.1"); put("axis", "x"); put("edge", "falling") },
            ).json["clip"]!!.jsonObject
            assertEquals(3, viaWait["holds"]!!.jsonArray.size)

            val flat = tcall("clip", "recording_id" to id, "mode" to "track", "roi" to "0,0.45,1,0.1", "edge" to "rising").json
            assertEquals(100, flat["lost"]!!.jsonPrimitive.int, "there is no dark to light edge")
            assertNotNull(flat["hint"])
        } finally {
            src.close()
            trackDir.deleteRecursively()
        }
    }

    @Test fun aSheetLayoutCutsTilesOfTheStatedSize() = runBlocking<Unit> {
        val r = recordAndWait()
        val sheet = call("clip", "recording_id" to r["recording_id"]!!.jsonPrimitive.content, "tiles" to 7, "roi" to "0,0.3,1,0.2")
        val l = sheet.json["layout"]!!.jsonObject
        fun n(k: String) = l[k]!!.jsonPrimitive.int
        val img = AwtImageOps.decode(sheet.image!!.jpeg)
        val cols = n("cols")
        val rows = n("rows")
        val w = n("tile_w")
        val h = n("tile_h")
        val band = n("band")
        val gap = n("gap")
        assertEquals(cols * w + gap * (cols - 1), img.width)
        assertEquals(rows * (h + band) + gap * (rows - 1), img.height)
        assertEquals(w.toDouble() / 640, l["px_per_source"]!!.jsonPrimitive.double, 0.002)
        val px = AwtImageOps.argb(img, img.width, img.height)
        fun green(x: Int, y: Int) = (px[y * img.width + x] shr 8) and 0xFF
        for (i in 0 until 7) {
            val x = (i % cols) * (w + gap)
            val y = (i / cols) * (h + band + gap)
            assertTrue(x + w <= img.width && y + h + band <= img.height, "tile $i lies inside the sheet")
            // The scene is gray (green 110) with a green board (90); the sheet background is near black (32).
            assertTrue(green(x + w / 2, y + h / 2) > 70, "tile $i has the scene at its center")
            assertTrue(green(x + 1, y + 1) > 70 && green(x + w - 2, y + h - 2) > 70, "tile $i fills its rect")
        }
    }

    private val empty = Fixtures.scene(320, 240)
    private val hand = Fixtures.scene(320, 240, handX = 0.5)

    /** The hand shows from 1000 to 1300 ms of each recording, so motion runs from 1000 to about 1400 ms. */
    private fun handScene(tMs: Long?) = if (tMs != null && tMs in 1_000L until 1_300L) hand else empty

    private inner class Timeline(val source: TimelineSource, val decoder: GatedDecoder) {
        val dir: java.io.File = Files.createTempDirectory("lookout-timeline").toFile()
        val core = Fixtures.core(source, recordingsDir = dir, clipDecoder = decoder).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }

        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

        suspend fun start(ms: Int): String =
            call("record", "action" to "start", "fps" to 30, "duration_ms" to ms).json["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content

        suspend fun done(id: String, vararg extra: Pair<String, Any>): JsonObject =
            call("wait_for", "condition" to "recording_done", "recording_id" to id, "sheet" to false, "timeout_ms" to 30_000, *extra).json

        suspend fun close() {
            decoder.release()
            source.close()
            dir.deleteRecursively()
        }
    }

    private fun timeline(preview: Boolean = true, gated: Boolean = true) =
        Timeline(TimelineSource(preview, AwtImageOps, ::handScene), GatedDecoder(LkClip.Decoder(AwtImageOps), gated))

    /** Fails unless one activity segment of [r] covers the hand: from about 1000 to about 1400 ms. */
    private fun assertHandActivity(r: JsonObject, toleranceMs: Long) {
        val segments = r["activity"]!!.jsonArray.map { it.jsonArray.map { v -> v.jsonPrimitive.double } }
        val hit = segments.filter { kotlin.math.abs(it[0] - 1_000) <= toleranceMs && kotlin.math.abs(it[1] - 1_400) <= toleranceMs }
        assertEquals(1, hit.size, "one segment at the hand, within $toleranceMs ms: $segments")
    }

    @Test fun aRecordingIsReadyFromPreviewMotionAndTurnsExactInTheBackground() = runBlocking<Unit> {
        val t = timeline()
        try {
            val id = t.start(2_500)
            delay(300)
            assertEquals(CameraSession.RECORDING_FPS, t.source.appliedFps, "analysis frames come faster while a recording runs")
            val done = t.done(id)
            val r = done["recording"]!!.jsonObject
            assertEquals("ready", r["state"]!!.jsonPrimitive.content)
            assertEquals(0, t.decoder.finishedPasses.get(), "ready before any full decode")
            assertEquals("preview", r["motion_source"]!!.jsonPrimitive.content)
            val finalizeMs = r["finalize_ms"]!!.jsonPrimitive.long
            assertTrue(finalizeMs in 0..2_000, "finalize_ms $finalizeMs")
            // One preview frame (67 ms), the 100 ms lag, and scheduling slack.
            assertHandActivity(r, 200)

            val tile = t.call("clip", "recording_id" to id, "at_ms" to "1150")
            assertTrue(kotlin.math.abs(tile.json.tileTimes().single() - 1_150) <= 40)
            assertNotNull(tile.image, "clip works on the preview index")
            assertEquals("trace", t.call("clip", "recording_id" to id, "mode" to "trace").json["mode"]!!.jsonPrimitive.content)

            val early = t.done(id, "exact" to true, "timeout_ms" to 300)
            assertFalse(early["matched"]!!.jsonPrimitive.boolean, "exact=true waits for the exact pass")

            t.decoder.release()
            val exact = t.done(id, "exact" to true)
            assertTrue(exact["matched"]!!.jsonPrimitive.boolean)
            val e = exact["recording"]!!.jsonObject
            assertNull(e["motion_source"], "exact motion leaves out motion_source: $e")
            assertEquals(finalizeMs, e["finalize_ms"]!!.jsonPrimitive.long)
            assertEquals(r["frames"], e["frames"])
            assertHandActivity(e, 100)
            val events = t.core.events.since(0, setOf("recording_exact"))
            assertEquals(listOf(id), events.map { it.data["id"]!!.jsonPrimitive.content })
        } finally {
            t.close()
        }
    }

    @Test fun aNewRecordingDoesNotWaitForTheExactPassOfTheOldOne() = runBlocking<Unit> {
        val t = timeline()
        try {
            val first = t.start(1_500)
            assertEquals("preview", t.done(first)["recording"]!!.jsonObject["motion_source"]!!.jsonPrimitive.content)
            val second = t.start(2_000)
            // The pass of the first recording can go now, and must give way to the running recording.
            t.decoder.release()
            val r = t.done(second)["recording"]!!.jsonObject
            assertEquals("ready", r["state"]!!.jsonPrimitive.content)
            assertTrue(r["finalize_ms"]!!.jsonPrimitive.long <= 2_000, "the second recording did not wait: $r")
            for (id in listOf(first, second)) {
                val e = t.done(id, "exact" to true)["recording"]!!.jsonObject
                assertNull(e["motion_source"], "$id is exact: $e")
                assertHandActivity(e, 100)
            }
            assertEquals(3, t.decoder.fullPasses.get(), "the first pass stopped for the recording and ran again after it")
        } finally {
            t.close()
        }
    }

    @Test fun withoutPreviewFramesTheFirstStageDecodesTheVideo() = runBlocking<Unit> {
        val t = timeline(preview = false, gated = false)
        try {
            val id = t.start(2_000)
            val r = t.done(id)["recording"]!!.jsonObject
            assertEquals("ready", r["state"]!!.jsonPrimitive.content)
            assertEquals(1, t.decoder.finishedPasses.get(), "the full decode ran before ready")
            assertNull(r["motion_source"])
            assertNotNull(r["finalize_ms"])
            assertHandActivity(r, 100)
            assertTrue(t.done(id, "exact" to true)["matched"]!!.jsonPrimitive.boolean, "exact=true returns at once")
            assertEquals(1, t.decoder.fullPasses.get(), "no second pass")
        } finally {
            t.close()
        }
    }

    @Test fun lkClipTimesMatchTheDecodedTimes() {
        val f = java.io.File(dir, "times.lkclip")
        val jpeg = AwtImageOps.encodeJpeg(empty, 80)
        val written = listOf(0L, 33_333L, 66_666L, 133_333L, 166_666L)
        LkClip.Writer(f).use { w -> written.forEach { w.write(it, jpeg) } }
        val decoder = LkClip.Decoder(AwtImageOps)
        val decoded = ArrayList<Long>()
        decoder.decode(f, 0, Long.MAX_VALUE, { null }) { p, _ -> decoded += p }
        assertEquals(written, decoded)
        assertEquals(decoded, decoder.times(f))
    }

    @Test fun segmentsJoinCloseFramesAndSplitGaps() {
        val pts = List(40) { it * 33_333L }
        val motion = List(40) { if (it in 5..8 || it in 30..32) 0.2 else 0.0 }
        val s = Recordings.segments(ClipIndex(pts, motion), 0.01)
        assertEquals(2, s.size)
        assertEquals(166L, s[0].fromMs)
        assertEquals(999L, s[1].fromMs)
    }

    @Test fun audioUnsupportedOnReplaySource() = runBlocking<Unit> {
        val desc = call("describe").json["recording"]!!.jsonObject
        assertEquals(false, desc["audio"]!!.jsonPrimitive.boolean)
        val err = assertFailsWith<LookoutError> { call("record", "action" to "start", "audio" to true) }
        assertEquals("audio_unsupported", err.code)
        assertEquals(501, err.status)
    }

    @Test fun audioPermissionSettingAndClipAnalysis() = runBlocking<Unit> {
        val sr = 48_000
        // 2 s audio: two 120 ms bursts at 400 ms (1000 Hz) and 1200 ms (2000 Hz).
        val samples = ShortArray(sr * 2) { i ->
            val ms = i * 1000.0 / sr
            val t = i.toDouble() / sr
            val v = when {
                ms in 400.0..520.0 -> 0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * t)
                ms in 1200.0..1320.0 -> 0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 2000.0 * t)
                else -> 0.0
            }
            (v * 32767.0).roundToInt().toShort()
        }
        val audioDir = Files.createTempDirectory("lookout-audio").toFile()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audioSource = AudioClipSource(count = 60, fps = 30)
        val decoder = AudioClipDecoder(audioIds = audioSource.audioIds) { Pcm(samples, sr) }
        val c = Fixtures.core(audioSource, recordingsDir = audioDir, clipDecoder = decoder).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(testScope)
        }
        suspend fun run(tool: String, vararg pairs: Pair<String, Any>) = c.capability(tool).call(args(*pairs), ctx)
        try {
            // Setting is off by default -> describe says false, record(audio=true) returns audio_off.
            assertEquals(false, run("describe").json["recording"]!!.jsonObject["audio"]!!.jsonPrimitive.boolean)
            val off = assertFailsWith<LookoutError> { run("record", "action" to "start", "audio" to true) }
            assertEquals("audio_off", off.code)
            assertEquals(403, off.status)
            assertTrue(off.message.orEmpty().contains("Allow sound recording") && off.message.orEmpty().contains("Setup"))

            // Setting on, permission missing -> describe says false, record(audio=true) returns audio_permission.
            c.updateSettings { it.copy(allowAudio = true) }
            audioSource.permitted = false
            assertEquals(false, run("describe").json["recording"]!!.jsonObject["audio"]!!.jsonPrimitive.boolean)
            val perm = assertFailsWith<LookoutError> { run("record", "action" to "start", "audio" to true) }
            assertEquals("audio_permission", perm.code)
            assertEquals(403, perm.status)
            assertTrue(perm.message.orEmpty().contains("microphone permission", ignoreCase = true))

            // Setting on and permission granted -> describe reports recording.audio=true.
            audioSource.permitted = true
            assertEquals(true, run("describe").json["recording"]!!.jsonObject["audio"]!!.jsonPrimitive.boolean)

            // Silent recording omits audio from summary and rejects clip mode=audio with no_audio.
            val silentStart = run("record", "action" to "start", "fps" to 30, "duration_ms" to 2_000).json["recording"]!!.jsonObject
            assertNull(silentStart["audio"])
            val silentId = silentStart["recording_id"]!!.jsonPrimitive.content
            val earlyNoAudio = assertFailsWith<LookoutError> {
                run("wait_for", "condition" to "recording_done", "recording_id" to silentId, "clip" to buildJsonObject { put("mode", "audio") })
            }
            assertEquals("no_audio", earlyNoAudio.code)
            assertTrue(earlyNoAudio.message.orEmpty().contains("audio=true"))
            run("record", "action" to "stop")
            val silentDone = run("wait_for", "condition" to "recording_done", "recording_id" to silentId, "sheet" to false).json["recording"]!!.jsonObject
            assertNull(silentDone["audio"])
            val noAudio = assertFailsWith<LookoutError> { run("clip", "recording_id" to silentId, "mode" to "audio") }
            assertEquals("no_audio", noAudio.code)
            assertEquals(404, noAudio.status)

            // Audio recording sets audio=true, works with wait_for(clip={mode:"audio"}), and returns no image.
            val audioStart = run("record", "action" to "start", "fps" to 30, "duration_ms" to 2_000, "audio" to true).json["recording"]!!.jsonObject
            assertEquals(true, audioStart["audio"]!!.jsonPrimitive.boolean)
            val audioId = audioStart["recording_id"]!!.jsonPrimitive.content
            run("record", "action" to "stop")
            val waited = run(
                "wait_for",
                "condition" to "recording_done",
                "recording_id" to audioId,
                "clip" to buildJsonObject { put("mode", "audio") },
            )
            assertNull(waited.image, "mode=audio must not return an image")
            assertEquals(true, waited.json["recording"]!!.jsonObject["audio"]!!.jsonPrimitive.boolean)
            val audioClip = waited.json["clip"]!!.jsonObject
            assertNoLongDecimals(audioClip)
            assertEquals("audio", audioClip["mode"]!!.jsonPrimitive.content)
            assertEquals(sr, audioClip["sample_rate"]!!.jsonPrimitive.int)
            assertNull(audioClip["image_id"])
            val events = audioClip["events"]!!.jsonArray
            assertEquals(2, events.size, "events=$events")
            assertTrue(kotlin.math.abs(events[0].jsonArray[3].jsonPrimitive.double - 1000.0) <= 20.0)
            assertTrue(kotlin.math.abs(events[1].jsonArray[3].jsonPrimitive.double - 2000.0) <= 20.0)

            // Adding a mark afterwards gives the mark table with event delay in clip mode=audio.
            run("record", "action" to "mark", "recording_id" to audioId, "label" to "servo_start", "at" to 280)
            val marked = run("clip", "recording_id" to audioId, "mode" to "audio")
            assertNull(marked.image)
            val markRow = marked.json["marks"]!!.jsonArray.single().jsonArray
            assertEquals("servo_start", markRow[0].jsonPrimitive.content)
            assertTrue(kotlin.math.abs(markRow[4].jsonPrimitive.long - 120L) <= 20L, "delay=${markRow[4]}")

            // Windowed clip mode=audio slices to from_ms..to_ms.
            val windowed = run("clip", "recording_id" to audioId, "mode" to "audio", "from_ms" to 900, "to_ms" to 1_600)
            assertNull(windowed.image)
            assertEquals(900L, windowed.json["from_ms"]!!.jsonPrimitive.long)
            assertEquals(1, windowed.json["events"]!!.jsonArray.size)
            assertTrue(kotlin.math.abs(windowed.json["events"]!!.jsonArray[0].jsonArray[3].jsonPrimitive.double - 2000.0) <= 20.0)

            // record(action=list) reports audio=true only on the recording with sound.
            val list = run("record", "action" to "list").json["recordings"]!!.jsonArray
            val byId = list.associate { it.jsonObject["recording_id"]!!.jsonPrimitive.content to it.jsonObject }
            assertEquals(true, byId[audioId]!!["audio"]!!.jsonPrimitive.boolean)
            assertNull(byId[silentId]!!["audio"])
        } finally {
            testScope.cancel()
            audioSource.close()
            audioDir.deleteRecursively()
        }
    }
}
