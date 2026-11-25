package app.lookout.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTest {
    private var now = 1_000_000L
    private val clock = Clock { now }

    @Test fun idleTimerTurnsOffTheLightAndClosesAWarmCamera() = runBlocking {
        val source = PushSource()
        val core = Fixtures.core(source, clock) // charging, so the default mode is warm
        core.applyCamera(CameraSettings(torch = true))
        core.session.reconcile()
        assertTrue(source.isOpen, "warm mode opens the camera")

        now += 14 * 60_000L
        core.sleepIfDue()
        assertFalse(core.sleep.asleep.value, "not idle long enough")

        now += 60_000L
        core.sleepIfDue()
        assertTrue(core.sleep.asleep.value)
        assertEquals(false, source.applied.torch, "the light goes off")
        now += CameraSession.COLD_IDLE_MS + 1
        core.session.reconcile()
        assertFalse(source.isOpen, "asleep, warm mode does not keep the camera open")

        core.agentActive()
        assertFalse(core.sleep.asleep.value)
        core.session.reconcile()
        assertTrue(source.isOpen, "awake again, warm mode opens the camera")
        assertEquals(false, source.applied.torch, "the light stays off after a wake")
    }

    @Test fun doneWaitsForAPromptAndATouch() = runBlocking {
        val core = Fixtures.core(PushSource(), clock)
        core.phoneActive()
        core.release()
        assertFalse(core.sleep.asleep.value, "a touch in the last minute holds off sleep")

        now += Sleep.PHONE_GRACE_MS
        core.activeWaits.incrementAndGet()
        core.sleepIfDue()
        assertFalse(core.sleep.asleep.value, "an active wait holds off sleep")
        assertEquals(listOf("wait"), core.sleepBlockers())

        core.activeWaits.decrementAndGet()
        core.sleepIfDue()
        assertTrue(core.sleep.asleep.value, "done applies when the wait ends")
        assertEquals("\"done\"", core.events.since(0, setOf("sleep")).last().data["by"].toString())
    }

    @Test fun anAgentCallCancelsDone() = runBlocking {
        val core = Fixtures.core(PushSource(), clock)
        core.activeWaits.incrementAndGet()
        core.release()
        core.agentActive()
        core.activeWaits.decrementAndGet()
        core.sleepIfDue()
        assertFalse(core.sleep.asleep.value, "the agent came back before Lookout slept")
    }

    @Test fun zeroMinutesTurnsTheIdleTimerOff() = runBlocking {
        val core = Fixtures.core(PushSource(), clock)
        core.updateSettings { it.copy(sleepAfterMinutes = 0) }
        now += 24 * 60 * 60_000L
        core.sleepIfDue()
        assertFalse(core.sleep.asleep.value)
        core.release()
        assertTrue(core.sleep.asleep.value, "done still works")
    }

    @Test fun sleepAndDoneCloseTheCameraOnBatteryDefaultAutoMode() = runBlocking {
        val source = PushSource(clock = clock)
        val core = Fixtures.core(source, clock, device = DeviceState(charging = false, batteryPct = 60))
        assertEquals(PowerMode.AUTO, core.session.effectiveMode())

        core.agentActive()
        core.session.use { }
        assertTrue(source.isOpen, "auto keeps the camera open after a call")

        // done closes the camera at once, without waiting for COLD_IDLE_MS or AUTO_WARM_MS.
        core.release()
        assertTrue(core.sleep.asleep.value)
        assertFalse(source.isOpen, "done closes the camera immediately")

        // Idle sleep shorter than AUTO_WARM_MS still sleeps and closes the camera.
        core.updateSettings { it.copy(sleepAfterMinutes = 2) }
        core.agentActive()
        core.session.use { }
        assertTrue(source.isOpen)
        assertTrue(core.sleepBlockers().isEmpty(), "an open auto camera is not a sleep blocker")

        now += 2 * 60_000L
        core.sleepIfDue()
        assertTrue(core.sleep.asleep.value)
        assertFalse(source.isOpen, "idle sleep closes the camera even within AUTO_WARM_MS")
    }
}
