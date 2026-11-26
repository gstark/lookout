package app.lookout.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * What the bubble level on the phone shows, from the up direction (against gravity) in screen axes:
 * x to the right, y down the screen, z out of the screen. The overlay and the level trigger of a guided shot use the same rule,
 * so the shot fires exactly when the person sees green.
 *
 * Flat (camera within [FLAT_LIMIT_DEG] of straight down or up): [a] is the left-right tilt and [b] the
 * up-down tilt, signed so the bubble moves toward the high side. Upright: [a] is the roll and [b] the pitch.
 */
data class LevelReading(val flat: Boolean, val a: Double, val b: Double) {
    /** How far from level in degrees: the worse of both tilts when flat, the roll when upright. */
    val offDeg: Double get() = if (flat) max(abs(a), abs(b)) else abs(a)
    val green: Boolean get() = offDeg <= LevelRule.LEVEL_DEG

    companion object {
        /** The reading of the up vector ([x], [y], [z]) in screen axes, or null when it is too weak to use. */
        fun of(x: Double, y: Double, z: Double): LevelReading? {
            val g = hypot(hypot(x, y), z).takeIf { it > 0.1 } ?: return null
            val flat = Math.toDegrees(acos((abs(z) / g).coerceIn(0.0, 1.0))) < LevelRule.FLAT_LIMIT_DEG
            return if (flat) {
                LevelReading(true, Math.toDegrees(atan2(x, abs(z))), Math.toDegrees(atan2(y, abs(z))))
            } else {
                LevelReading(false, Math.toDegrees(atan2(x, -y)), Math.toDegrees(atan2(z, hypot(x, y))))
            }
        }
    }
}

object LevelRule {
    /** Within this many degrees the level is green. */
    const val LEVEL_DEG = 1.0

    /** Within this many degrees the level is yellow. */
    const val CLOSE_DEG = 3.0

    /** The phone counts as flat (camera straight down or straight up) while it is within this angle. */
    const val FLAT_LIMIT_DEG = 45.0
}
