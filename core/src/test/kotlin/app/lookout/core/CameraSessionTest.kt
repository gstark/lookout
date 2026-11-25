package app.lookout.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSessionTest {
    @Test fun previewStaysOnWhenANewScreenStartsBeforeTheOldOneStops() = runBlocking {
        val source = PushSource()
        // On battery with no recent request, the default auto mode leaves the camera closed, so only the preview keeps it open.
        val core = Fixtures.core(source, device = DeviceState(charging = false, batteryPct = 50))
        val session = core.session

        session.previewStarted()          // the old activity
        session.reconcile()
        assertTrue(source.isOpen, "the preview opens the camera")

        session.previewStarted()          // Android starts the new activity first ...
        session.previewStopped()          // ... and stops the old one after it
        session.reconcile()
        assertTrue(session.previewActive, "the new screen still shows the preview")
        assertTrue(source.isOpen, "the camera stays open")

        session.previewStopped()
        session.previewStopped()          // an extra stop does not go below zero
        assertFalse(session.previewActive)
        session.previewStarted()
        assertTrue(session.previewActive, "one start after extra stops turns it on again")
    }

    @Test fun batteryDefaultIsAutoAndKeepsCameraOpenUntilIdleTimeout() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val source = PushSource(clock = clock, onOpen = { now += 400 })
        val core = Fixtures.core(source, clock = clock, device = DeviceState(charging = false, batteryPct = 50))
        val session = core.session

        kotlin.test.assertEquals(PowerMode.AUTO, session.effectiveMode())
        session.reconcile()
        assertFalse(source.isOpen, "before any request, auto leaves the camera closed")

        val first = session.use { it }
        assertTrue(first.coldStart)
        kotlin.test.assertEquals(400L, first.openMs)
        assertTrue(source.isOpen)

        now += CameraSession.COLD_IDLE_MS + 1
        session.reconcile()
        assertTrue(source.isOpen, "auto keeps the camera open past COLD_IDLE_MS")

        val second = session.use { it }
        assertFalse(second.coldStart, "already open")
        kotlin.test.assertEquals(0L, second.openMs)

        now += CameraSession.AUTO_WARM_MS - 1
        session.reconcile()
        assertTrue(source.isOpen, "still within AUTO_WARM_MS")

        now += 1
        session.reconcile()
        assertFalse(source.isOpen, "closes after AUTO_WARM_MS with no request")
    }

    @Test fun explicitColdClosesAfterColdIdleAndChargingDefaultsToWarm() = runBlocking {
        var now = 1_000_000L
        val clock = Clock { now }
        val source = PushSource(clock = clock)
        val battery = Fixtures.core(source, clock = clock, device = DeviceState(charging = false, batteryPct = 50))
        battery.updateSettings { it.copy(powerMode = PowerMode.COLD) }

        kotlin.test.assertEquals(PowerMode.COLD, battery.session.effectiveMode())
        battery.session.use { }
        assertTrue(source.isOpen)

        now += CameraSession.COLD_IDLE_MS
        battery.session.reconcile()
        assertTrue(source.isOpen, "stays open until COLD_IDLE_MS passes")

        now += 1
        battery.session.reconcile()
        assertFalse(source.isOpen, "explicit cold closes after COLD_IDLE_MS")

        val chargingSource = PushSource(clock = clock)
        val charging = Fixtures.core(chargingSource, clock = clock, device = DeviceState(charging = true, batteryPct = 80))
        kotlin.test.assertEquals(PowerMode.WARM, charging.session.effectiveMode())
        charging.session.reconcile()
        assertTrue(chargingSource.isOpen, "charging defaults to warm and opens without a request")
        now += CameraSession.AUTO_WARM_MS * 2
        charging.session.reconcile()
        assertTrue(chargingSource.isOpen, "charging warm stays open past AUTO_WARM_MS")

        val hotSource = PushSource(clock = clock)
        val hot = Fixtures.core(hotSource, clock = clock, device = DeviceState(charging = true, thermal = Thermal.SEVERE))
        kotlin.test.assertEquals(PowerMode.COLD, hot.session.effectiveMode(), "SEVERE thermal forces cold")
    }

    @Test fun orientationSettingReachesTheSourceAndDescribe() = runBlocking {
        val source = PushSource()
        val kv = MemoryKeyValueStore()
        val core = LookoutCore(app.lookout.awt.AwtImageOps, source, kv, MemoryBlobStore(), { DeviceState() }, { true })
        kotlin.test.assertEquals(FrameOrientation.PORTRAIT, source.lastOrientation, "portrait by default, set at start")
        core.updateSettings { it.copy(orientation = FrameOrientation.LANDSCAPE) }
        kotlin.test.assertEquals(FrameOrientation.LANDSCAPE, source.lastOrientation)
        kotlin.test.assertEquals("landscape", core.capability("describe").call(Args.EMPTY, CallContext("test")).json["orientation"].toString().trim('"'))

        // It survives a restart, and settings saved before this field existed read as portrait.
        val again = PushSource()
        LookoutCore(app.lookout.awt.AwtImageOps, again, kv, MemoryBlobStore(), { DeviceState() }, { true })
        kotlin.test.assertEquals(FrameOrientation.LANDSCAPE, again.lastOrientation)
        val old = MemoryKeyValueStore().also { it.put(LookoutCore.SETTINGS_KEY, """{"port":8080,"jpegQuality":80}""") }
        val legacy = PushSource()
        LookoutCore(app.lookout.awt.AwtImageOps, legacy, old, MemoryBlobStore(), { DeviceState() }, { true })
        kotlin.test.assertEquals(FrameOrientation.PORTRAIT, legacy.lastOrientation)
    }
}
