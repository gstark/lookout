package app.lookout.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.tan
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlurTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun tearDown() = scope.cancel()

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Number) put(k, v) else if (v is Boolean) put(k, v) else put(k, v.toString()) }
    })

    @Test fun blurIsRateTimesExposureTimesFocalLength() {
        // 90 degrees across 2000 px gives a focal length of 1000 px.
        assertEquals(10.0, Blur.predictedPx(0.1, 100_000_000L, 90.0, 1.0, 2000))
        // The mural shot: 1/15 s, 55.8 degrees, 4000 px wide, a steady hand at 0.02 rad/s.
        val f = 2000 / tan(Math.toRadians(27.9))
        assertEquals(Math.round(0.02 * 0.0666668 * f * 10) / 10.0, Blur.predictedPx(0.02, 66_666_802L, 55.8, 1.0, 4000))
    }

    @Test fun zoomMakesTheSameTurnBlurMore() {
        val x1 = Blur.predictedPx(0.05, 33_000_000L, 60.0, 1.0, 1000)!!
        val x2 = Blur.predictedPx(0.05, 33_000_000L, 60.0, 2.0, 1000)!!
        assertEquals(2 * x1, x2, 0.15)
    }

    @Test fun missingValuesGiveNoBlur() {
        assertNull(Blur.predictedPx(null, 1L, 60.0, 1.0, 100))
        assertNull(Blur.predictedPx(0.1, null, 60.0, 1.0, 100))
        assertNull(Blur.predictedPx(0.1, 1L, null, 1.0, 100))
    }

    @Test fun onlyAHandheldLookGetsBlurFields() = runBlocking {
        val source = PushSource(lensList = listOf(LensInfo("back", "back", fovHDeg = 90.0)))
        val core = Fixtures.core(source).also { it.start(scope) }
        core.session.open.first { it }
        source.exposureNs = 50_000_000L
        val ctx = CallContext("test")

        source.rate = 0.001
        source.push(Fixtures.scene())
        core.store.latest.filter { it?.camera?.rateRadS == 0.001 }.first()
        val still = core.capability("look").call(args("fresh" to false), ctx).json
        assertNull(still["handheld"])
        assertNull(still["blur_px"])

        source.rate = 0.1
        source.push(Fixtures.scene())
        core.store.latest.filter { it?.camera?.rateRadS == 0.1 }.first()
        val held = core.capability("look").call(args("fresh" to false), ctx).json
        assertTrue(held["handheld"]!!.jsonPrimitive.boolean)
        // 0.1 rad/s × 50 ms × (width / 2) px of focal length at 90 degrees.
        val width = core.store.latest.value!!.image.width
        assertEquals(Math.round(0.1 * 0.05 * width / 2 * 10) / 10.0, held["blur_px"]!!.jsonPrimitive.double)
        assertFalse(Blur.handheld(null))
    }
}
