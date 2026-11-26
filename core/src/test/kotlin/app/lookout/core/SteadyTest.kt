package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteadyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lens = LensInfo("back", "back", fovHDeg = 60.0, stillSize = "4000x3000")
    private val source = PushSource(lensList = listOf(lens)).apply { exposureNs = 33_000_000L }
    private val gate = SteadyGate(source, Clock.SYSTEM) { null }

    @AfterTest fun tearDown() = scope.cancel()

    @Test fun aSteadyPhonePassesAfterTheHoldTime() = runBlocking {
        source.rate = 0.001
        val r = gate.await(lens)
        assertEquals(true, r.steady)
        assertTrue(r.waitedMs in SteadyGate.HOLD_MS until 1_000L, "waited ${r.waitedMs}")
    }

    @Test fun aShakingPhoneTimesOut() = runBlocking {
        source.rate = 0.5
        val r = gate.await(lens, maxMs = 300)
        assertEquals(false, r.steady)
        assertTrue(r.blurPx!! > SteadyGate.GATE_PX)
    }

    @Test fun thePhoneMustStaySteadyForTheWholeHold() = runBlocking {
        source.rate = 0.5
        val shaky = launch {
            // Steady for 50 ms at a time, shorter than the hold, then steady for good.
            repeat(4) { source.rate = 0.001; delay(50); source.rate = 0.5; delay(30) }
            source.rate = 0.001
        }
        val r = gate.await(lens)
        shaky.join()
        assertEquals(true, r.steady)
        assertTrue(r.waitedMs >= 4 * 80 + SteadyGate.HOLD_MS - 20, "waited ${r.waitedMs}")
    }

    @Test fun noGyroMeansNoWait() = runBlocking {
        source.rate = null
        val r = gate.await(lens)
        assertNull(r.steady)
        assertTrue(r.waitedMs < 50)
    }

    @Test fun anExtraConditionMustHoldToo() = runBlocking {
        source.rate = 0.001
        var green = false
        val r = async { gate.await(lens) { green } }
        delay(200)
        assertFalse(r.isCompleted)
        green = true
        assertEquals(true, r.await().steady)
    }

    @Test fun aGuidedLookWaitsAfterTheTapAndReportsSteady() = runBlocking {
        val core = Fixtures.core(source).also { it.start(scope) }
        core.session.open.first { it }
        source.rate = 0.001
        val pusher = launch { while (true) { source.push(Fixtures.scene()); delay(40) } }
        val shot = async {
            core.capability("look").call(Args(buildJsonObject { put("guide_text", "Hold it"); put("wait_ms", 20_000) }), CallContext("test"))
        }
        val p = withTimeout(5_000) { core.human.current.filterNotNull().first() }
        val tappedAt = System.currentTimeMillis()
        core.human.answer(p.id, Tools.READY)
        val r = shot.await().json
        assertTrue(System.currentTimeMillis() - tappedAt >= Tools.AFTER_TAP_MS + SteadyGate.HOLD_MS)
        assertTrue(r["steady"]!!.jsonPrimitive.boolean)
        assertTrue(r["steady_wait_ms"]!!.jsonPrimitive.long >= SteadyGate.HOLD_MS)
        pusher.cancel()
    }

    @Test fun aGuidedLookCapsExposureWhileTheGuideShowsAndUsesTheTorchInTheDark() = runBlocking {
        val flashLens = LensInfo("back", "back", fovHDeg = 60.0, hasFlash = true)
        val src = PushSource(lensList = listOf(flashLens))
        val core = Fixtures.core(src).also { it.start(scope) }
        core.session.open.first { it }
        val dark = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val pusher = launch { while (true) { src.push(app.lookout.awt.AwtImg(dark)); delay(40) } }
        val shot = async {
            core.capability("look").call(Args(buildJsonObject { put("guide_text", "Hold it"); put("wait_ms", 20_000); put("force", true) }), CallContext("test"))
        }
        val p = withTimeout(5_000) { core.human.current.filterNotNull().first() }
        assertEquals(true, src.applied.handheld, "the cap applies while the guide shows")
        core.human.answer(p.id, Tools.READY)
        val r = shot.await().json
        assertTrue(r["torch"]!!.jsonPrimitive.boolean)
        assertTrue(src.appliedHistory.any { it.torch == true })
        assertEquals(false, src.applied.torch, "the torch goes off after the shot")
        assertEquals(false, src.applied.handheld, "the cap goes off after the shot")
        pusher.cancel()
    }

    /** A guided look on [src] with a level, already showing its first prompt. */
    private suspend fun levelLook(src: PushSource): Triple<LookoutCore, kotlinx.coroutines.Deferred<CapResult>, Prompt> {
        val core = Fixtures.core(src).also { it.start(scope) }
        core.session.open.first { it }
        scope.launch { while (true) { src.push(Fixtures.scene()); delay(40) } }
        val shot = scope.async {
            core.capability("look").call(Args(buildJsonObject { put("guide_text", "Hold it"); put("wait_ms", 20_000) }), CallContext("test"))
        }
        val p = withTimeout(5_000) { core.human.current.filterNotNull().first() }
        return Triple(core, shot, p)
    }

    private fun tilted(deg: Double) = LevelReading(flat = true, a = deg, b = 0.0)

    @Test fun readyWhenLevelWaitsForGreenThenShootsAndBuzzes() = runBlocking {
        source.rate = 0.001
        source.level = tilted(4.0)
        val (core, shot, first) = levelLook(source)
        assertEquals(listOf(Tools.READY, Tools.READY_LEVEL, Tools.CANCEL), first.buttons)
        val buzz = async { withTimeout(10_000) { core.human.shots.first() } }
        core.human.answer(first.id, Tools.READY_LEVEL)
        val waiting = withTimeout(5_000) { core.human.current.filterNotNull().first { it.id != first.id } }
        assertTrue(waiting.showLevel)
        assertEquals(listOf(Tools.SNAP_NOW, Tools.CANCEL), waiting.buttons)
        delay(300)
        assertFalse(shot.isCompleted, "no shot while the level is off")
        source.level = tilted(0.4)
        val r = shot.await().json
        assertEquals("level", r["released_by"]!!.jsonPrimitive.content)
        assertEquals(0.4, r["level_deg"]!!.jsonPrimitive.content.toDouble())
        buzz.await()
        assertNull(core.human.current.value, "the level prompt is gone")
    }

    @Test fun snapNowTakesTheShotWithoutGreen() = runBlocking {
        source.rate = 0.001
        source.level = tilted(4.0)
        val (core, shot, first) = levelLook(source)
        core.human.answer(first.id, Tools.READY_LEVEL)
        val waiting = withTimeout(5_000) { core.human.current.filterNotNull().first { it.id != first.id } }
        core.human.answer(waiting.id, Tools.SNAP_NOW)
        val r = shot.await().json
        assertEquals("snap_now", r["released_by"]!!.jsonPrimitive.content)
        assertEquals(4.0, r["level_deg"]!!.jsonPrimitive.content.toDouble())
    }

    @Test fun noLevelButtonWithoutAGravitySensor() = runBlocking {
        source.rate = 0.001
        source.level = null
        val (core, shot, first) = levelLook(source)
        assertEquals(listOf(Tools.READY, Tools.CANCEL), first.buttons)
        core.human.answer(first.id, Tools.CANCEL)
        assertEquals("declined", runCatching { shot.await() }.exceptionOrNull().let { (it as LookoutError).code })
    }
}
