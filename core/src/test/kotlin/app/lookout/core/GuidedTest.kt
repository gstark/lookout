package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuidedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("lookout-guided").toFile()
    /** Moves the injected clock forward. */
    @Volatile private var skewMs = 0L
    private val clock = Clock { System.currentTimeMillis() + skewMs }
    private val source = ReplayFrameSource(List(4) { Fixtures.scene(noiseSeed = it + 1) }, fps = 10.0, clock = clock, ops = AwtImageOps)
    private val core = Fixtures.core(source, clock = clock, recordingsDir = dir).also { it.start(scope) }
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
                is kotlinx.serialization.json.JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

    private suspend fun prompt(): Prompt = withTimeout(5_000) { core.human.current.filterNotNull().first() }

    private suspend fun tap(choice: String = Tools.READY) = prompt().let { core.human.answer(it.id, choice) }

    private suspend fun fetch(id: String, timeoutMs: Long = 10_000, vararg extra: Pair<String, Any>) =
        call("wait_for", "condition" to "request", "request_id" to id, "timeout_ms" to timeoutMs, *extra)

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun readyWithinWaitMsReturnsTheImageAndHowItWasReleased() = runBlocking {
        val shot = async { call("look", "guide_text" to "Hold the board flat", "wait_ms" to 20_000) }
        val p = prompt()
        skewMs += 7_000
        core.human.answer(p.id, Tools.READY)
        val r = shot.await()
        assertTrue(r.image!!.jpeg.isNotEmpty())
        assertEquals("tap", r.json.str("released_by"))
        assertTrue(r.json["ready_after_ms"]!!.jsonPrimitive.long >= 7_000, r.json.toString())
        val event = core.events.since(0, setOf("guide")).single().data
        assertEquals("tap", event.str("released_by"))
        assertTrue(event["ready_after_ms"]!!.jsonPrimitive.long >= 7_000)
        assertEquals("look", event.str("tool"))
    }

    @Test fun theZoomOfAGuidedLookAppliesWhileThePromptShows() = runBlocking {
        val shot = async { call("look", "guide_text" to "Frame the label", "zoom" to 2.5, "wait_ms" to 20_000) }
        prompt()
        assertEquals(2.5, source.apply(CameraSettings()).zoom, "the preview shows the zoom before Ready")
        tap()
        shot.await()
        assertEquals(1.0, source.apply(CameraSettings()).zoom, "the zoom goes back after the shot")
    }

    @Test fun aCancelledGuidedLookPutsTheZoomBack() = runBlocking {
        val shot = async { runCatching { call("look", "guide_text" to "Frame the label", "zoom" to 2.5, "wait_ms" to 20_000) } }
        tap(Tools.CANCEL)
        assertEquals("declined", (shot.await().exceptionOrNull() as LookoutError).code)
        assertEquals(1.0, source.apply(CameraSettings()).zoom)
    }

    @Test fun aLateReadyComesThroughWaitForWithTheImage() = runBlocking {
        val waiting = call("look", "guide_text" to "Hold the board flat", "wait_ms" to 0)
        assertEquals(null, waiting.image)
        assertEquals("waiting", waiting.json.str("state"))
        val id = waiting.json.str("request_id")
        assertTrue("expires_at" in waiting.json && id in waiting.json.str("next"))
        assertTrue("guided_shot" in core.sleepBlockers(), "a waiting request keeps Lookout awake")

        val early = fetch(id, timeoutMs = 200).json
        assertFalse(early["matched"]!!.jsonPrimitive.boolean)
        assertEquals("waiting", early.str("state"))
        assertEquals(id, early.str("request_id"))

        tap()
        val done = fetch(id)
        assertTrue(done.image!!.jpeg.isNotEmpty(), "the look image comes with the result")
        assertEquals("tap", done.json.str("released_by"))
        assertTrue("frame_id" in done.json)
        val again = fetch(id)
        assertEquals(done.json.str("frame_id"), again.json.str("frame_id"), "a result can be fetched again")
        assertTrue(again.image!!.jpeg.contentEquals(done.image!!.jpeg))
        assertFalse("guided_shot" in core.sleepBlockers())
    }

    @Test fun inlineFalseOfTheGuidedCallHolds() = runBlocking {
        val id = call("look", "guide_text" to "Hold it", "wait_ms" to 0, "inline" to false).json.str("request_id")
        tap()
        val done = fetch(id)
        assertEquals(null, done.image, "inline=false of the look call holds")
        val imageId = done.json.str("image_id")
        assertNotNull(core.composites.get(imageId))
        assertEquals(framePath(imageId), done.json.str("image_url"))
        assertEquals(imageId, fetch(id).json.str("image_id"), "the second fetch links the same image")
    }

    @Test fun aGuidedRecordingStartsOnTheTapWithNoCallWaiting() = runBlocking {
        val waiting = call("record", "action" to "start", "guide_text" to "Point at the LED", "duration_ms" to 1_000, "wait_ms" to 0).json
        val id = waiting.str("request_id")
        assertEquals(null, core.recordings.current.value)
        tap()
        val rec = withTimeout(5_000) { core.recordings.current.filterNotNull().first() }
        val done = fetch(id).json
        assertEquals(rec.id, done["recording"]!!.jsonObject.str("recording_id"))
        assertEquals("tap", done.str("released_by"))
        assertTrue("next" in done)
    }

    @Test fun cancellingTheCallKeepsTheRequestAndASecondGuideIsRefused() = runBlocking {
        val first = async { call("look", "guide_text" to "Hold the board flat", "wait_ms" to 30_000) }
        val p = prompt()
        first.cancelAndJoin()
        assertEquals(p.id, core.human.current.value?.id, "the prompt stays after the call is cancelled")
        val busy = assertFailsWith<LookoutError> { call("record", "action" to "start", "guide_text" to "Another shot") }
        assertEquals("guide_busy", busy.code)
        val id = Regex("g\\d+").find(busy.message!!)!!.value
        assertTrue("request_id=$id" in busy.message!!)
        core.human.answer(p.id, Tools.READY)
        val done = fetch(id)
        assertTrue(done.image!!.jpeg.isNotEmpty())
        // The prompt is gone, so a new guide may show.
        val next = call("look", "guide_text" to "Next", "wait_ms" to 0).json
        assertEquals("waiting", next.str("state"))
    }

    @Test fun cancelAndReadyTimeoutComeBackAsStates() = runBlocking {
        val cancelled = call("look", "guide_text" to "Hold it", "wait_ms" to 0).json.str("request_id")
        tap(Tools.CANCEL)
        val declined = fetch(cancelled).json
        assertEquals("declined", declined.str("state"))
        assertTrue(declined["matched"]!!.jsonPrimitive.boolean)

        val late = call("record", "action" to "start", "guide_text" to "Anyone?", "ready_timeout_ms" to 1_000, "wait_ms" to 0).json.str("request_id")
        assertEquals("timeout", fetch(late).json.str("state"))
        assertEquals(null, core.recordings.current.value, "no recording without Ready")
        assertEquals(listOf("cancel", "timeout"), core.events.since(0, setOf("guide")).map { it.data.str("released_by") })
    }

    @Test fun anUnknownRequestFails() = runBlocking {
        assertEquals("request_not_found", assertFailsWith<LookoutError> { fetch("g999") }.code)
        assertEquals("missing_param", assertFailsWith<LookoutError> { call("wait_for", "condition" to "request") }.code)
    }

    @Test fun guideImageTakesAFrameAnImageIdOrABaseline() = runBlocking {
        val look = call("look", "inline" to false).json
        call("look", "save_as" to "bench", "force" to true)
        for (ref in listOf(look.str("frame_id"), look.str("image_id"), "bench")) {
            val shot = async { call("look", "guide_image" to ref) }
            val p = prompt()
            assertTrue(AwtImageOps.decode(p.image!!).width > 0, ref)
            core.human.answer(p.id, Tools.READY)
            shot.await()
        }
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "guide_image" to "no-such-frame") }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "guide_text" to "x", "guide_marks" to "[{\"at\":\"C3\"}]") }.code)
    }

    @Test fun guideMarksAreDrawnOnTheExample() = runBlocking {
        val frame = call("look").json.str("frame_id")
        suspend fun shown(vararg extra: Pair<String, Any>): Img {
            val shot = async { call("look", "guide_image" to frame, *extra) }
            val p = prompt()
            core.human.answer(p.id, Tools.READY)
            shot.await()
            return AwtImageOps.decode(p.image!!)
        }
        val plain = shown()
        val marked = shown("guide_marks" to JsonPrimitive("""[{"shape":"box","at":"C2:E4","color":"yellow"}]"""))
        assertEquals(plain.width, marked.width)
        val a = AwtImageOps.argb(plain, plain.width, plain.height)
        val b = AwtImageOps.argb(marked, marked.width, marked.height)
        val changed = a.indices.count { a[it] != b[it] }
        assertTrue(changed > a.size / 200, "the box changes the example ($changed px)")
    }

    @Test fun theDefaultWaitsFitAOneMinuteClient() {
        fun param(tool: String, name: String) = core.capability(tool).params.single { it.name == name }
        assertTrue("Default 50000" in param("wait_for", "timeout_ms").description)
        assertTrue("Default 50000" in param("ask", "timeout_ms").description)
        assertTrue("default 50000" in param("point", "timeout_ms").description)
        assertTrue("Default 45000, max 50000" in param("look", "wait_ms").description)
        assertTrue("wait_ms" in core.capability("record").params.map { it.name })
        assertTrue("at or under 50000" in Tools.SERVER_INSTRUCTIONS)
        assertTrue("request" in param("wait_for", "condition").enum!!)
    }

    @Test fun aRequestWhoseScopeEndedBeforeItRanStillEnds() = runBlocking<Unit> {
        val g = GuidedRequests(clock)
        g.start(CoroutineScope(SupervisorJob()).also { it.cancel() })
        val req = g.launch("look", clock.now() + 60_000) { error("the body must not run") }
        val outcome = assertNotNull(req.await(1_000), "the request ended")
        assertEquals("guided_failed", (outcome.exceptionOrNull() as LookoutError).code)
        assertFalse(g.active)
        g.launch("look", clock.now() + 60_000) { error("the body must not run") }
    }

    @Test fun guideFitShowsTheBoxAndAddsALine() = runBlocking {
        val shot = async { call("look", "guide_text" to "Hold the driver chip side up", "guide_fit" to "c2:f5", "wait_ms" to 20_000) }
        val p = prompt()
        assertEquals(NRect(2.0 / 8, 1.0 / 6, 4.0 / 8, 4.0 / 6), p.fit)
        assertEquals("Hold the driver chip side up\n${Tools.FIT_LINE}", p.text)
        core.human.answer(p.id, Tools.READY)
        assertEquals("C2:F5", shot.await().json.str("guide_fit"))
    }

    @Test fun guideFitAloneIsAGuide() = runBlocking {
        val shot = async { call("look", "guide_fit" to "B2:G5", "wait_ms" to 20_000) }
        val p = prompt()
        assertTrue(p.text.startsWith(Tools.FIT_LINE), p.text)
        core.human.answer(p.id, Tools.READY)
        assertEquals("B2:G5", shot.await().json.str("guide_fit"))
    }

    @Test fun guideFitMustCoverTwoByTwoCells() = runBlocking {
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "guide_fit" to "C2:C5") }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { call("look", "guide_fit" to "C2") }.code)
        assertEquals("invalid_cell", assertFailsWith<LookoutError> { call("look", "guide_fit" to "C2:Z9") }.code)
        assertEquals(null, core.human.current.value, "a bad box shows nothing on the phone")
    }
}
