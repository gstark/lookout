package app.lookout.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSessionTest {
    @Test fun previewStaysOnWhenANewScreenStartsBeforeTheOldOneStops() = runBlocking {
        val source = PushSource()
        // On battery the default mode is cold, so only the preview keeps the camera open.
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
