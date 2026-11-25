package app.lookout.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Reads the up direction from the gravity sensor, or from the accelerometer when the phone has no
 * gravity sensor. [up] turns the reading into screen axes for a display or target rotation.
 */
class GravityReader(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor = gravitySensor ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // The raw accelerometer also measures movement, so smooth it. The gravity sensor is already smooth.
    private val alpha = if (gravitySensor != null) 1f else 0.15f

    @Volatile private var device: FloatArray? = null
    private var listener: SensorEventListener? = null

    /** Starts the sensor. [onChange] runs on the main thread after each reading. */
    @Synchronized
    fun start(delay: Int = SensorManager.SENSOR_DELAY_UI, onChange: () -> Unit = {}) {
        if (listener != null || sensor == null) return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val prev = device
                device = if (prev == null) {
                    floatArrayOf(e.values[0], e.values[1], e.values[2])
                } else {
                    FloatArray(3) { i -> prev[i] + alpha * (e.values[i] - prev[i]) }
                }
                onChange()
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        listener = l
        sm.registerListener(l, sensor, delay)
    }

    @Synchronized
    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
        device = null
    }

    /**
     * The up vector in screen axes for [rotation] (a Surface.ROTATION_* value): x to the right,
     * y to the top, z out of the screen. Null before the first reading.
     */
    fun up(rotation: Int): FloatArray? {
        val v = device ?: return null
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> -v[1] to v[0]
            Surface.ROTATION_180 -> -v[0] to -v[1]
            Surface.ROTATION_270 -> v[1] to -v[0]
            else -> v[0] to v[1]
        }
        return floatArrayOf(x, y, v[2])
    }
}
