package app.lookout.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiltTest {
    private val g = 9.81

    @Test fun uprightPhoneLooksLevel() {
        assertEquals(Tilt(0.0, 0.0), Tilt.fromUp(0.0, g, 0.0, frontLens = false))
    }

    @Test fun phoneFlatOnItsBackLooksStraightDown() {
        assertEquals(-90.0, Tilt.fromUp(0.0, 0.0, g, frontLens = false)!!.pitchDeg)
        assertEquals(90.0, Tilt.fromUp(0.0, 0.0, g, frontLens = true)!!.pitchDeg)
    }

    @Test fun topTurnedLeftGivesClockwiseRoll() {
        // The phone top turns 10 degrees to the left, so true up leans to the right of frame up.
        val r = Math.toRadians(10.0)
        val t = Tilt.fromUp(g * kotlin.math.sin(r), g * kotlin.math.cos(r), 0.0, frontLens = false)!!
        assertEquals(10.0, t.rollDeg)
        assertEquals(-10.0, Tilt.fromUp(g * kotlin.math.sin(r), g * kotlin.math.cos(r), 0.0, frontLens = true)!!.rollDeg)
    }

    @Test fun lensTiltedDownGivesNegativePitch() {
        // The top of the phone leans away from the user, so the back lens looks down.
        val p = Math.toRadians(20.0)
        assertEquals(-20.0, Tilt.fromUp(0.0, g * kotlin.math.cos(p), g * kotlin.math.sin(p), frontLens = false)!!.pitchDeg)
    }

    @Test fun angleBetweenTilts() {
        assertEquals(4.0, Tilt(-10.0, 0.0).angleTo(Tilt(-14.0, 0.0)), 1e-9)
        assertEquals(5.0, Tilt(0.0, 0.0).angleTo(Tilt(0.0, 5.0)), 1e-9)
        // Near straight down, a large roll change is a small real move.
        assertEquals(true, Tilt(-89.0, 0.0).angleTo(Tilt(-89.0, 90.0)) < 1.5)
    }

    @Test fun noDirectionWithoutGravity() {
        assertNull(Tilt.fromUp(0.0, 0.0, 0.0, frontLens = false))
    }
}
