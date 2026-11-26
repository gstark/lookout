package app.lookout.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelRuleTest {
    private val g = 9.81
    private fun r(deg: Double) = Math.toRadians(deg)

    @Test fun flatPhoneIsGreenWithinOneDegreeOnBothAxes() {
        val level = LevelReading.of(0.0, 0.0, g)!!
        assertTrue(level.flat)
        assertTrue(level.green)
        val tilted = LevelReading.of(g * sin(r(0.8)), g * sin(r(1.5)), g * cos(r(1.5)))!!
        assertEquals(0.8, tilted.a, 0.05)
        assertEquals(1.5, tilted.b, 0.05)
        assertFalse(tilted.green, "the worse axis decides")
    }

    @Test fun uprightPhoneIsGreenOnRollAlone() {
        // Upright with the top of the phone up: up points to the top of the screen, and y grows downward.
        val leaning = LevelReading.of(0.0, -g * cos(r(20.0)), g * sin(r(20.0)))!!
        assertFalse(leaning.flat)
        assertEquals(0.0, leaning.a, 1e-9)
        assertEquals(20.0, leaning.b, 1e-9)
        assertTrue(leaning.green, "pitch does not count when upright")
        val rolled = LevelReading.of(g * sin(r(2.0)), -g * cos(r(2.0)), 0.0)!!
        assertEquals(2.0, rolled.offDeg, 1e-9)
        assertFalse(rolled.green)
    }

    @Test fun noReadingWithoutGravity() {
        assertNull(LevelReading.of(0.0, 0.0, 0.0))
    }
}
