package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = PushSource()
    @Volatile private var readImage: Img? = null
    @Volatile private var meterText = "DC 12.04 V"
    private val core = Fixtures.core(source, textReader = { img ->
        readImage = img
        listOf(TextLine(meterText, NRect(0.1, 0.2, 0.5, 0.3), 0.93), TextLine("HOLD", NRect(0.7, 0.8, 0.2, 0.1)))
    })
    @Volatile private var scene: Img = Fixtures.scene(noiseSeed = 1)
    private val ctx = CallContext("test")

    init {
        core.start(scope)
        scope.launch {
            var seed = 1
            while (isActive) {
                source.push(if (scene === STATIC) Fixtures.scene(noiseSeed = ++seed % 7 + 1) else scene)
                delay(40)
            }
        }
        scene = STATIC
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is kotlinx.serialization.json.JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

    @Test fun freshLookIsNeverOlderThanTheRequest() = runBlocking {
        core.store.latest.filterNotNull().first()
        repeat(5) {
            val before = System.currentTimeMillis()
            val r = call("look")
            val captured = Instant.parse(r.json["captured_at"]!!.jsonPrimitive.content).toEpochMilli()
            assertTrue(captured >= before, "captured $captured before request $before")
            assertTrue(r.image!!.jpeg.isNotEmpty())
        }
    }

    @Test fun staleFrameIsSkippedWhenFresh() = runBlocking {
        val stale = System.currentTimeMillis() - 5_000
        val pre = core.store.latest.filterNotNull().first().id
        source.push(Fixtures.scene(), at = stale)
        val r = call("look", "fresh" to true)
        assertTrue(r.json["frame_id"]!!.jsonPrimitive.long > pre + 1)
        assertTrue(r.json["age_ms"]!!.jsonPrimitive.long < 1_000)
    }

    @Test fun afterFrameIdReturnsNewerFrame() = runBlocking {
        val id = core.store.latest.filterNotNull().first().id + 3
        val r = call("look", "fresh" to false, "after_frame_id" to id)
        assertTrue(r.json["frame_id"]!!.jsonPrimitive.long > id)
    }

    @Test fun lookWidthAndMeta() = runBlocking {
        val r = call("look", "width" to 320, "grid" to "8x6", "stamp" to true)
        assertEquals(320, r.json["width"]!!.jsonPrimitive.long.toInt())
        for (k in listOf("frame_id", "captured_at", "age_ms", "lens", "zoom", "battery_pct", "charging", "thermal", "brightness")) {
            assertTrue(k in r.json, "meta has $k")
        }
        assertEquals(320, AwtImageOps.decode(r.image!!.jpeg).width)
    }

    @Test fun roiFromGridCellsCropsLook() = runBlocking {
        call("roi", "action" to "set", "name" to "board", "from" to "C2", "to" to "F5")
        val roi = core.rois.require("board")
        assertEquals(GridSpec.DEFAULT.span("C2", "F5"), roi.rect)
        val r = call("look", "roi" to "board", "width" to 200)
        assertEquals("board", r.json["roi"]!!.jsonPrimitive.content)
        val img = AwtImageOps.decode(r.image!!.jpeg)
        assertEquals(200, maxOf(img.width, img.height))
        assertEquals("roi_not_found", assertFailsWith<LookoutError> { call("look", "roi" to "nope") }.code)
        assertTrue(call("roi", "action" to "list").json["rois"]!!.jsonArray.size == 1)
        call("roi", "action" to "delete", "name" to "board")
        assertTrue(core.rois.list().isEmpty())
    }

    @Test fun smallRoiUsesTheStillPath() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("look", "roi" to "0.6,0.35,0.1,0.1", "width" to 512)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
    }

    @Test fun waitForChangeFiresWhenHandEnters() = runBlocking {
        val wait = async { call("wait_for", "condition" to "change", "timeout_ms" to 5_000) }
        delay(400)
        val entered = System.currentTimeMillis()
        scene = Fixtures.scene(handX = 0.3)
        val r = wait.await()
        assertTrue(r.json["matched"]!!.jsonPrimitive.boolean)
        assertTrue(r.json["waited_ms"]!!.jsonPrimitive.long - 400 < 1_000, "fired within 1 s of the hand")
        assertTrue(System.currentTimeMillis() - entered < 1_000)
        assertTrue(r.json["regions"]!!.jsonArray.isNotEmpty())
        assertTrue(r.json["score"]!!.jsonPrimitive.double > 0.02)
        assertTrue(r.image!!.jpeg.isNotEmpty() && r.image!!.id != null)
        scene = STATIC
    }

    @Test fun waitForChangeDoesNotFireOnStaticNoise() = runBlocking {
        val r = call("wait_for", "condition" to "change", "timeout_ms" to 1_500)
        assertFalse(r.json["matched"]!!.jsonPrimitive.boolean)
        assertTrue(r.image != null, "timeout still returns the latest frame")
    }

    @Test fun waitForChangeInRoiIgnoresChangesOutside() = runBlocking {
        val wait = async { call("wait_for", "condition" to "change", "roi" to "0.6,0.35,0.1,0.15", "timeout_ms" to 1_500) }
        delay(300)
        scene = Fixtures.scene(handX = 0.3)
        assertFalse(wait.await().json["matched"]!!.jsonPrimitive.boolean)
        scene = STATIC
        val led = async { call("wait_for", "condition" to "change", "roi" to "0.6,0.35,0.1,0.15", "timeout_ms" to 3_000) }
        delay(300)
        scene = Fixtures.scene(ledOn = true)
        assertTrue(led.await().json["matched"]!!.jsonPrimitive.boolean)
        scene = STATIC
    }

    @Test fun waitForStillAfterMotionStops() = runBlocking {
        val mover = scope.launch {
            var x = 0.1
            while (isActive) { scene = Fixtures.scene(handX = x); x = if (x > 0.5) 0.1 else x + 0.1; delay(60) }
        }
        val wait = async { call("wait_for", "condition" to "still", "for_ms" to 500, "timeout_ms" to 5_000) }
        delay(800)
        mover.cancel()
        scene = STATIC
        val stoppedAt = System.currentTimeMillis()
        val r = wait.await()
        assertTrue(r.json["matched"]!!.jsonPrimitive.boolean)
        assertTrue(System.currentTimeMillis() - stoppedAt >= 400, "waited for the still period")
    }

    @Test fun waitForHumanAck() = runBlocking {
        val wait = async { call("wait_for", "condition" to "human_ack", "message" to "Press reset", "timeout_ms" to 5_000) }
        val prompt = core.human.current.filterNotNull().first()
        assertEquals("Press reset", prompt.text)
        core.human.answer(prompt.id, "Done")
        assertTrue(wait.await().json["matched"]!!.jsonPrimitive.boolean)
    }

    @Test fun waitTimeoutIsNotAnError() = runBlocking {
        val r = call("wait_for", "condition" to "motion", "timeout_ms" to 300)
        assertFalse(r.json["matched"]!!.jsonPrimitive.boolean)
    }

    @Test fun tooManyWaitsAreRejected() = runBlocking {
        val waits = List(4) { async { call("wait_for", "condition" to "change", "timeout_ms" to 1_500) } }
        delay(300)
        assertEquals("too_many_waits", assertFailsWith<LookoutError> { call("wait_for", "condition" to "change", "timeout_ms" to 100) }.code)
        waits.forEach { it.await() }
    }

    @Test fun historyReturnsRequestedTiles() = runBlocking {
        core.updateSettings { it.copy(bufferIntervalMs = 50) }
        delay(1_500)
        val r = call("history", "since" to "-2s", "tiles" to 12)
        val tiles = r.json["tiles"]!!.jsonArray
        assertEquals(12, tiles.size)
        val times = tiles.map { Instant.parse(it.jsonObject["captured_at"]!!.jsonPrimitive.content).toEpochMilli() }
        assertEquals(times.sorted(), times)
        val sheet = AwtImageOps.decode(r.image!!.jpeg)
        assertTrue(sheet.width <= 1568 && sheet.height <= 1568)
        assertEquals("no_history", assertFailsWith<LookoutError> { call("history", "since" to "-20m", "until" to "-10m") }.code)
    }

    @Test fun tiltReachesFrameMetadataDescribeAndDiff() = runBlocking {
        source.tilt = Tilt(-10.0, 1.5)
        core.store.latest.filter { it?.camera?.tilt != null }.first()
        val look = call("look").json
        assertEquals(-10.0, look["pitch_deg"]!!.jsonPrimitive.double)
        assertEquals(1.5, look["roll_deg"]!!.jsonPrimitive.double)
        assertEquals(-10.0, call("describe").json["tilt"]!!.jsonObject["pitch_deg"]!!.jsonPrimitive.double)

        call("baseline", "action" to "save", "name" to "desk")
        assertEquals(Tilt(-10.0, 1.5), core.baselines.get("desk")!!.tilt)
        assertFalse(call("diff", "a" to "desk").json["camera_moved"]!!.jsonPrimitive.boolean)

        source.tilt = Tilt(-14.0, 1.5)
        core.store.latest.filter { it?.camera?.tilt?.pitchDeg == -14.0 }.first()
        val d = call("diff", "a" to "desk").json
        assertTrue(d["camera_moved"]!!.jsonPrimitive.boolean)
        assertEquals(4.0, d["tilt_change_deg"]!!.jsonPrimitive.double, 0.05)
        val moved = core.events.since(0, setOf("moved"))
        assertEquals(1, moved.size, "one moved event for one bump")
        assertEquals(-14.0, moved.single().data["pitch_deg"]!!.jsonPrimitive.double)
    }

    @Test fun baselineAndDiff() = runBlocking {
        core.store.latest.filterNotNull().first()
        call("baseline", "action" to "save", "name" to "empty")
        assertEquals(1, core.baselines.list().size)
        val same = call("diff", "a" to "empty")
        assertFalse(same.json["changed"]!!.jsonPrimitive.boolean)
        scene = Fixtures.scene(handX = 0.4)
        delay(150)
        val changed = call("diff", "a" to "empty")
        assertTrue(changed.json["changed"]!!.jsonPrimitive.boolean)
        assertTrue((changed.json["regions"] as JsonArray).isNotEmpty())
        assertTrue(core.tools.frameJpeg(changed.json["image_id"]!!.jsonPrimitive.content) != null)
        scene = STATIC
        call("baseline", "action" to "delete", "name" to "empty")
        assertEquals("baseline_not_found", assertFailsWith<LookoutError> { call("diff", "a" to "empty") }.code)
    }

    @Test fun fullDetailReturnsAFullResolutionStill() = runBlocking {
        val big = PushSource(still = Fixtures.scene(3000, 2000))
        val c = Fixtures.core(big).also { it.start(scope) }
        val r = c.capability("look").call(args("detail" to "full"), ctx)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertEquals(1568, r.json["width"]!!.jsonPrimitive.long.toInt())
        assertEquals(1568, AwtImageOps.decode(r.image!!.jpeg).width)
        assertEquals(800, c.capability("look").call(args("detail" to "full", "width" to 800), ctx).json["width"]!!.jsonPrimitive.long.toInt())
    }

    @Test fun fullDetailWaitsToSettle() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("look", "detail" to "full", "settle_ms" to 200)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertTrue(r.json["settled"]!!.jsonPrimitive.boolean)
    }

    @Test fun invalidDetailIsRejected() = runBlocking {
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "detail" to "max") }.code)
    }

    @Test fun saveAsImpliesFullDetail() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("look", "save_as" to "board-off", "torch" to true)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertTrue(core.baselines.get("board-off") != null)
        assertEquals(false, source.applied.torch, "torch reverts after the shot")
    }

    @Test fun guidedFastLookTakesAFrameAfterReady() = runBlocking {
        core.store.latest.filterNotNull().first()
        val shot = async { call("look", "guide_text" to "Hold the board flat", "fresh" to false) }
        val p = core.human.current.filterNotNull().first()
        delay(200)
        assertFalse(shot.isCompleted, "no frame before Ready")
        val ready = System.currentTimeMillis()
        core.human.answer(p.id, Tools.READY)
        val r = shot.await()
        assertEquals("analysis", r.json["source"]!!.jsonPrimitive.content)
        assertTrue(Instant.parse(r.json["captured_at"]!!.jsonPrimitive.content).toEpochMilli() >= ready, "the frame comes from after Ready")
    }

    @Test fun guidedFullLookWaitsForReady() = runBlocking {
        core.store.latest.filterNotNull().first()
        val png = java.io.ByteArrayOutputStream().also { out ->
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
        val shot = async {
            call("look", "detail" to "full", "guide_text" to "Hold the board flat", "guide_image" to "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png))
        }
        val p = core.human.current.filterNotNull().first()
        assertTrue(p.guide)
        assertEquals("Hold the board flat", p.text)
        assertEquals(listOf(Tools.READY, Tools.CANCEL), p.buttons)
        val example = AwtImageOps.decode(p.image!!)
        assertEquals(1280, example.width, "the example is scaled for the phone")
        delay(200)
        assertFalse(shot.isCompleted, "no shot before Ready")
        core.human.answer(p.id, Tools.READY)
        assertEquals("still", shot.await().json["source"]!!.jsonPrimitive.content)
    }

    @Test fun guidedShotCanBeCancelledOrTimeOut() = runBlocking {
        val shot = async { runCatching { call("record", "action" to "start", "guide_text" to "Point at the LED") } }
        val p = core.human.current.filterNotNull().first()
        core.human.answer(p.id, Tools.CANCEL)
        assertEquals("declined", (shot.await().exceptionOrNull() as LookoutError).code)
        assertEquals("ready_timeout", assertFailsWith<LookoutError> { call("look", "guide_text" to "Anyone?", "ready_timeout_ms" to 1_000) }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "guide_image" to "not an image") }.code)
    }

    @Test fun askReturnsTappedChoice() = runBlocking {
        val ask = async { call("ask", "text" to "Ready?", "buttons" to "Yes,No", "timeout_ms" to 5_000) }
        val p = core.human.current.filterNotNull().first()
        assertEquals(listOf("Yes", "No"), p.buttons)
        core.human.answer(p.id, "No")
        val r = ask.await().json
        assertTrue(r["answered"]!!.jsonPrimitive.boolean)
        assertEquals("No", r["choice"]!!.jsonPrimitive.content)
        val timeout = call("ask", "text" to "Anyone?", "timeout_ms" to 1_000).json
        assertFalse(timeout["answered"]!!.jsonPrimitive.boolean)
    }

    @Test fun pointShowsMarksAndClears() = runBlocking {
        val marks = LookoutJson.parseToJsonElement("""[{"shape":"box","at":"C2:E4","label":"board"},{"at":"F3"}]""")
        val r = call("point", "marks" to marks, "text" to "Plug the red wire here", "timeout_ms" to 5_000)
        assertEquals(null, r.image, "no image without preview")
        val set = core.pointer.current.value!!
        assertEquals(2, set.marks.size)
        assertEquals("Plug the red wire here", set.text)
        assertTrue("expires_at" in r.json)
        call("point", "action" to "clear")
        assertEquals(null, core.pointer.current.value)
        assertEquals("missing_param", assertFailsWith<LookoutError> { call("point") }.code)
    }

    @Test fun pointWithButtonsWaitsForATapThenClears() = runBlocking {
        core.store.latest.filterNotNull().first()
        val point = async { call("point", "marks" to "[{\"at\":\"0.5,0.5\"}]", "text" to "Press this button", "buttons" to "Done", "preview" to true) }
        val p = core.human.current.filterNotNull().first()
        assertTrue(p.guide)
        assertEquals(Tools.POINT_HEADING, p.heading)
        assertEquals("Press this button", p.text)
        val set = core.pointer.current.value!!
        assertEquals(null, set.text, "the panel shows the text, not the caption")
        core.human.answer(p.id, "Done")
        val r = point.await()
        assertEquals("Done", r.json["choice"]!!.jsonPrimitive.content)
        assertTrue(r.image!!.jpeg.isNotEmpty(), "preview returns the frame with the marks")
        assertTrue("frame_id" in r.json["preview"]!!.jsonObject)
        assertEquals(null, core.pointer.current.value, "the marks clear after the tap")
    }

    @Test fun captionShowsOnImagesUntilItEnds() = runBlocking {
        core.store.latest.filterNotNull().first()
        assertEquals(0, CaptionsTest.yellowPixels(call("look").image!!.jpeg), "the scene has no caption yellow")
        val r = call("caption", "text" to "sent M106", "duration_ms" to 600).json
        assertEquals("sent M106", r["text"]!!.jsonPrimitive.content)
        assertTrue("until" in r)
        assertTrue(CaptionsTest.yellowPixels(call("look", "roi" to "0.5,0,0.5,0.5").image!!.jpeg) > 0, "a cropped look has it too")
        delay(700)
        assertEquals(0, CaptionsTest.yellowPixels(call("look").image!!.jpeg), "it goes away after duration_ms")

        call("caption", "text" to "pressed reset")
        assertTrue(CaptionsTest.yellowPixels(call("look").image!!.jpeg) > 0)
        assertTrue(call("caption", "action" to "clear").json["cleared"]!!.jsonPrimitive.boolean)
        assertEquals(0, CaptionsTest.yellowPixels(call("look").image!!.jpeg))

        assertEquals("missing_param", assertFailsWith<LookoutError> { call("caption") }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("caption", "text" to "x".repeat(81)) }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("caption", "text" to "x", "duration_ms" to 10) }.code)
    }

    @Test fun readReturnsNumbersAndFullFrameBoxes() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("read", "roi" to "0.5,0.5,0.25,0.25", "source" to "frame")
        assertEquals(null, r.image)
        assertEquals("DC 12.04 V\nHOLD", r.json["text"]!!.jsonPrimitive.content)
        assertEquals(12.04, r.json["value"]!!.jsonPrimitive.double)
        assertEquals("V", r.json["unit"]!!.jsonPrimitive.content)
        val box = r.json["lines"]!!.jsonArray[0].jsonObject["box"]!!.jsonObject
        assertEquals(0.525, box["x"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.55, box["y"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.125, box["w"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(640, maxOf(readImage!!.width, readImage!!.height), "a 160 px crop is scaled up 4x before OCR")
        val withImage = call("read", "image" to true)
        assertEquals("still", withImage.json["source"]!!.jsonPrimitive.content)
        assertTrue(withImage.image!!.jpeg.isNotEmpty())
    }

    @Test fun readLogsAValueOverTime() = runBlocking {
        core.store.latest.filterNotNull().first()
        val changer = launch { delay(700); meterText = "DC 13.5 V" }
        val r = call("read", "duration_ms" to 1_500, "every_ms" to 300).json
        changer.join()
        val samples = r["samples"]!!.jsonArray
        assertTrue(samples.size in 3..6, "about 5 samples, got ${samples.size}")
        assertEquals(12.04, r["min"]!!.jsonPrimitive.double)
        assertEquals(13.5, r["max"]!!.jsonPrimitive.double)
        assertEquals(13.5, r["last"]!!.jsonPrimitive.double)
        assertEquals("V", r["unit"]!!.jsonPrimitive.content)
        assertEquals(12.04, samples[0].jsonArray[1].jsonPrimitive.double)
    }

    @Test fun waitForValueMatchesAboveAndText() = runBlocking {
        core.store.latest.filterNotNull().first()
        val changer = launch { delay(600); meterText = "Nozzle 205 C" }
        val r = call("wait_for", "condition" to "value", "above" to 200, "every_ms" to 250, "timeout_ms" to 5_000).json
        changer.join()
        assertTrue(r["matched"]!!.jsonPrimitive.boolean)
        assertEquals(205.0, r["value"]!!.jsonPrimitive.double)
        val timeout = call("wait_for", "condition" to "value", "text" to "done", "timeout_ms" to 800).json
        assertFalse(timeout["matched"]!!.jsonPrimitive.boolean)
        assertEquals(205.0, timeout["value"]!!.jsonPrimitive.double, "a timeout still reports the last value read")
        meterText = "PRINT DONE"
        assertTrue(call("wait_for", "condition" to "value", "text" to "done", "timeout_ms" to 2_000).json["matched"]!!.jsonPrimitive.boolean)
        assertEquals("missing_param", assertFailsWith<LookoutError> { call("wait_for", "condition" to "value") }.code)
        meterText = "DC 12.04 V"
        val glitch = launch { delay(300); meterText = "DC 709 V"; delay(200); meterText = "DC 12.04 V" }
        val steady = call("wait_for", "condition" to "value", "above" to 100, "every_ms" to 250, "timeout_ms" to 1_500).json
        glitch.join()
        assertFalse(steady["matched"]!!.jsonPrimitive.boolean, "one misread does not end the wait")
    }

    @Test fun readWithoutAReaderIsUnsupported() = runBlocking {
        val bare = Fixtures.core(PushSource())
        assertEquals("read_unsupported", assertFailsWith<LookoutError> { bare.capability("read").call(Args.EMPTY, ctx) }.code)
    }

    @Test fun pausedRejectsImages() = runBlocking {
        core.setPaused(true)
        assertEquals("paused", assertFailsWith<LookoutError> { call("look") }.code)
        core.setPaused(false)
        assertTrue(call("look").image != null)
    }

    @Test fun unknownAndMissingParamsAreErrors() = runBlocking {
        assertEquals("unknown_param", assertFailsWith<LookoutError> { call("look", "colour" to "red") }.code)
        assertEquals("missing_param", assertFailsWith<LookoutError> { call("wait_for") }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("wait_for", "condition" to "sound") }.code)
    }

    @Test fun cameraToolSetsMode() = runBlocking {
        val r = call("camera", "mode" to "cold", "zoom" to 2.0)
        assertEquals("cold", r.json["effective_mode"]!!.jsonPrimitive.content)
        assertEquals(2.0, source.applied.zoom)
        call("camera", "mode" to "default")
        assertEquals("warm", core.session.effectiveMode().wire)
    }

    @Test fun describeHasTheEssentials() = runBlocking {
        val d = call("describe").json
        for (k in listOf("lenses", "camera", "rois", "baselines", "history", "device", "api_version", "asleep")) assertTrue(k in d, "describe has $k")
        assertEquals(16, core.tools.mcpTools.size)
    }

    companion object {
        private val STATIC: Img = Fixtures.scene()
    }
}
