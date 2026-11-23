package app.lookout.core

import kotlinx.serialization.json.JsonArray
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarksTest {
    private val grid = GridSpec.DEFAULT
    private val rois = mapOf("led" to NRect(0.6, 0.4, 0.1, 0.1))

    private fun parse(json: String): List<PointMark> =
        Marks.parse(LookoutJson.parseToJsonElement(json) as JsonArray, grid) { rois[it] ?: throw LookoutError("roi_not_found", 404, it) }

    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-9, "$a != $b")

    @Test fun placesTakeCellsSpansPointsRectsAndRois() {
        val c3 = Marks.place("C3", grid) { error("no") }
        assertEquals(NRect(2.0 / 8, 2.0 / 6, 1.0 / 8, 1.0 / 6), c3.rect)
        near(c3.center.x, 2.5 / 8)
        assertEquals(NRect(2.0 / 8, 1.0 / 6, 3.0 / 8, 3.0 / 6), Marks.place("c2:e4", grid) { error("no") }.rect)
        val p = Marks.place("0.4, 0.5", grid) { error("no") }
        assertEquals(NPoint(0.4, 0.5), p.center)
        assertNull(p.rect)
        assertEquals(NRect(0.1, 0.2, 0.3, 0.4), Marks.place("0.1,0.2,0.3,0.4", grid) { error("no") }.rect)
        assertEquals(rois["led"], Marks.place("led", grid) { rois.getValue(it) }.rect)
    }

    @Test fun boxOutlinesTheRegionAndArrowStopsAtItsEdge() {
        val (box, arrow) = parse("""[{"shape":"box","at":"led","label":"LED","color":"green"},{"at":"led","from":"0.65,0.9"}]""")
        assertEquals(NRect(0.6, 0.4, 0.1, 0.1), box.rect)
        assertEquals("green", box.color)
        assertEquals("arrow", arrow.shape)
        assertEquals(NPoint(0.65, 0.9), arrow.from)
        near(arrow.to!!.x, 0.65)
        near(arrow.to!!.y, 0.5)
    }

    @Test fun defaultTailPointsInFromTheFrameCenter() {
        val (arrow) = parse("""[{"at":"0.1,0.1"}]""")
        val tail = arrow.from!!
        assertTrue(tail.x > 0.1 && tail.y > 0.1, "tail $tail sits toward the center")
        near(hypot(tail.x - 0.1, tail.y - 0.1), 0.18)
        val (centered) = parse("""[{"at":"0.5,0.5"}]""")
        assertTrue(centered.from!!.x > 0.5 && centered.from!!.y < 0.5, "a centered target gets a tail up and to the right")
        val (cell) = parse("""[{"at":"B2"}]""")
        near(hypot(cell.from!!.x - cell.to!!.x, cell.from!!.y - cell.to!!.y), 0.18)
    }

    @Test fun labelSitsAtTheCenterOfItsPlace() {
        val (label) = parse("""[{"shape":"label","at":"A1","label":"reset"}]""")
        assertEquals("reset", label.label)
        near(label.to!!.x, 0.5 / 8)
        near(label.to!!.y, 0.5 / 6)
    }

    @Test fun badSpecsAreClearErrors() {
        fun code(json: String) = assertFailsWith<LookoutError> { parse(json) }.code
        assertEquals("invalid_param", code("[]"))
        assertEquals("invalid_param", code("""[{"shape":"circle","at":"C3"}]"""))
        assertEquals("invalid_param", code("""[{"shape":"box","at":"0.5,0.5"}]"""))
        assertEquals("invalid_param", code("""[{"shape":"label","at":"C3"}]"""))
        assertEquals("invalid_param", code("""[{"shape":"box"}]"""))
        assertEquals("invalid_param", code("""[{"at":"C3","colour":"red"}]"""))
        assertEquals("invalid_param", code("""[{"at":"1.2,0.5"}]"""))
        assertEquals("invalid_cell", code("""[{"at":"Z9"}]"""))
        assertEquals("roi_not_found", code("""[{"at":"nowhere"}]"""))
        assertEquals("invalid_param", code((1..9).joinToString(",", "[", "]") { """{"at":"C3"}""" }))
    }

    @Test fun arrowWingsTrailTheHead() {
        val wings = Marks.arrowWings(0.0, 0.0, 100.0, 0.0, 10.0)
        wings.forEach { (x, y) -> assertTrue(x < 100.0 && abs(y) > 1.0, "wing ($x,$y) trails the head") }
        near(wings[0].second, -wings[1].second)
    }

    @Test fun pointerClearsOnlyItsOwnSet() {
        var now = 1_000L
        val p = Pointer { now }
        val first = p.show(emptyList(), null, 5_000)
        val second = p.show(emptyList(), "here", 5_000)
        p.clear(first.id)
        assertEquals(second.id, p.current.value?.id)
        now += 6_000
        assertNull(p.active(), "an expired set is not active")
        p.clear()
        assertNull(p.current.value)
    }
}
