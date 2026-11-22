package app.lookout.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Gravity in screen axes: x to the right, y up the screen, z out of the screen. */
private data class Gravity(val x: Float, val y: Float, val z: Float)

private const val LEVEL_DEG = 1f
private const val CLOSE_DEG = 3f

/** The phone counts as flat (camera straight down or straight up) while it is within this angle. */
private const val FLAT_LIMIT_DEG = 45f

/** The bubble reaches the outer ring at this tilt. */
private const val RING_DEG = 10f

private val LevelGreen = Color(0xFF22C55E)
private val CloseYellow = Color(0xFFFACC15)
private val OffRed = Color(0xFFFF3B30)

private fun tone(deg: Float): Color = when {
    abs(deg) <= LEVEL_DEG -> LevelGreen
    abs(deg) <= CLOSE_DEG -> CloseYellow
    else -> OffRed
}

private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

/**
 * A bubble level over the preview. With the phone flat (camera straight down) it shows a bubble and
 * the tilt on both axes. With the phone upright it shows a horizon line, the roll and the pitch.
 * The sensor runs only while the overlay is visible and the activity is started.
 */
@Composable
fun LevelOverlay(modifier: Modifier = Modifier) {
    val gravity = rememberGravity() ?: return
    val g = hypot(hypot(gravity.x, gravity.y), gravity.z).takeIf { it > 0.1f } ?: return
    // Tilt from flat, signed so that the bubble moves toward the high side.
    val tiltX = deg(atan2(gravity.x, abs(gravity.z)))
    val tiltY = deg(atan2(gravity.y, abs(gravity.z)))
    val flat = deg(acos((abs(gravity.z) / g).coerceIn(0f, 1f))) < FLAT_LIMIT_DEG

    Box(modifier.fillMaxSize()) {
        if (flat) {
            val worst = max(abs(tiltX), abs(tiltY))
            val color = tone(worst)
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = min(size.width, size.height) * 0.38f
                val shade = Color.Black.copy(alpha = 0.45f)
                drawCircle(shade, r + 10f, c)
                drawCircle(Color.White, r, c, style = Stroke(5f))
                drawCircle(Color.White, r * CLOSE_DEG / RING_DEG, c, style = Stroke(3f))
                drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 3f)
                drawLine(Color.White, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 3f)
                // Screen y grows downward, and the bubble floats away from the low edge.
                val bx = (tiltX / RING_DEG).coerceIn(-1f, 1f) * r
                val by = (tiltY / RING_DEG).coerceIn(-1f, 1f) * r
                val bubble = Offset(c.x + bx, c.y + by)
                val br = r * 0.16f
                drawCircle(color.copy(alpha = 0.85f), br, bubble)
                drawCircle(Color.Black, br, bubble, style = Stroke(5f))
                drawCircle(Color.Black, r * LEVEL_DEG / RING_DEG + 3f, c, style = Stroke(2f))
            }
            LevelReadout(
                listOf("Left-right" to tiltX, "Up-down" to tiltY),
                if (worst <= LEVEL_DEG) "Straight down" else null,
                Modifier.align(Alignment.TopStart),
            )
        } else {
            // Upright: roll is the turn in the screen plane, pitch is the lean toward or away.
            val roll = deg(atan2(gravity.x, -gravity.y))
            val pitch = deg(atan2(gravity.z, hypot(gravity.x, gravity.y)))
            val color = tone(roll)
            Canvas(Modifier.fillMaxSize()) {
                val half = size.width * 0.42f
                drawLine(Color.White.copy(alpha = 0.6f), Offset(center.x - half, center.y), Offset(center.x + half, center.y), strokeWidth = 3f)
                rotate(roll, center) {
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(center.x - half, center.y), Offset(center.x + half, center.y), strokeWidth = 18f)
                    drawLine(color, Offset(center.x - half, center.y), Offset(center.x + half, center.y), strokeWidth = 10f)
                }
            }
            LevelReadout(listOf("Roll" to roll, "Pitch" to pitch), if (abs(roll) <= LEVEL_DEG) "Level" else null, Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun LevelReadout(rows: List<Pair<String, Float>>, done: String?, modifier: Modifier) {
    Column(modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        rows.forEach { (label, value) ->
            Text("$label ${String.format(Locale.US, "%+.1f", value)}°", color = tone(value), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        done?.let { Text(it, color = LevelGreen, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Reads gravity from the sensor and turns it into screen axes for the current display rotation. */
@Composable
private fun rememberGravity(): Gravity? {
    val context = LocalContext.current
    val view = LocalView.current
    var gravity by remember { mutableStateOf<Gravity?>(null) }
    LifecycleStartEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravitySensor ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // The raw accelerometer also measures movement, so smooth it. The gravity sensor is already smooth.
        val alpha = if (gravitySensor != null) 1f else 0.15f
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var first = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y) = when (view.display?.rotation) {
                    Surface.ROTATION_90 -> -e.values[1] to e.values[0]
                    Surface.ROTATION_180 -> -e.values[0] to -e.values[1]
                    Surface.ROTATION_270 -> e.values[1] to -e.values[0]
                    else -> e.values[0] to e.values[1]
                }
                val z = e.values[2]
                if (first) {
                    sx = x; sy = y; sz = z; first = false
                } else {
                    sx += alpha * (x - sx); sy += alpha * (y - sy); sz += alpha * (z - sz)
                }
                // Flip y so that it grows downward, as screen coordinates do.
                gravity = Gravity(sx, -sy, sz)
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onStopOrDispose {
            sm.unregisterListener(listener)
            gravity = null
        }
    }
    return gravity
}
