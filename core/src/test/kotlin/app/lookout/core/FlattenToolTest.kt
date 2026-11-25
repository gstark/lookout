package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlattenToolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ctx = CallContext("test")

    @AfterTest fun tearDown() = scope.cancel()

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    })

    private fun cornerList(vararg pts: Pt) = JsonArray(pts.map { JsonPrimitive("${it.x / 1600},${it.y / 1200}") })

    private val given = listOf(Pt(306.0, 199.0), Pt(1284.0, 246.0), Pt(1344.0, 888.0), Pt(256.0, 857.0))

    private fun rows(r: CapResult) = r.json["circles"]!!.jsonObject["rows"]!!.jsonArray.map { row -> row.jsonArray.take(3).map { it.jsonPrimitive.double } }

    @Test fun aKeptFrameIsFlattenedWithNoCameraAndNoWake() = runBlocking {
        val source = PushSource(still = Plate.photo)
        val core = Fixtures.core(source).also { it.start(scope) }
        val id = core.capability("look").call(args("detail" to "full", "width" to 400), ctx).json["frame_id"]!!.jsonPrimitive.long
        core.stop()
        val opens = source.opens
        val flatten = core.capability("flatten")
        val a = args("frame" to id.toString(), "corners" to cornerList(*given.toTypedArray()), "width_mm" to 80, "height_mm" to 50)
        assertFalse(flatten.wakes(a), "a kept frame needs no camera")
        assertTrue(flatten.wakes(args("mat" to true)), "a new still needs the camera")
        val r = flatten.call(a, ctx)
        assertFalse(source.isOpen)
        assertEquals(opens, source.opens, "the camera did not open")
        assertEquals(id, r.json["frame_id"]!!.jsonPrimitive.long)
        assertEquals(1600, r.json["source_width"]!!.jsonPrimitive.long.toInt())
        assertEquals(12.8, r.json["px_per_mm"]!!.jsonPrimitive.double)
        assertEquals(5, rows(r).size)
        assertEquals(1024, AwtImageOps.decode(r.image!!.jpeg).width)
        assertNoLongDecimals(r.json)
    }

    @Test fun withNoFrameItTakesASharpStillAndKeepsIt() = runBlocking {
        val core = Fixtures.core(PushSource(still = Plate.photo)).also { it.start(scope) }
        val r = core.capability("flatten").call(args("corners" to cornerList(*given.toTypedArray()), "width_mm" to 80, "height_mm" to 50, "circles" to false), ctx)
        assertEquals("still", r.json["source"]!!.jsonPrimitive.content)
        core.store.find(r.json["frame_id"]!!.jsonPrimitive.long)
        assertFalse("circles" in r.json)
    }

    @Test fun cornersInAnyOrderGiveTheSameResult() = runBlocking {
        val core = Fixtures.core(PushSource(still = Plate.photo)).also { it.start(scope) }
        val id = core.capability("look").call(args("detail" to "full"), ctx).json["frame_id"]!!.jsonPrimitive.long.toString()
        val a = core.capability("flatten").call(args("frame" to id, "corners" to cornerList(*given.toTypedArray()), "width_mm" to 80, "height_mm" to 50), ctx)
        val b = core.capability("flatten").call(args("frame" to id, "corners" to cornerList(given[2], given[0], given[3], given[1]), "width_mm" to 80, "height_mm" to 50), ctx)
        assertEquals(rows(a), rows(b))
        assertEquals(a.json["corners"], b.json["corners"])
        val cells = core.capability("flatten").call(args("frame" to id, "corners" to "0.19,0.17,0.8,0.2,0.84,0.74,0.16,0.71", "width_mm" to 80, "height_mm" to 50), ctx)
        assertEquals(rows(a).size, rows(cells).size, "a REST style flat list works too")
    }

    @Test fun theWidthIsTheSideNearerHorizontalInTheFrame() = runBlocking {
        // The same plate turned on its side: its 80 mm edges now run up and down the frame.
        val turned = listOf(Pt(420.0, 1100.0), Pt(470.0, 110.0), Pt(1110.0, 140.0), Pt(1090.0, 1080.0))
        val photo = Fixtures.plane(1600, 1200, Fixtures.view(Plate.W, Plate.H, turned).inverse(), 3, Plate::paint)
        val core = Fixtures.core(PushSource(still = photo)).also { it.start(scope) }
        val id = core.capability("look").call(args("detail" to "full"), ctx).json["frame_id"]!!.jsonPrimitive.long.toString()
        val corners = cornerList(*turned.toTypedArray())
        val r = core.capability("flatten").call(args("frame" to id, "corners" to corners, "width_mm" to 50, "height_mm" to 80), ctx)
        assertEquals(640, r.json["width"]!!.jsonPrimitive.long.toInt())
        assertEquals(1024, r.json["height"]!!.jsonPrimitive.long.toInt())
        assertFalse("warnings" in r.json)
        // The view's x runs along the plate's y, and its y runs back along the plate's x.
        val hole = Plate.holes[0]
        assertTrue(rows(r).any { abs(it[0] - hole.second) < 0.2 && abs(it[1] - (Plate.W - hole.first)) < 0.2 }, "${rows(r)}")
        val wrong = core.capability("flatten").call(args("frame" to id, "corners" to corners, "width_mm" to 80, "height_mm" to 50, "circles" to false), ctx)
        assertTrue(wrong.json["warnings"]!!.jsonArray.any { "other way round" in it.jsonPrimitive.content })
    }

    @Test fun inlineFalseAndRulersInMm() = runBlocking {
        val core = Fixtures.core(PushSource(still = Plate.photo)).also { it.start(scope) }
        val id = core.capability("look").call(args("detail" to "full"), ctx).json["frame_id"]!!.jsonPrimitive.long.toString()
        val base = arrayOf("frame" to id, "corners" to cornerList(*given.toTypedArray()), "width_mm" to 80, "height_mm" to 50, "circles" to false)
        val r = core.capability("flatten").call(args(*base, "inline" to false), ctx)
        assertEquals(null, r.image)
        val imageId = r.json["image_id"]!!.jsonPrimitive.content
        assertEquals(1024, AwtImageOps.decode(core.tools.frameJpeg(imageId)).width)
        val plain = core.capability("flatten").call(args(*base), ctx).image!!.jpeg
        val ruled = core.capability("flatten").call(args(*base, "grid" to "ticks"), ctx).image!!.jpeg
        assertFalse(plain.contentEquals(ruled))
    }

    @Test fun badReferencesAreRejected() = runBlocking {
        val core = Fixtures.core(PushSource(still = Plate.photo)).also { it.start(scope) }
        val flatten = core.capability("flatten")
        assertEquals("missing_param", assertFailsWith<LookoutError> { flatten.call(args("frame" to "1"), ctx) }.code)
        assertEquals("invalid_param", assertFailsWith<LookoutError> { flatten.call(args("mat" to true, "corners" to cornerList(*given.toTypedArray())), ctx) }.code)
        assertEquals("missing_param", assertFailsWith<LookoutError> { flatten.call(args("corners" to cornerList(*given.toTypedArray()), "width_mm" to 80), ctx) }.code)
    }
}
