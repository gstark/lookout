package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TicksTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ctx = CallContext("test", "127.0.0.1")

    @AfterTest fun tearDown() {
        scope.cancel()
    }

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                else -> error("unexpected arg $v")
            }
        }
    })

    @Test fun fullFrameAxisHas005StepAnd01Labels() {
        val axis = Ticks.axis(0.0, 1.0)
        assertEquals(0.05, axis.step, 1e-9)
        assertEquals(21, axis.ticks.size)
        val labeled = axis.ticks.filter { it.label != null }
        assertEquals(listOf("0.0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"), labeled.map { it.label })
        assertTrue(axis.ticks.all { it.value in 0.0..1.0 })
        assertEquals(0.0, axis.ticks.first().value, 1e-9)
        assertEquals(1.0, axis.ticks.last().value, 1e-9)
    }

    @Test fun croppedAxesUseAdaptiveNiceStepAndFullFrameLabels() {
        // Medium crop: span = 0.125 -> step = 0.01 (13 ticks), labels every 0.02 (0.42, 0.44, ..., 0.54).
        val medium = Ticks.axis(0.42, 0.545)
        assertEquals(0.01, medium.step, 1e-9)
        assertTrue(medium.ticks.size in 8..20, "tick count ${medium.ticks.size}")
        assertTrue(medium.ticks.all { it.value in 0.42 - 1e-9..0.545 + 1e-9 })
        val mediumLabels = medium.ticks.mapNotNull { it.label }
        assertTrue(mediumLabels.containsAll(listOf("0.42", "0.44", "0.50", "0.54")), "full-frame labels: $mediumLabels")

        // Thin crop: span = 0.024 -> step = 0.002 (13 ticks), labels every 0.004 (0.480, 0.484, ..., 0.504).
        val thin = Ticks.axis(0.48, 0.504)
        assertEquals(0.002, thin.step, 1e-9)
        assertTrue(thin.ticks.size in 8..20, "tick count ${thin.ticks.size}")
        val thinLabels = thin.ticks.mapNotNull { it.label }
        assertTrue(thinLabels.containsAll(listOf("0.480", "0.488", "0.500", "0.504")), "full-frame labels: $thinLabels")

        // Slightly wider thin crop: span = 0.032 -> step = 0.002, majorStep = 0.01 (labelEvery=5).
        val thin5 = Ticks.axis(0.48, 0.512)
        assertEquals(0.002, thin5.step, 1e-9)
        assertEquals(0.01, thin5.majorStep, 1e-9)
        assertEquals(listOf("0.48", "0.49", "0.50", "0.51"), thin5.ticks.mapNotNull { it.label })

        // Very narrow crop: span = 0.005 -> step = 0.0005.
        val narrow = Ticks.axis(0.123, 0.128)
        assertEquals(0.0005, narrow.step, 1e-9)
        assertTrue(narrow.ticks.size in 8..21, "tick count ${narrow.ticks.size}")
        val narrowLabels = narrow.ticks.mapNotNull { it.label }
        assertTrue("0.124" in narrowLabels && "0.127" in narrowLabels, "narrow labels: $narrowLabels")
        // Verify no floating-point garbage like 0.12400000000000001.
        assertTrue(narrowLabels.all { it.length <= 6 }, "clean labels: $narrowLabels")
    }

    @Test fun gridOverlayParsesTicksAndCells() {
        assertEquals(GridOverlay.Ticks, GridOverlay.parse("ticks"))
        assertEquals(GridOverlay.Ticks, GridOverlay.parse("TICKS"))
        assertEquals(GridOverlay.Cells(GridSpec.DEFAULT), GridOverlay.parse("true"))
        assertEquals(GridOverlay.Cells(GridSpec(6, 4)), GridOverlay.parse("6x4"))
        assertEquals(null, GridOverlay.parse("false"))
        assertEquals(null, GridOverlay.parse(null))
        assertFailsWith<LookoutError> { GridOverlay.parse("bogus") }
    }

    @Test fun lookAndCropDrawTicksAndPointRejectsTicks() = runBlocking<Unit> {
        val src = PushSource(still = Fixtures.scene(1200, 900))
        val core = Fixtures.core(src).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

        core.session.use {}
        src.push(Fixtures.scene(640, 480))
        delay(50)

        val plain = call("look", "fresh" to false, "width" to 320)
        val ticked = call("look", "fresh" to false, "width" to 320, "grid" to "ticks")
        val tickedImg = assertNotNull(ticked.image)
        assertEquals(320, AwtImageOps.decode(tickedImg.jpeg).width)
        assertFalse(plain.image!!.jpeg.contentEquals(tickedImg.jpeg), "ticks should alter the rendered image")

        // Cropped look and crop tool with grid=ticks both render cleanly.
        val croppedLook = call("look", "fresh" to false, "roi" to "0.42,0.48,0.125,0.024", "width" to 320, "grid" to "ticks")
        val croppedLookImg = assertNotNull(croppedLook.image)
        assertTrue(AwtImageOps.decode(croppedLookImg.jpeg).width > 0)

        val fid = plain.json["frame_id"]!!.jsonPrimitive.long.toString()
        val croppedPlain = call("crop", "frame" to fid, "roi" to "0.2,0.2,0.4,0.4", "width" to 240)
        val croppedTicked = call("crop", "frame" to fid, "roi" to "0.2,0.2,0.4,0.4", "width" to 240, "grid" to "ticks")
        assertFalse(croppedPlain.image!!.jpeg.contentEquals(croppedTicked.image!!.jpeg))

        // point still uses cell grids only, not ticks.
        val err = assertFailsWith<LookoutError> {
            core.capability("point").call(
                Args(buildJsonObject {
                    put("grid", "ticks")
                    put("marks", """[{"shape":"arrow","at":"C3"}]""")
                }),
                ctx,
            )
        }
        assertEquals("invalid_grid", err.code)
    }
}
