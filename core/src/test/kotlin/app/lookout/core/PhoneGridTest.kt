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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneGridTest {
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

    @Test fun resultsReportTheGridThatThePersonSees() = runBlocking {
        val src = PushSource()
        val core = Fixtures.core(src).also { it.start(scope) }
        suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = core.capability(tool).call(args(*pairs), ctx)
        core.session.use {}
        src.push(Fixtures.scene())
        core.store.latest.filterNotNull().first()

        assertEquals(false, call("describe").json["phone_grid"]!!.jsonObject["shown"]!!.jsonPrimitive.boolean)
        assertNull(call("look", "fresh" to false, "width" to 320).json["phone_grid"], "no phone_grid while the grid is off")

        core.phoneGrid.set(true)
        assertEquals("grid", core.events.recent(1).single().type)
        val described = call("describe").json["phone_grid"]!!.jsonObject
        assertEquals("8x6", described.str("grid"))
        assertEquals("person", described.str("by"))
        val note = described.str("note")
        assertTrue("A1..H6" in note && "left to right" in note && "C3 is x 0.25..0.375, y 0.3333..0.5" in note, note)
        val looked = call("look", "fresh" to false, "width" to 320, "roi" to "0.5,0.5,0.25,0.25").json["phone_grid"]!!.jsonObject
        assertEquals("person", looked.str("by"))

        core.phoneGrid.set(false)
        assertNull(call("look", "fresh" to false, "width" to 320).json["phone_grid"])
    }

    @Test fun aGuideWithATargetBoxShowsTheGrid() = runBlocking {
        val core = Fixtures.core(PushSource())
        assertNull(core.phoneGrid.json())
        val shown = async(Dispatchers.Default) {
            core.human.guide("Shot", "Fit it", null, listOf("Ready"), 5_000, fit = NRect(0.25, 0.25, 0.5, 0.5))
        }
        while (core.human.current.value == null) delay(5)
        assertEquals("guide", core.phoneGrid.json()!!.str("by"))
        core.human.answer(core.human.current.value!!.id, "Ready")
        assertEquals("Ready", shown.await())
        assertNull(core.phoneGrid.json())
    }

    @Test fun aCropLabelsTheFullFrameCells() {
        val comp = Compositor(AwtImageOps)
        val canvas = AwtImageOps.canvas(800, 600, 0xFF606060.toInt())
        val full = comp.grid(canvas, GridSpec.DEFAULT)
        // C2:D3 is x 200..400, y 100..300 in an 800 x 600 frame.
        val region = GridSpec.DEFAULT.span("C2", "D3")
        val px = region.toPixels(800, 600)
        val expected = AwtImageOps.argb(AwtImageOps.crop(full, px), px.w, px.h)
        val actual = AwtImageOps.argb(comp.grid(AwtImageOps.crop(canvas, px), GridSpec.DEFAULT, region), px.w, px.h)
        var worst = 0
        // The crop edges hold the full-frame lines at x 200 and y 100, which the crop leaves out.
        for (y in 3 until px.h - 3) for (x in 3 until px.w - 3) {
            val a = expected[y * px.w + x]
            val b = actual[y * px.w + x]
            for (shift in listOf(0, 8, 16)) worst = maxOf(worst, abs((a shr shift and 0xFF) - (b shr shift and 0xFF)))
        }
        assertTrue(worst < 40, "the crop shows the same lines and labels as the full-frame grid, worst channel difference $worst")
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
