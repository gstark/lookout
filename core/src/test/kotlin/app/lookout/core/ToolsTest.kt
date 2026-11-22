package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val core = Fixtures.core(source)
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
                is JsonObject -> put(k, v)
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

    @Test fun captureSaveAsMakesBaseline() = runBlocking {
        core.store.latest.filterNotNull().first()
        val r = call("capture", "save_as" to "board-off", "torch" to true)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        assertTrue(core.baselines.get("board-off") != null)
        assertEquals(false, source.applied.torch, "torch reverts after the shot")
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
        for (k in listOf("lenses", "camera", "rois", "baselines", "history", "device", "api_version")) assertTrue(k in d, "describe has $k")
        assertEquals(13, core.tools.mcpTools.size)
    }

    companion object {
        private val STATIC: Img = Fixtures.scene()
    }
}
