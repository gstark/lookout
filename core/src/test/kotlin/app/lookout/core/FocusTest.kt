package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ctx = CallContext("test", "127.0.0.1")

    @AfterTest fun tearDown() {
        scope.cancel()
    }

    @Test fun focusRoiSendsRegionToSourceOnLookAndReverts() = runBlocking<Unit> {
        val src = PushSource()
        val core = Fixtures.core(src).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        core.session.use {}
        val scene = Fixtures.scene(640, 480)
        val feeder = scope.launch {
            while (true) {
                src.push(scene)
                delay(20)
            }
        }
        try {
            delay(40)

            // Inline ROI with string focus="roi".
            src.appliedHistory.clear()
            core.capability("look").call(
                Args(buildJsonObject {
                    put("roi", "0.2,0.3,0.4,0.2")
                    put("focus", "roi")
                }),
                ctx,
            )
            assertEquals(2, src.appliedHistory.size)
            val appliedFocus = src.appliedHistory[0].focus
            assertEquals("roi", appliedFocus?.mode)
            assertEquals(NRect(0.2, 0.3, 0.4, 0.2), appliedFocus?.region)
            assertEquals("auto", src.appliedHistory[1].focus?.mode, "withSettings reverts focus after look")

            // Named ROI with object focus={"mode":"roi"}.
            core.rois.put(Roi("part", NRect(0.1, 0.4, 0.3, 0.2)))
            src.appliedHistory.clear()
            core.capability("look").call(
                Args(buildJsonObject {
                    put("roi", "part")
                    putJsonObject("focus") { put("mode", "roi") }
                }),
                ctx,
            )
            assertEquals(NRect(0.1, 0.4, 0.3, 0.2), src.appliedHistory[0].focus?.region)
        } finally {
            feeder.cancel()
        }
    }

    @Test fun focusRoiFailsWithoutRoiAndOnCamera() = runBlocking<Unit> {
        val src = PushSource()
        val core = Fixtures.core(src).also { it.start(scope) }

        val lookErr = assertFailsWith<LookoutError> {
            core.capability("look").call(Args(buildJsonObject { put("focus", "roi") }), ctx)
        }
        assertEquals("invalid_param", lookErr.code)
        assertEquals(400, lookErr.status)

        val camErr = assertFailsWith<LookoutError> {
            core.capability("camera").call(Args(buildJsonObject { put("focus", "roi") }), ctx)
        }
        assertEquals("invalid_param", camErr.code)
        assertEquals(400, camErr.status)
    }

    @Test fun focusMmMinFocusMmAndFocusAtLimitWarning() = runBlocking<Unit> {
        assertEquals(500, dioptersToMm(2.0))
        assertEquals(100, dioptersToMm(10.0))
        assertNull(dioptersToMm(0.0))
        assertNull(dioptersToMm(null))
        assertTrue(focusAtLimit(9.8, 10.0))
        assertTrue(focusAtLimit(10.5, 10.0))
        assertFalse(focusAtLimit(9.7, 10.0))
        assertFalse(focusAtLimit(10.0, 0.0))

        val lens = LensInfo(
            id = "0",
            facing = "back",
            manualFocus = true,
            minFocusDistance = 10.0,
            focalLengthMm = 4.25,
            fovHDeg = 68.5,
            fovVDeg = 54.2,
        )
        val src = PushSource(lensList = listOf(lens))
        val core = Fixtures.core(src).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        core.session.use {}

        // describe includes min_focus_mm, focal_length_mm, fov_h_deg, fov_v_deg.
        val descLens = core.capability("describe").call(Args(buildJsonObject {}), ctx).json["lenses"]!!.jsonArray[0].jsonObject
        assertEquals(100, descLens["min_focus_mm"]!!.jsonPrimitive.int)
        assertEquals(4.25, descLens["focal_length_mm"]!!.jsonPrimitive.content.toDouble(), 1e-3)
        assertEquals(68.5, descLens["fov_h_deg"]!!.jsonPrimitive.content.toDouble(), 1e-3)
        assertEquals(54.2, descLens["fov_v_deg"]!!.jsonPrimitive.content.toDouble(), 1e-3)

        // Infinity (0 diopters): focus_mm omitted, no focus_at_limit.
        src.push(Fixtures.scene(640, 480), meta = CameraMeta(lens = "0", focusDistance = 0.0))
        delay(50)
        val inf = core.capability("look").call(Args(buildJsonObject { put("fresh", false); put("verbose", true) }), ctx).json
        assertNull(inf["focus_mm"])
        assertNull(inf["focus_distance"])
        assertNull(inf["focus_at_limit"])

        // Well away from close limit (5.0 diopters = 200 mm): focus_mm=200, no warning.
        src.push(Fixtures.scene(640, 480), meta = CameraMeta(lens = "0", focusDistance = 5.0))
        delay(50)
        val mid = core.capability("look").call(Args(buildJsonObject { put("fresh", false); put("verbose", true) }), ctx).json
        assertEquals(200, mid["focus_mm"]!!.jsonPrimitive.int)
        assertNull(mid["focus_at_limit"])
        assertNull(mid["hint"])

        // Within 2% of close limit (9.85 diopters vs 10.0): focus_at_limit=true and hint mentioning 100 mm.
        src.push(Fixtures.scene(640, 480), meta = CameraMeta(lens = "0", focusDistance = 9.85))
        delay(50)
        val near = core.capability("look").call(Args(buildJsonObject { put("fresh", false); put("verbose", true) }), ctx).json
        assertEquals(102, near["focus_mm"]!!.jsonPrimitive.int)
        assertTrue(near["focus_at_limit"]!!.jsonPrimitive.boolean)
        val hint = near["hint"]!!.jsonPrimitive.content
        assertTrue("100 mm" in hint, "expected close limit mm in hint: $hint")
    }
}
