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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
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
        for (k in listOf("frame_id", "captured_at", "age_ms", "lens", "zoom", "ae_converged", "brightness")) {
            assertTrue(k in r.json, "meta has $k")
        }
        for (k in listOf("battery_pct", "charging", "temperature_c", "thermal")) assertFalse(k in r.json, "a healthy phone sends no $k")
        assertEquals(320, AwtImageOps.decode(r.image!!.jpeg).width)
        assertNoLongDecimals(r.json)
    }

    @Test fun cameraFieldsComeOnlyWhenTheyChangeOrWithVerbose() = runBlocking {
        val first = call("look").json
        for (k in listOf("lens", "zoom", "ae_converged")) assertTrue(k in first, "the first result has $k")
        val second = call("look").json
        for (k in listOf("lens", "zoom", "ae_converged")) assertFalse(k in second, "the second result leaves out $k")
        val verbose = call("look", "verbose" to true).json
        for (k in listOf("lens", "zoom", "ae_converged", "battery_pct", "charging", "thermal")) assertTrue(k in verbose, "verbose has $k")
        val other = core.capability("look").call(args(), CallContext("test", "10.0.0.9")).json
        assertTrue("lens" in other, "another client gets its own first result")
    }

    @Test fun cameraFieldsCountAsChangedOnlyPastTheirTolerance() {
        val f = CameraFields()
        val base = CameraMeta(zoom = 1.0, focusDistance = 2.0, exposureTimeNs = 10_000_000, iso = 100, whiteBalance = "auto")
        assertEquals(setOf("lens", "zoom", "focus_distance", "exposure_time_ns", "iso", "white_balance", "ae_converged"), f.select("c", base, false).keys)
        val drift = base.copy(zoom = 1.005, focusDistance = 2.05, exposureTimeNs = 12_000_000, iso = 120)
        assertEquals(emptySet(), f.select("c", drift, false).keys, "auto exposure drift is not a change")
        val changed = base.copy(lens = "front", zoom = 2.0, focusDistance = 3.0, exposureTimeNs = 20_000_000, iso = 400, whiteBalance = "off")
        assertEquals(setOf("lens", "zoom", "focus_distance", "exposure_time_ns", "iso", "white_balance"), f.select("c", changed, false).keys)
        assertEquals(setOf("ae_converged"), f.select("c", changed.copy(aeConverged = false), false).keys)
        assertEquals(setOf("ae_converged"), f.select("c", changed.copy(aeConverged = false), false).keys, "ae_converged=false is always sent")
        assertEquals(7, f.select("c", changed, true).size, "all fields with verbose")
    }

    @Test fun deviceFieldsComeOnlyPastAWarningLevel() {
        assertEquals(emptySet(), DeviceState(batteryPct = 80, charging = false, temperatureC = 35.0, thermal = Thermal.LIGHT).metaFields(false).keys)
        assertEquals(emptySet(), DeviceState(batteryPct = 10, charging = true).metaFields(false).keys, "a low battery that charges is fine")
        assertEquals(setOf("battery_pct", "charging"), DeviceState(batteryPct = 19, charging = false).metaFields(false).keys)
        assertEquals(setOf("temperature_c"), DeviceState(temperatureC = 40.0).metaFields(false).keys)
        assertEquals(setOf("thermal"), DeviceState(thermal = Thermal.MODERATE).metaFields(false).keys)
        assertEquals(setOf("battery_pct", "charging", "temperature_c", "thermal"), DeviceState(80, true, 30.0).metaFields(true).keys)
    }

    @Test fun lookAtAWarningLevelSendsTheDeviceFields() = runBlocking {
        val hot = Fixtures.core(source, device = DeviceState(batteryPct = 12, charging = false, thermal = Thermal.SEVERE)).also { it.start(scope) }
        val r = hot.capability("look").call(args(), ctx).json
        assertEquals(12, r["battery_pct"]!!.jsonPrimitive.long.toInt())
        assertEquals("severe", r["thermal"]!!.jsonPrimitive.content)
        assertFalse("temperature_c" in r)
    }

    @Test fun roundsResultNumbersAndKeepsStoredOnes() = runBlocking {
        val third = 1.0 / 3
        call("roi", "action" to "set", "name" to "third", "rect" to buildJsonObject { put("x", third); put("y", third); put("w", third); put("h", third) })
        assertEquals(third, core.rois.require("third").rect.x, "the stored ROI keeps its precision")
        val look = call("look", "roi" to "0.123456,0.2,0.5,0.5").json
        assertNoLongDecimals(look)
        assertEquals(0.123, look["roi"]!!.jsonObject["x"]!!.jsonPrimitive.double)
        call("baseline", "action" to "save", "name" to "empty")
        scene = Fixtures.scene(handX = 0.4)
        delay(150)
        assertNoLongDecimals(call("diff", "a" to "empty").json)
        scene = STATIC
        assertEquals(JsonPrimitive(0.167), roundNumbers(JsonPrimitive(0.16666666666666666)))
        assertEquals(JsonPrimitive(12L), roundNumbers(JsonPrimitive(12L)))
        assertEquals(JsonPrimitive("0.16666666"), roundNumbers(JsonPrimitive("0.16666666")))
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
        assertEquals(listOf("frame_id", "captured_at"), r.json["tile_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        val times = tiles.map { Instant.parse(it.jsonArray[1].jsonPrimitive.content).toEpochMilli() }
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
        assertTrue(core.tools.frameJpeg(changed.json["image_id"]!!.jsonPrimitive.content).isNotEmpty())
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

    @Test fun aFrameFromLookStaysForDiffAndCropAfterNewerFramesAndAfterTheCameraCloses() = runBlocking {
        core.updateSettings { it.copy(bufferIntervalMs = 600_000) }
        val a = call("look").json["frame_id"]!!.jsonPrimitive.long
        val b = call("look").json["frame_id"]!!.jsonPrimitive.long
        core.store.latest.first { (it?.id ?: 0) > b + 20 }
        core.stop()
        assertEquals(null, core.store.latest.value, "the camera is closed")
        val d = call("diff", "a" to a.toString(), "b" to b.toString(), "image" to false).json
        assertEquals("#$a", d["a"]!!.jsonPrimitive.content.substringBefore(' '))
        val crop = call("crop", "frame" to a.toString(), "roi" to "0.25,0.25,0.5,0.5")
        assertEquals("analysis", crop.json["source"]!!.jsonPrimitive.content, "from the kept frame, not the 640 px history")
        assertEquals(320, crop.json["width"]!!.jsonPrimitive.long.toInt())
        assertEquals(a, crop.json["frame_id"]!!.jsonPrimitive.long)
        assertTrue("captured_at" in crop.json)
    }

    @Test fun aStillStaysAtFullResolutionForCrop() = runBlocking {
        val big = PushSource(still = Fixtures.scene(3000, 2000))
        val c = Fixtures.core(big).also { it.start(scope) }
        val id = c.capability("look").call(args("detail" to "full", "width" to 400), ctx).json["frame_id"]!!.jsonPrimitive.long
        c.stop()
        val r = c.capability("crop").call(args("frame" to id.toString(), "roi" to "0.4,0.4,0.2,0.2", "width" to 1568), ctx)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertEquals(3000, r.json["source_width"]!!.jsonPrimitive.long.toInt())
        assertEquals(600, AwtImageOps.decode(r.image!!.jpeg).width, "0.2 of a 3000 px still, not of a preview")
        assertFalse(big.isOpen, "crop does not open the camera")
        assertEquals(3000, AwtImageOps.decode(c.tools.frameJpeg(id.toString())).width, "the frame route gives the kept still at full resolution")
    }

    @Test fun cropOfABaseline() = runBlocking {
        core.store.latest.filterNotNull().first()
        call("baseline", "action" to "save", "name" to "desk")
        val r = call("crop", "frame" to "desk", "roi" to "0,0,0.5,0.5", "grid" to "4x3")
        assertEquals("desk", r.json["baseline"]!!.jsonPrimitive.content)
        assertEquals("baseline", r.json["source"]!!.jsonPrimitive.content)
        assertEquals(320, AwtImageOps.decode(r.image!!.jpeg).width)
        assertEquals("baseline_not_found", assertFailsWith<LookoutError> { call("crop", "frame" to "nope") }.code)
    }

    @Test fun inlineFalseStoresTheImageAndLinksIt() = runBlocking {
        val r = call("look", "inline" to false, "width" to 300)
        assertEquals(null, r.image)
        val id = r.json["image_id"]!!.jsonPrimitive.content
        assertEquals("/v1/frames/$id.jpg", r.json["image_url"]!!.jsonPrimitive.content, "no base URL in core, so the path stays relative")
        assertEquals(300, AwtImageOps.decode(core.tools.frameJpeg(id)).width)
        val w = call("wait_for", "condition" to "change", "timeout_ms" to 200, "inline" to false)
        assertEquals(null, w.image)
        assertTrue(core.composites.get(w.json["image_id"]!!.jsonPrimitive.content) != null)
        for (k in listOf("before_frame_id", "after_frame_id")) core.store.find(w.json[k]!!.jsonPrimitive.long)
        assertTrue("inline" in core.capability("diff").params.map { it.name })
        assertFalse("inline" in core.capability("roi").params.map { it.name })
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
        assertEquals(17, core.tools.mcpTools.size)
    }

    @Test fun fastWarmLookHasNoTimingOrColdStart() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("look").json
        assertFalse("cold_start" in r, "warm look has no cold_start")
        assertFalse("hint" in r, "warm look has no hint")
        assertFalse("timing" in r, "fast look has no timing")
    }

    @Test fun lookColdStartReportsFlagAndModeSpecificHint() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        var state = DeviceState(charging = false, batteryPct = 50)
        val s = PushSource(still = STATIC, clock = clock)
        val c = LookoutCore(AwtImageOps, s, MemoryKeyValueStore(), MemoryBlobStore(), { state }, { true }, clock)

        // Default on battery is auto: cold_start is true on first open, no hint, no timing under 2 s.
        val autoShot = async { c.capability("look").call(args("detail" to "full"), ctx) }.await().json
        assertEquals(true, autoShot["cold_start"]!!.jsonPrimitive.boolean)
        assertFalse("hint" in autoShot, "auto mode gives no hint")
        assertFalse("timing" in autoShot, "fast cold start gives no timing")

        // Second look while still open in auto mode is warm: no cold_start, no hint.
        val warmAgain = c.capability("look").call(args("detail" to "full"), ctx).json
        assertFalse("cold_start" in warmAgain)
        assertFalse("hint" in warmAgain)

        // Explicit cold mode after the camera closes gives cold_start and the mode hint.
        c.updateSettings { it.copy(powerMode = PowerMode.COLD) }
        now += CameraSession.COLD_IDLE_MS + 1
        c.session.reconcile()
        assertFalse(s.isOpen)
        val coldShot = c.capability("look").call(args("detail" to "full"), ctx).json
        assertEquals(true, coldShot["cold_start"]!!.jsonPrimitive.boolean)
        assertEquals(
            "The camera was closed. camera(mode=warm) or mode=auto keeps it open between calls.",
            coldShot["hint"]!!.jsonPrimitive.content,
        )

        // SEVERE thermal forces cold and gives the thermal hint.
        c.updateSettings { it.copy(powerMode = PowerMode.WARM) }
        state = DeviceState(charging = true, batteryPct = 80, thermal = Thermal.SEVERE)
        now += CameraSession.COLD_IDLE_MS + 1
        c.session.reconcile()
        assertFalse(s.isOpen)
        val hotShot = c.capability("look").call(args("detail" to "full"), ctx).json
        assertEquals(true, hotShot["cold_start"]!!.jsonPrimitive.boolean)
        assertTrue("too hot to stay warm" in hotShot["hint"]!!.jsonPrimitive.content)
    }

    @Test fun slowLookReportsTimingPartsAndExcludesGuideWait() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        var openDelay = 1_200L
        var stillDelay = 500L
        var encodeDelay = 150L
        val ops = object : ImageOps by AwtImageOps {
            override fun encodeJpeg(img: Img, quality: Int): ByteArray {
                if (quality == 90) now += encodeDelay
                return AwtImageOps.encodeJpeg(img, quality)
            }
        }
        val s = PushSource(still = STATIC, clock = clock, onOpen = { now += openDelay }, onStill = { now += stillDelay })
        val c = LookoutCore(ops, s, MemoryKeyValueStore(), MemoryBlobStore(), { DeviceState(charging = false, batteryPct = 50) }, { true }, clock)

        val shot = async {
            c.capability("look").call(args("detail" to "full", "settle_ms" to 1_000, "guide_text" to "Hold steady", "quality" to 90), ctx)
        }
        val prompt = c.human.current.filterNotNull().first()
        now += 10_000 // waiting for the person to tap Ready is not part of total_ms
        c.human.answer(prompt.id, Tools.READY)
        c.session.open.first { it }
        delay(50)
        now += 400
        s.push(STATIC)
        delay(50)
        now += 600
        s.push(STATIC)
        val meta = shot.await().json
        assertEquals(true, meta["cold_start"]!!.jsonPrimitive.boolean)
        val timing = meta["timing"]!!.jsonObject
        assertEquals(2_850L, timing["total_ms"]!!.jsonPrimitive.long)
        assertEquals(1_200L, timing["open_ms"]!!.jsonPrimitive.long)
        assertEquals(400L, timing["converge_ms"]!!.jsonPrimitive.long)
        assertEquals(600L, timing["settle_ms"]!!.jsonPrimitive.long)
        assertEquals(500L, timing["still_ms"]!!.jsonPrimitive.long)
        assertEquals(150L, timing["encode_ms"]!!.jsonPrimitive.long)

        // Warm slow still omits parts that did not happen or were 0.
        openDelay = 0L
        stillDelay = 2_100L
        encodeDelay = 0L
        val warmSlow = c.capability("look").call(args("detail" to "full", "quality" to 90), ctx).json
        assertFalse("cold_start" in warmSlow)
        val warmTiming = warmSlow["timing"]!!.jsonObject
        assertEquals(2_100L, warmTiming["total_ms"]!!.jsonPrimitive.long)
        assertEquals(2_100L, warmTiming["still_ms"]!!.jsonPrimitive.long)
        assertFalse("open_ms" in warmTiming)
        assertFalse("converge_ms" in warmTiming)
        assertFalse("settle_ms" in warmTiming)
        assertFalse("encode_ms" in warmTiming)

        // A long guide wait followed by a fast capture does not trigger timing.
        stillDelay = 100L
        val guidedFast = async {
            c.capability("look").call(args("detail" to "full", "guide_text" to "Ready?", "quality" to 90), ctx)
        }
        val prompt2 = c.human.current.filterNotNull().first()
        now += 8_000
        c.human.answer(prompt2.id, Tools.READY)
        assertFalse("timing" in guidedFast.await().json, "Ready wait does not count toward total_ms")
    }

    @Test fun darkFrameGuardOnLook() = runBlocking {
        val black = AwtImageOps.canvas(640, 480, Colors.BLACK)
        val s = PushSource(still = black)
        val c = Fixtures.core(s).also { it.start(scope) }
        val pusher = launch {
            while (isActive) {
                s.push(black)
                delay(30)
            }
        }
        c.store.latest.filterNotNull().first()

        // detail=fast on a black frame returns JSON only with dark=true and hint.
        val fast = c.capability("look").call(Args.EMPTY, ctx)
        assertEquals(null, fast.image)
        assertTrue(fast.json["dark"]!!.jsonPrimitive.boolean)
        assertTrue(fast.json["brightness"]!!.jsonPrimitive.double < Tools.DARK_FRAME)
        assertTrue("frame_id" in fast.json)
        assertFalse("width" in fast.json)
        val hint = fast.json["hint"]!!.jsonPrimitive.content
        assertTrue("force=true" in hint && "torch=true" in hint, hint)

        // force=true returns the image as usual.
        val forcedFast = c.capability("look").call(args("force" to true), ctx)
        assertTrue(forcedFast.image!!.jpeg.isNotEmpty())
        assertFalse("dark" in forcedFast.json)
        assertFalse("hint" in forcedFast.json)

        // detail=full also guards dark frames.
        val full = c.capability("look").call(args("detail" to "full"), ctx)
        assertEquals(null, full.image)
        assertEquals("still", full.json["source"]!!.jsonPrimitive.content)
        assertTrue(full.json["dark"]!!.jsonPrimitive.boolean)
        val forcedFull = c.capability("look").call(args("detail" to "full", "force" to true), ctx)
        assertTrue(forcedFull.image!!.jpeg.isNotEmpty())
        assertFalse("dark" in forcedFull.json)

        // save_as does not save a dark frame without force=true.
        val unsaved = c.capability("look").call(args("save_as" to "dark-ref"), ctx)
        assertEquals(null, unsaved.image)
        assertTrue(unsaved.json["dark"]!!.jsonPrimitive.boolean)
        assertFalse(unsaved.json["saved"]!!.jsonPrimitive.boolean)
        assertEquals(null, c.baselines.get("dark-ref"))

        // save_as with force=true saves the baseline and returns the image.
        val saved = c.capability("look").call(args("save_as" to "dark-ref", "force" to true), ctx)
        assertTrue(saved.image!!.jpeg.isNotEmpty())
        assertTrue(c.baselines.get("dark-ref") != null)
        assertFalse("dark" in saved.json)
        assertFalse("saved" in saved.json)
        pusher.cancel()

        // Normal frame is unchanged.
        core.store.latest.filterNotNull().first()
        val normal = call("look")
        assertTrue(normal.image!!.jpeg.isNotEmpty())
        assertFalse("dark" in normal.json)

        // A dark roi in a lit frame still gives the image: a black part is a valid subject.
        val half = AwtImageOps.canvas(640, 480, Colors.BLACK).also {
            AwtImageOps.drawRect(it, PixelRect(320, 0, 320, 480), Colors.WHITE, 0f, fill = true)
        }
        val hs = PushSource(still = half)
        val hc = Fixtures.core(hs).also { it.start(scope) }
        val darkRoi = hc.capability("look").call(args("detail" to "full", "roi" to "0,0,0.4,1"), ctx)
        assertTrue(darkRoi.image!!.jpeg.isNotEmpty())
        assertFalse("dark" in darkRoi.json)
    }

    @Test fun diffWithoutImage() = runBlocking {
        var encoded = 0
        val ops = object : ImageOps by AwtImageOps {
            override fun encodeJpeg(img: Img, quality: Int): ByteArray {
                encoded++
                return AwtImageOps.encodeJpeg(img, quality)
            }
        }
        val s = PushSource(still = Fixtures.scene())
        val c = LookoutCore(ops, s, MemoryKeyValueStore(), MemoryBlobStore(), { DeviceState(charging = true, batteryPct = 80) }, { true })
        c.updateSettings { it.copy(bufferIntervalMs = 60_000) }
        c.start(scope)
        val pusher = launch {
            while (isActive) {
                s.push(Fixtures.scene(handX = 0.4))
                delay(30)
            }
        }
        c.store.latest.filterNotNull().first()
        c.capability("baseline").call(args("action" to "save", "name" to "empty"), ctx)
        val encodedBefore = encoded
        val r = c.capability("diff").call(args("a" to "empty", "image" to false), ctx)
        pusher.cancel()
        assertEquals(null, r.image)
        assertFalse("image_id" in r.json)
        assertFalse("image_url" in r.json)
        assertTrue(r.json["changed"]!!.jsonPrimitive.boolean)
        assertTrue(r.json["score"]!!.jsonPrimitive.double > 0.02)
        assertTrue((r.json["regions"] as JsonArray).isNotEmpty())
        assertEquals(encodedBefore, encoded, "composite image is not encoded when image=false")
        assertEquals(null, c.composites.get("c1"), "composite image is not stored when image=false")
    }

    /** A core whose camera shows [view] (read on every frame), and a function that returns the id of a fresh frame. */
    private fun cameraOn(view: () -> Img, still: Img? = null): Pair<LookoutCore, suspend () -> String> {
        val s = PushSource(still = still)
        val c = Fixtures.core(s).also { it.start(scope) }
        scope.launch {
            while (isActive) {
                s.push(view())
                delay(30)
            }
        }
        return c to { c.capability("look").call(args(), ctx).json["frame_id"]!!.jsonPrimitive.content }
    }

    private fun JsonObject.pair(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.double }

    /** The textured background with a textured 120 × 60 px object at ([ox], 200). */
    private fun objectScene(ox: Double, scale: Double = 1.0): Img {
        val back = Fixtures.Blobs(1)
        val part = Fixtures.Blobs(2, 120.0, 60.0, amplitude = 110.0)
        return Fixtures.render((640 * scale).toInt(), (480 * scale).toInt(), scale) { x, y ->
            if (x >= ox && x < ox + 120 && y >= 200 && y < 260) part.at(x - ox, y - 200) else back.at(x, y)
        }
    }

    @Test fun diffAlignsAWholeSceneThatMovedAFewPixels() = runBlocking {
        val view = AtomicReference<Img>(Fixtures.texture())
        val (c, look) = cameraOn(view::get)
        val a = look()
        view.set(Fixtures.texture(dx = 6.0, dy = -4.0))
        delay(100)
        val b = look()
        val before = c.detector.compare(c.detector.thumb(c.store.find(a.toLong()).image()), c.detector.thumb(c.store.find(b.toLong()).image()))
        assertTrue(before.score > 0.1, "without alignment the score is ${before.score}")
        val d = c.capability("diff").call(args("a" to a, "b" to b, "image" to false), ctx).json
        assertFalse(d["changed"]!!.jsonPrimitive.boolean, "score ${d["score"]}")
        assertTrue(d["aligned"]!!.jsonPrimitive.boolean)
        val (sx, sy) = d.pair("global_shift_px")
        assertEquals(6.0, sx, 0.3)
        assertEquals(-4.0, sy, 0.3)
        assertFalse("dx" in d, "no roi, no content shift")
    }

    @Test fun diffMeasuresHowFarAnObjectInTheRoiMoved() = runBlocking {
        val view = AtomicReference(objectScene(200.0))
        val (c, look) = cameraOn(view::get)
        val a = look()
        view.set(objectScene(218.5))
        delay(100)
        val b = look()
        val r = c.capability("diff").call(args("a" to a, "b" to b, "roi" to "0.29,0.4,0.24,0.15"), ctx)
        val d = r.json
        assertTrue(d["changed"]!!.jsonPrimitive.boolean)
        assertTrue(d["aligned"]!!.jsonPrimitive.boolean)
        d.pair("global_shift_px").forEach { assertEquals(0.0, it, 0.3) }
        assertEquals(18.5, d["dx"]!!.jsonPrimitive.double, 0.3)
        assertEquals(0.0, d["dy"]!!.jsonPrimitive.double, 0.3)
        assertTrue(d["shift_confidence"]!!.jsonPrimitive.double >= Shift.MIN_CONTENT)
        assertFalse("dx_mm" in d)
        assertTrue(r.image!!.jpeg.isNotEmpty())
        assertNoLongDecimals(d)
    }

    @Test fun diffAgainstALargerBaselineReportsBaselinePixels() = runBlocking {
        val view = AtomicReference(objectScene(200.0))
        val (c, look) = cameraOn(view::get, still = objectScene(200.0, scale = 2.0))
        c.capability("baseline").call(args("action" to "save", "name" to "holder", "force" to true), ctx)
        view.set(objectScene(212.0))
        delay(100)
        look()
        val r = c.capability("diff").call(args("a" to "holder", "roi" to "0.29,0.4,0.24,0.15", "mm_per_px" to 0.05, "image" to false), ctx)
        val d = r.json
        assertEquals(null, r.image)
        assertFalse("image_id" in d)
        assertTrue(d["changed"]!!.jsonPrimitive.boolean)
        assertEquals(24.0, d["dx"]!!.jsonPrimitive.double, 0.6, "12 px in the 640 px frame is 24 px in the 1280 px baseline")
        assertEquals(0.0, d["dy"]!!.jsonPrimitive.double, 0.6)
        assertEquals(1.2, d["dx_mm"]!!.jsonPrimitive.double, 0.03)
        assertEquals("invalid_param", assertFailsWith<LookoutError> {
            c.capability("diff").call(args("a" to "holder", "mm_per_px" to 0), ctx)
        }.code)
    }

    @Test fun sharpnessInLookAndCrop() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val sharp = Fixtures.checkerboard(640, 480)
        val s = PushSource(still = sharp, clock = clock)
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }
        now += 10
        s.push(sharp)
        val look = c.capability("look").call(args("fresh" to false), ctx).json
        val expected = round3(Sharpness.score(AwtImageOps, sharp))
        assertEquals(expected, look["sharpness"]!!.jsonPrimitive.double)
        assertFalse("moving" in look)
        assertFalse("burst" in look)
        assertFalse("blurred" in look)

        val id = look["frame_id"]!!.jsonPrimitive.long
        val crop = c.capability("crop").call(args("frame" to id.toString(), "roi" to "0.25,0.25,0.5,0.5"), ctx).json
        val expectedCrop = round3(Sharpness.score(AwtImageOps, sharp, NRect(0.25, 0.25, 0.5, 0.5)))
        assertEquals(expectedCrop, crop["sharpness"]!!.jsonPrimitive.double, expectedCrop * 0.02)
    }

    @Test fun sharpFastBurstReturnsSharpestAndReportsBurst() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val sharp = Fixtures.checkerboard(640, 480)
        val mild = Fixtures.boxBlur(sharp, 2)
        val heavy = Fixtures.boxBlur(sharp, 5)
        val s = PushSource(clock = clock)
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }
        s.push(heavy) // warm frame before request

        val shot = async { c.capability("look").call(args("sharp" to true), ctx) }
        val sequence = listOf(heavy, mild, sharp, mild, heavy)
        var sharpFrameId = -1L
        for ((i, img) in sequence.withIndex()) {
            delay(30)
            now += 100
            s.push(img)
            if (i == 2) sharpFrameId = c.store.latest.value!!.id
        }
        val r = shot.await().json
        assertEquals(Tools.SHARP_FAST_FRAMES, r["burst"]!!.jsonPrimitive.long.toInt())
        assertEquals(sharpFrameId, r["frame_id"]!!.jsonPrimitive.long)
        assertEquals(round3(Sharpness.score(AwtImageOps, sharp)), r["sharpness"]!!.jsonPrimitive.double)

        // Time cap stops the burst after SHARP_FAST_MS and burst_ms shows up in slow timing.
        now += 1_000
        val slow = async { c.capability("look").call(args("sharp" to true, "settle_ms" to 1_500), ctx) }
        delay(30)
        now += 1_600
        s.push(heavy)
        delay(30)
        now += 500
        s.push(mild)
        delay(30)
        now += 500
        s.push(sharp)
        val sr = slow.await().json
        assertTrue(sr["burst"]!!.jsonPrimitive.long.toInt() < Tools.SHARP_FAST_FRAMES, "time cap stopped burst early: ${sr["burst"]}")
        assertTrue(sr["timing"]!!.jsonObject["burst_ms"]!!.jsonPrimitive.long >= Tools.SHARP_FAST_MS)
    }

    @Test fun sharpFullBurstReturnsSharpestAndReportsTiming() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val sharp = Fixtures.checkerboard(640, 480)
        val mild = Fixtures.boxBlur(sharp, 2)
        val heavy = Fixtures.boxBlur(sharp, 5)
        val stills = listOf(heavy, sharp, mild)
        var idx = 0
        val s = PushSource(
            clock = clock,
            onStill = { now += 800 },
            nextStill = { stills[idx++ % stills.size] },
        )
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }
        s.push(sharp)

        val r = c.capability("look").call(args("detail" to "full", "sharp" to true), ctx)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertEquals(Tools.SHARP_STILLS, r.json["burst"]!!.jsonPrimitive.long.toInt())
        assertEquals(Tools.SHARP_STILLS, idx)
        assertEquals(round3(Sharpness.score(AwtImageOps, sharp)), r.json["sharpness"]!!.jsonPrimitive.double)
        assertTrue(r.json["timing"]!!.jsonObject["still_ms"]!!.jsonPrimitive.long >= 2_400L)

        // detail=full with sharp=true is unavailable while a recording runs.
        val recDir = java.nio.file.Files.createTempDirectory("lookout-sharp-rec").toFile()
        try {
            val recSource = CroppedReplaySource(listOf(sharp), clock = clock)
            val rc = Fixtures.core(recSource, clock = clock, recordingsDir = recDir).also { it.start(scope) }
            rc.capability("record").call(args("action" to "start", "duration_ms" to 5_000), ctx)
            assertEquals(
                "recording_active",
                assertFailsWith<LookoutError> { rc.capability("look").call(args("detail" to "full", "sharp" to true), ctx) }.code,
            )
            rc.capability("record").call(args("action" to "stop"), ctx)
            rc.stop()
        } finally {
            recDir.deleteRecursively()
        }
    }

    @Test fun movingAppearsWhenFramesChangeAndAbsentWhenStill() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val s = PushSource(clock = clock)
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }
        s.push(Fixtures.scene())
        now += 100
        s.push(Fixtures.scene())

        val still = c.capability("look").call(args("fresh" to false), ctx).json
        assertFalse("moving" in still)
        assertFalse("hint" in still)

        now += 100
        s.push(Fixtures.scene(handX = 0.4))
        val moving = c.capability("look").call(args("fresh" to false), ctx).json
        assertTrue(moving["moving"]!!.jsonPrimitive.boolean)
        val hint = moving["hint"]!!.jsonPrimitive.content
        assertTrue("settle_ms" in hint && "sharp=true" in hint, hint)

        now += Tools.MOTION_WINDOW_MS + 100
        s.push(Fixtures.scene(handX = 0.4))
        val calmAgain = c.capability("look").call(args("fresh" to false), ctx).json
        assertFalse("moving" in calmAgain)
        assertFalse("hint" in calmAgain)
    }

    @Test fun saveAsRefusesBlurredFrameUnlessForced() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val sharp = Fixtures.checkerboard(640, 480)
        val blurry = Fixtures.boxBlur(sharp, 4)
        val stills = listOf(blurry, sharp, blurry)
        var idx = 0
        val s = PushSource(clock = clock, nextStill = { stills[idx++ % stills.size] })
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }

        // Motion during capture refuses save_as without force, still returns image.
        s.push(Fixtures.scene())
        now += 50
        s.push(Fixtures.scene(handX = 0.4))
        val refusedMotion = c.capability("look").call(args("save_as" to "ref"), ctx)
        assertTrue(refusedMotion.image!!.jpeg.isNotEmpty())
        assertEquals(false, refusedMotion.json["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedMotion.json["blurred"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedMotion.json["moving"]!!.jsonPrimitive.boolean)
        assertEquals(Tools.SHARP_STILLS, refusedMotion.json["burst"]!!.jsonPrimitive.long.toInt())
        val mHint = refusedMotion.json["hint"]!!.jsonPrimitive.content
        assertTrue("motion" in mHint && "force=true" in mHint, mHint)
        assertEquals(null, c.baselines.get("ref"))

        // force=true saves despite motion.
        val forcedMotion = c.capability("look").call(args("save_as" to "ref", "force" to true), ctx)
        assertTrue(forcedMotion.image!!.jpeg.isNotEmpty())
        assertFalse("saved" in forcedMotion.json)
        assertFalse("blurred" in forcedMotion.json)
        assertTrue(c.baselines.get("ref") != null)

        // AF not converged refuses save_as without force.
        now += Tools.MOTION_WINDOW_MS + 100
        s.push(Fixtures.scene(handX = 0.4), meta = CameraMeta(afConverged = false))
        val refusedAf = c.capability("look").call(args("save_as" to "af-ref"), ctx)
        assertTrue(refusedAf.image!!.jpeg.isNotEmpty())
        assertEquals(false, refusedAf.json["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedAf.json["blurred"]!!.jsonPrimitive.boolean)
        assertFalse("moving" in refusedAf.json)
        val afHint = refusedAf.json["hint"]!!.jsonPrimitive.content
        assertTrue("autofocus" in afHint && "force=true" in afHint, afHint)
        assertEquals(null, c.baselines.get("af-ref"))

        // force=true saves despite unconverged AF.
        val forcedAf = c.capability("look").call(args("save_as" to "af-ref", "force" to true), ctx)
        assertTrue(c.baselines.get("af-ref") != null)
        assertFalse("blurred" in forcedAf.json)

        // Steady + converged AF saves the sharpest still from the burst.
        now += 100
        s.push(Fixtures.scene(handX = 0.4), meta = CameraMeta(afConverged = true))
        val ok = c.capability("look").call(args("save_as" to "clean-ref"), ctx)
        assertTrue(c.baselines.get("clean-ref") != null)
        assertFalse("saved" in ok.json)
        assertFalse("blurred" in ok.json)
        assertEquals(Tools.SHARP_STILLS, ok.json["burst"]!!.jsonPrimitive.long.toInt())
        assertEquals(round3(Sharpness.score(AwtImageOps, sharp)), ok.json["sharpness"]!!.jsonPrimitive.double)
    }

    @Test fun baselineSaveRefusesBlurredFrameUnlessForced() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val sharp = Fixtures.checkerboard(640, 480)
        val blurry = Fixtures.boxBlur(sharp, 4)
        val stills = listOf(blurry, sharp, blurry)
        var idx = 0
        val s = PushSource(clock = clock, nextStill = { stills[idx++ % stills.size] })
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }

        // Motion refuses baseline save without force.
        s.push(Fixtures.scene())
        now += 50
        s.push(Fixtures.scene(handX = 0.4))
        val refusedMotion = c.capability("baseline").call(args("action" to "save", "name" to "ref"), ctx)
        assertTrue(refusedMotion.image!!.jpeg.isNotEmpty())
        assertEquals(false, refusedMotion.json["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedMotion.json["blurred"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedMotion.json["moving"]!!.jsonPrimitive.boolean)
        assertEquals(Tools.SHARP_STILLS, refusedMotion.json["burst"]!!.jsonPrimitive.long.toInt())
        val mHint = refusedMotion.json["hint"]!!.jsonPrimitive.content
        assertTrue("motion" in mHint && "force=true" in mHint, mHint)
        assertEquals(null, c.baselines.get("ref"))

        // force=true saves it.
        val forced = c.capability("baseline").call(args("action" to "save", "name" to "ref", "force" to true), ctx)
        assertTrue(c.baselines.get("ref") != null)
        assertTrue("baseline" in forced.json)
        assertFalse("blurred" in forced.json)

        // AF not converged refuses without force and saves with force.
        now += Tools.MOTION_WINDOW_MS + 100
        s.push(Fixtures.scene(handX = 0.4), meta = CameraMeta(afConverged = false))
        val refusedAf = c.capability("baseline").call(args("action" to "save", "name" to "af-ref"), ctx)
        assertTrue(refusedAf.image!!.jpeg.isNotEmpty())
        assertEquals(false, refusedAf.json["saved"]!!.jsonPrimitive.boolean)
        assertEquals(true, refusedAf.json["blurred"]!!.jsonPrimitive.boolean)
        assertTrue("autofocus" in refusedAf.json["hint"]!!.jsonPrimitive.content)
        assertEquals(null, c.baselines.get("af-ref"))

        c.capability("baseline").call(args("action" to "save", "name" to "af-ref", "force" to true), ctx)
        assertTrue(c.baselines.get("af-ref") != null)
    }

    companion object {
        private val STATIC: Img = Fixtures.scene()
    }
}
