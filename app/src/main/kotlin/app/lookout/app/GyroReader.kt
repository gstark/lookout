package app.lookout.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads the gyroscope and keeps the last [CAPACITY] samples (about 5 s at the game rate). A sample is the rate that moves the
 * image: the turn about the two screen axes, plus half the turn about the lens axis (that turn moves
 * the frame edges about half as far as a tilt does, at the fields of view of phone lenses).
 * Sensor timestamps use the elapsedRealtime clock, the same clock as most camera timestamps.
 */
class GyroReader(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val times = LongArray(CAPACITY)
    private val rates = DoubleArray(CAPACITY)
    private var head = 0
    private var count = 0
    private var listener: SensorEventListener? = null
    private var startedNs = 0L

    val available: Boolean get() = sensor != null

    @Synchronized
    fun start() {
        if (listener != null || sensor == null) return
        startedNs = SystemClock.elapsedRealtimeNanos()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble() * LENS_AXIS_WEIGHT)
                add(e.timestamp, sqrt(x * x + y * y + z * z))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        listener = l
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    @Synchronized
    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
        count = 0
    }

    @Synchronized
    private fun add(atNs: Long, rate: Double) {
        // The first readings after the sensor starts are noisy, and read as a hand on a phone that rests on something.
        if (atNs - startedNs < WARMUP_NS) return
        times[head] = atNs
        rates[head] = rate
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /** The highest rate in rad/s from [fromNs] to [toNs], with the nearest sample on each side. Null without samples. */
    @Synchronized
    fun peak(fromNs: Long, toNs: Long): Double? {
        if (count == 0) return null
        var best: Double? = null
        var before = -1
        var after = -1
        for (k in 0 until count) {
            val i = (head - 1 - k + CAPACITY) % CAPACITY
            val t = times[i]
            when {
                t > toNs -> after = i
                t < fromNs -> { before = i; break }
                else -> best = maxOf(best ?: 0.0, rates[i])
            }
        }
        // A short exposure can fall between two samples: use the samples on each side of it.
        for (i in intArrayOf(before, after)) if (i >= 0 && (best == null || rates[i] > best)) best = rates[i]
        return best
    }

    /** The highest rate in the last [windowMs], or null when the sensor is off or has no recent sample. */
    fun recent(windowMs: Long = RECENT_MS): Double? {
        val now = SystemClock.elapsedRealtimeNanos()
        val newest = synchronized(this) { if (count == 0) null else times[(head - 1 + CAPACITY) % CAPACITY] }
        if (newest == null || abs(now - newest) > STALE_NS) return null
        return peak(now - windowMs * 1_000_000, now)
    }

    private companion object {
        const val CAPACITY = 256
        const val STALE_NS = 200_000_000L
        const val WARMUP_NS = 200_000_000L
        const val RECENT_MS = 50L
        const val LENS_AXIS_WEIGHT = 0.5
    }
}
