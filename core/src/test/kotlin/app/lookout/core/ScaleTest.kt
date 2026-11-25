package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.hypot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaleTest {
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
                is Long -> put(k, v)
                is Double -> put(k, v)
                is Boolean -> put(k, v)
                else -> error("unexpected arg $v")
            }
        }
    })

    @Test fun scaleMathConvertsAcrossPreviewStillAndCroppedVideo() {
        // 4:3 landscape look frame (1280 x 960): physical scene is 128 mm wide x 96 mm tall (0.1 mm/px at 1280px).
        // Diagonal from (0.2, 0.2) to (0.7, 0.6): dx = 640 px, dy = 384 px.
        val diagMm = hypot(640.0, 384.0) * 0.1
        val cal = ScaleCalibration.fromPoints(
            from = NPoint(0.2, 0.2),
            to = NPoint(0.7, 0.6),
            mm = diagMm,
            frameWidth = 1280,
            frameHeight = 960,
            lens = "back",
            zoom = 1.0,
            tilt = Tilt(-90.0, 0.0),
            setAt = 1000L,
        )
        assertEquals(128.0, cal.widthMm, 1e-6)
        assertEquals(96.0, cal.heightMm, 1e-6)

        // Preview 1280 px wide (full frame): 128 / 1280 = 0.1 mm/px.
        assertEquals(0.1, cal.mmPerPx(1280), 1e-9)
        // Still 4000 px wide (full frame): 128 / 4000 = 0.032 mm/px.
        assertEquals(0.032, cal.mmPerPx(4000), 1e-9)
        // 16:9 landscape video (1920x1080) cropped vertically from 4:3 look frame: covers full width (w=1.0, h=0.75).
        assertEquals(128.0 / 1920.0, cal.mmPerPx(1920, NRect(0.0, 0.125, 1.0, 0.75)), 1e-9)

        // Portrait 3:4 look frame (960 x 1280): 96 mm wide x 128 mm tall.
        // 9:16 portrait video (1080x1920) covers middle 75% of look width (x=0.125, w=0.75).
        val port = ScaleCalibration.fromPoints(
            from = NPoint(0.0, 0.25),
            to = NPoint(0.0, 0.75),
            mm = 64.0,
            frameWidth = 960,
            frameHeight = 1280,
            lens = "back",
            zoom = 1.0,
            tilt = null,
            setAt = 1000L,
        )
        assertEquals(96.0, port.widthMm, 1e-6)
        assertEquals(128.0, port.heightMm, 1e-6)
        // Video width covers 96 * 0.75 = 72 mm across 1080 px -> 72 / 1080 = 1/15 mm/px.
        assertEquals(72.0 / 1080.0, port.mmPerPx(1080, NRect(0.125, 0.0, 0.75, 1.0)), 1e-9)
    }

    @Test fun scaleParsesGridCellsAndRejectsBadInputs() {
        val g = GridSpec(8, 6)
        val a1 = ScaleCalibration.parsePoint("A1", g)
        val e1 = ScaleCalibration.parsePoint("E1", g)
        assertEquals(0.5 / 8, a1.x, 1e-9)
        assertEquals(0.5 / 6, a1.y, 1e-9)
        assertEquals(4.5 / 8, e1.x, 1e-9)

        val p = ScaleCalibration.parsePoint("0.25,0.75", g)
        assertEquals(0.25, p.x, 1e-9)
        assertEquals(0.75, p.y, 1e-9)

        assertFailsWith<LookoutError> { ScaleCalibration.parsePoint("A1:C3", g) }
        assertFailsWith<LookoutError> { ScaleCalibration.parsePoint("0.1,0.2,0.3,0.4", g) }
        assertFailsWith<LookoutError> {
            ScaleCalibration.fromPoints(NPoint(0.5, 0.5), NPoint(0.5, 0.5), 10.0, 640, 480, "back", 1.0, null, 0L)
        }
        assertFailsWith<LookoutError> {
            ScaleCalibration.fromPoints(NPoint(0.1, 0.5), NPoint(0.5, 0.5), 0.0, 640, 480, "back", 1.0, null, 0L)
        }
    }

    @Test fun lensFovAccountsFor43CropAndRotation() {
        // 4:3 sensor: 6.4 mm x 4.8 mm, f = 4.0 mm.
        // In landscape (rot=0): horizontal = 6.4 -> 2 atan(6.4/8) = 77.3 deg, vertical = 4.8 -> 2 atan(4.8/8) = 61.9 deg.
        val (hLand, vLand) = assertNotNull(ScaleCalibration.lensFovDeg(6.4, 4.8, 4.0, 0))
        assertEquals(77.3, hLand, 0.1)
        assertEquals(61.9, vLand, 0.1)
        // In portrait (rot=90): axes swap.
        val (hPort, vPort) = assertNotNull(ScaleCalibration.lensFovDeg(6.4, 4.8, 4.0, 90))
        assertEquals(61.9, hPort, 0.1)
        assertEquals(77.3, vPort, 0.1)
        // 16:9 sensor (8.0 mm x 4.5 mm) cropped to 4:3 uses 6.0 mm x 4.5 mm.
        val (h169, v169) = assertNotNull(ScaleCalibration.lensFovDeg(8.0, 4.5, 4.0, 0))
        val (h43, v43) = assertNotNull(ScaleCalibration.lensFovDeg(6.0, 4.5, 4.0, 0))
        assertEquals(h43, h169)
        assertEquals(v43, v169)
        assertNull(ScaleCalibration.lensFovDeg(6.4, 4.8, 0.0, 0))
    }

    @Test fun scalePersistsShowsInLookCropDescribeMarksStaleAndClearsOnMoved() = runBlocking<Unit> {
        val kv = MemoryKeyValueStore()
        val src = PushSource(still = Fixtures.scene(1600, 1200))
        src.tilt = Tilt(-85.0, 0.0)
        val core = LookoutCore(
            AwtImageOps, src, kv, MemoryBlobStore(),
            { DeviceState(charging = true, batteryPct = 80) }, { true }, Clock.SYSTEM,
        ).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

        core.session.use {}
        src.push(Fixtures.scene(640, 480))
        delay(50)

        // Initially unset.
        val empty = call("scale", "action" to "get").json
        assertEquals(JsonNull, empty["scale"])
        assertNull(call("describe").json["scale"])
        assertNull(call("look", "fresh" to false).json["mm_per_px"])

        // Set scale using grid cells: A1 (x=0.0625) to E1 (x=0.5625) -> span 0.5 of width = 50 mm -> width_mm = 100.
        val set = call("scale", "action" to "set", "from" to "A1", "to" to "E1", "mm" to 50.0).json
        assertTrue(set["valid"]!!.jsonPrimitive.boolean)
        assertEquals(100.0, set["scale"]!!.jsonObject["width_mm"]!!.jsonPrimitive.double, 0.01)
        assertEquals(75.0, set["scale"]!!.jsonObject["height_mm"]!!.jsonPrimitive.double, 0.01)
        assertNoLongDecimals(set)

        // Persisted in KeyValueStore.
        val reloaded = ScaleStore(kv).get()
        assertNotNull(reloaded)
        assertEquals(100.0, reloaded.widthMm, 1e-6)

        // Compact summary in describe.
        val desc = call("describe").json["scale"]!!.jsonObject
        assertEquals(100.0, desc["width_mm"]!!.jsonPrimitive.double, 0.01)
        assertTrue(desc["valid"]!!.jsonPrimitive.boolean)
        assertNull(desc["stale_reason"])

        // look and crop report mm_per_px for the returned image pixels.
        val lookFull = call("look", "fresh" to false, "width" to 320).json
        assertEquals(100.0 / 320.0, lookFull["mm_per_px"]!!.jsonPrimitive.double, 0.002)
        val fid = lookFull["frame_id"]!!.jsonPrimitive.long.toString()

        // Crop to half width, output width 160 px -> covers 50 mm in 160 px -> 0.3125 mm/px.
        val cropped = call("crop", "frame" to fid, "roi" to "0.25,0.25,0.5,0.5", "width" to 160).json
        assertEquals(50.0 / 160.0, cropped["mm_per_px"]!!.jsonPrimitive.double, 0.002)

        // Still look (1600x1200) cropped to 0.25 width (400 source px = 25 mm) at output width 400 -> 25 / 400 = 0.0625 mm/px.
        val stillCrop = call("look", "detail" to "full", "roi" to "0.25,0.25,0.25,0.25", "width" to 400).json
        assertEquals(25.0 / 400.0, stillCrop["mm_per_px"]!!.jsonPrimitive.double, 0.002)

        // Zoom change marks scale stale and suppresses mm_per_px.
        call("camera", "zoom" to 2.0)
        src.push(Fixtures.scene(640, 480))
        delay(50)
        val staleZoom = call("scale", "action" to "get").json
        assertFalse(staleZoom["valid"]!!.jsonPrimitive.boolean)
        assertTrue("zoom" in staleZoom["stale_reason"]!!.jsonPrimitive.content)
        assertNull(call("look", "fresh" to false, "width" to 320).json["mm_per_px"])
        assertFalse(call("describe").json["scale"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean)

        // Reverting zoom restores validity; changing lens marks stale.
        call("camera", "zoom" to 1.0)
        assertTrue(call("scale", "action" to "get").json["valid"]!!.jsonPrimitive.boolean)
        call("camera", "lens" to "front")
        val staleLens = call("scale", "action" to "get").json
        assertFalse(staleLens["valid"]!!.jsonPrimitive.boolean)
        assertTrue("lens" in staleLens["stale_reason"]!!.jsonPrimitive.content)

        // Revert lens, then trigger a moved event (tilt change > MOVED_DEG) -> clears stored scale.
        call("camera", "lens" to "back")
        assertTrue(call("scale", "action" to "get").json["valid"]!!.jsonPrimitive.boolean)
        src.tilt = Tilt(-75.0, 0.0)
        src.push(Fixtures.scene(640, 480))
        delay(100)
        assertTrue(core.events.since(0, setOf("moved")).isNotEmpty(), "moved event should fire")
        assertEquals(JsonNull, call("scale", "action" to "get").json["scale"], "moved event must clear stored scale")
        assertNull(ScaleStore(kv).get())
    }

    @Test fun diffUsesStoredScaleAndExplicitWins() = runBlocking<Unit> {
        val src = PushSource()
        val core = Fixtures.core(src).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)

        core.session.use {}
        val aImg = Fixtures.texture(640, 480, dx = 0.0, dy = 0.0, seed = 9)
        val bImg = Fixtures.texture(640, 480, dx = 12.0, dy = 0.0, seed = 9)
        src.push(aImg)
        delay(40)
        val idA = call("look", "fresh" to false).json["frame_id"]!!.jsonPrimitive.long.toString()

        // Calibrate: full width (640 px) = 64 mm -> 0.1 mm/px.
        call("scale", "action" to "set", "from" to "0.0,0.5", "to" to "1.0,0.5", "mm" to 64.0)

        src.push(bImg)
        delay(40)
        val idB = call("look", "fresh" to false).json["frame_id"]!!.jsonPrimitive.long.toString()

        // Stored scale is used when mm_per_px is omitted.
        val withStored = call("diff", "a" to idA, "b" to idB, "roi" to "0.3,0.3,0.4,0.4", "image" to false).json
        assertEquals("stored", withStored["scale"]!!.jsonPrimitive.content)
        assertEquals(0.1, withStored["mm_per_px"]!!.jsonPrimitive.double, 0.002)
        assertEquals(withStored["dx"]!!.jsonPrimitive.double * 0.1, withStored["dx_mm"]!!.jsonPrimitive.double, 0.02)

        // Explicit mm_per_px overrides stored scale and omits scale="stored".
        val withExplicit = call("diff", "a" to idA, "b" to idB, "roi" to "0.3,0.3,0.4,0.4", "image" to false, "mm_per_px" to 0.5).json
        assertNull(withExplicit["scale"])
        assertEquals(0.5, withExplicit["mm_per_px"]!!.jsonPrimitive.double, 0.002)
        assertEquals(withExplicit["dx"]!!.jsonPrimitive.double * 0.5, withExplicit["dx_mm"]!!.jsonPrimitive.double, 0.05)
    }

    @Test fun theStoredScaleFollowsTheZoomOfTheMeasuredFrame() = runBlocking<Unit> {
        val src = PushSource()
        val core = Fixtures.core(src).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)
        val image = Fixtures.texture(640, 480, dx = 0.0, dy = 0.0, seed = 9)

        core.session.use {}
        src.push(image)
        delay(40)
        call("scale", "action" to "set", "from" to "0.0,0.5", "to" to "1.0,0.5", "mm" to 64.0)
        val at1 = call("look", "fresh" to false, "width" to 320).json
        assertEquals(0.2, at1["mm_per_px"]!!.jsonPrimitive.double, 0.002)
        val id1 = at1["frame_id"]!!.jsonPrimitive.long.toString()

        // A look with a temporary zoom takes its frame at 2x. The camera goes back to 1x after it.
        val pending = async { call("look", "zoom" to 2.0, "width" to 320).json }
        delay(200)
        src.push(image)
        val at2 = pending.await()
        assertEquals(2.0, at2["zoom"]!!.jsonPrimitive.double, "the frame was taken at 2x")
        assertNull(at2["mm_per_px"])
        assertEquals(1.0, src.applied.zoom, "the camera is back at 1x")
        val id2 = at2["frame_id"]!!.jsonPrimitive.long.toString()

        assertNull(call("crop", "frame" to id2, "width" to 320).json["mm_per_px"], "a 2x frame gets no 1x scale")
        assertEquals(0.2, call("crop", "frame" to id1, "width" to 320).json["mm_per_px"]!!.jsonPrimitive.double, 0.002)
        for ((a, b) in listOf(id1 to id2, id2 to id1)) {
            val d = call("diff", "a" to a, "b" to b, "roi" to "0.3,0.3,0.4,0.4", "image" to false).json
            assertNull(d["mm_per_px"], "diff $a to $b mixes zooms")
            assertNull(d["dx_mm"])
        }

        // The latest frame is the 2x one, so a scale set from its points is a 2x scale.
        val set = call("scale", "action" to "set", "from" to "0.0,0.5", "to" to "1.0,0.5", "mm" to 32.0).json
        assertEquals(2.0, set["scale"]!!.jsonObject["zoom"]!!.jsonPrimitive.double)
        assertEquals(0.1, call("crop", "frame" to id2, "width" to 320).json["mm_per_px"]!!.jsonPrimitive.double, 0.002)
    }

    @Test fun trackUsesStoredScaleWithVideoCropAndExplicitWins() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("lookout-track-scale").toFile()
        // Portrait 3:4 look frame (480x640); 9:16 video covers x=0.125..0.875 (360x640 video px).
        val crop = NRect(0.125, 0.0, 0.75, 1.0)
        fun edgeFrame(edgeLookX: Double): Img {
            val img = BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(30, 30, 30)
            g.fillRect(0, 0, 480, 640)
            val ex = (edgeLookX * 480).toInt()
            g.color = Color(230, 230, 230)
            g.fillRect(ex, 0, 480 - ex, 640)
            g.dispose()
            return AwtImg(img)
        }
        val frames = List(18) { i -> edgeFrame(if (i < 9) 0.35 else 0.55) }
        val src = object : FrameSource {
            private val delegate = ReplayFrameSource(listOf(frames[0]), fps = 5.0, ops = AwtImageOps)
            private val comp = Compositor(AwtImageOps)
            private var recEnd: ((RecordingEnd) -> Unit)? = null
            override fun lenses() = delegate.lenses()
            override val isOpen get() = delegate.isOpen
            override suspend fun open(onFrame: (RawFrame) -> Unit) = delegate.open(onFrame)
            override suspend fun close() = delegate.close()
            override suspend fun captureStill() = delegate.captureStill()
            override suspend fun apply(settings: CameraSettings) = delegate.apply(settings)
            override fun setAnalysisFps(fps: Double) = delegate.setAnalysisFps(fps)
            override fun recordingCaps() = RecordingCaps(listOf(30), VideoResolution.entries.map { it.wire })
            override suspend fun startRecording(spec: RecordSpec, file: java.io.File, onEnd: (RecordingEnd) -> Unit): RecordingStarted {
                val cropped = frames.map { comp.crop(it, crop) }
                LkClip.Writer(file).use { w ->
                    cropped.forEachIndexed { i, f -> w.write(i * 1_000_000L / 30, AwtImageOps.encodeJpeg(f, 95)) }
                }
                recEnd = onEnd
                return RecordingStarted(System.currentTimeMillis(), cropped[0].width, cropped[0].height, 30, 1, LkClip.EXTENSION, crop)
            }
            override suspend fun stopRecording() {
                recEnd?.invoke(RecordingEnd())
                recEnd = null
            }
        }
        val core = Fixtures.core(src, recordingsDir = dir).also {
            it.updateSettings { s -> s.copy(powerMode = PowerMode.WARM) }
            it.start(scope)
        }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)
        try {
            // Full look width (480 px) = 96 mm -> 0.2 mm/px in look frame and also 0.2 mm/px in 360px video covering 0.75 of width (72 / 360 = 0.2).
            call("scale", "action" to "set", "from" to "0.0,0.5", "to" to "1.0,0.5", "mm" to 96.0)

            val start = call("record", "action" to "start", "fps" to 30, "duration_ms" to 600).json
            val recId = start["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content
            call("record", "action" to "stop")
            call("wait_for", "condition" to "recording_done", "recording_id" to recId, "sheet" to false)

            // Stored scale applies automatically. Travel from x=0.35 to 0.55 = 0.2 of 96 mm = 19.2 mm (96 video px).
            val tracked = call("clip", "recording_id" to recId, "mode" to "track", "roi" to "0.2,0.48,0.6,0.04").json
            assertEquals("stored", tracked["scale"]!!.jsonPrimitive.content)
            assertEquals(0.2, tracked["mm_per_px"]!!.jsonPrimitive.double, 0.002)
            assertEquals(19.2, tracked["mm"]!!.jsonObject["travel"]!!.jsonPrimitive.double, 0.6)
            assertNoLongDecimals(tracked)

            // Explicit mm_per_px overrides stored scale.
            val explicit = call("clip", "recording_id" to recId, "mode" to "track", "roi" to "0.2,0.48,0.6,0.04", "mm_per_px" to 0.5).json
            assertNull(explicit["scale"])
            assertEquals(0.5, explicit["mm_per_px"]!!.jsonPrimitive.double, 0.002)
            assertEquals(48.0, explicit["mm"]!!.jsonObject["travel"]!!.jsonPrimitive.double, 1.5)

            // The scale follows the zoom the recording was made with, not the live camera.
            call("camera", "zoom" to 2.0)
            val oldAtNewZoom = call("clip", "recording_id" to recId, "mode" to "track", "roi" to "0.2,0.48,0.6,0.04").json
            assertEquals(0.2, oldAtNewZoom["mm_per_px"]!!.jsonPrimitive.double, 0.002, "a recording made at 1x keeps its scale")
            val zoomed = call("record", "action" to "start", "fps" to 30, "duration_ms" to 600).json["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content
            call("record", "action" to "stop")
            call("wait_for", "condition" to "recording_done", "recording_id" to zoomed, "sheet" to false)
            call("camera", "zoom" to 1.0)
            val atOtherZoom = call("clip", "recording_id" to zoomed, "mode" to "track", "roi" to "0.2,0.48,0.6,0.04").json
            assertNull(atOtherZoom["scale"], "a recording made at 2x gets no stored scale")
            assertNull(atOtherZoom["mm_per_px"])
            assertNull(atOtherZoom["mm"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
