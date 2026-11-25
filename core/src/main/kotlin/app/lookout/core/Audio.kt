package app.lookout.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure PCM sound analysis: overall level, noise floor, loudness over time, sound events with
 * hysteresis and gap merging, dominant frequencies from an averaged Hann-windowed FFT, regularity
 * check for repeating chirps, and per-mark response delays.
 */
object Audio {
    const val FULL_SCALE = 32768.0
    const val MIN_DB = -96.0

    /** Fixed frame size for noise floor, event detection and raw=true loudness. */
    const val BASE_STEP_MS = 20
    const val MAX_LOUDNESS_POINTS = 200
    const val NOISE_FLOOR_PERCENTILE = 0.15

    /** Level rise above the noise floor to start an event, and the drop below that to end it. */
    const val EVENT_RISE_DB = 10.0
    const val EVENT_HYSTERESIS_DB = 3.0
    const val MIN_EVENT_DB = -70.0

    /** Gaps up to this long join into one event. */
    const val EVENT_GAP_MS = 100L
    const val MAX_EVENTS = 50

    const val FFT_SIZE = 4096
    const val MAX_FFT_FRAMES = 128
    const val MIN_FREQ_HZ = 20.0
    const val MAX_SPECTRAL_PEAKS = 5
    const val PEAK_MIN_SEP_BINS = 2
    const val PEAK_FLOOR_REL_DB = -30.0

    const val PERIOD_TOLERANCE = 0.15
    const val PERIOD_MIN_TOL_MS = 40L
    const val MARK_WINDOW_MS = 500L

    private val LOUDNESS_STEPS_MS = intArrayOf(20, 50, 100, 200, 300, 500, 1000)
    private val PEAK_COLUMNS = buildJsonArray { add("hz"); add("rel_db") }
    private val EVENT_COLUMNS = buildJsonArray { add("from_ms"); add("to_ms"); add("peak_dbfs"); add("main_hz") }
    private val MARK_COLUMNS = buildJsonArray {
        add("label"); add("t_ms"); add("after_rms_dbfs"); add("after_peak_dbfs"); add("event_delay_ms"); add("event_hz")
    }

    private class SpectralPeak(val hz: Double, val relDb: Double)
    private class Spectrum(val mainHz: Double?, val peaks: List<SpectralPeak>, val resolutionHz: Double)
    private class SoundEvent(val fromMs: Long, val toMs: Long, val peakDb: Double, val mainHz: Double?)

    /** In-place radix-2 Cooley-Tukey FFT. [re] and [im] must have the same power-of-two length. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0 && im.size == n) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wStepR = cos(ang)
            val wStepI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until half) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + half] * wr - im[i + k + half] * wi
                    val vI = re[i + k + half] * wi + im[i + k + half] * wr
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + half] = uR - vR
                    im[i + k + half] = uI - vI
                    val nextR = wr * wStepR - wi * wStepI
                    wi = wr * wStepI + wi * wStepR
                    wr = nextR
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Linear 16-bit amplitude to dBFS, floored at [MIN_DB]. */
    fun dbfs(amplitude: Double): Double = if (amplitude <= 0.0) MIN_DB else max(MIN_DB, 20.0 * log10(amplitude / FULL_SCALE))

    /**
     * Analyzes [pcm] starting at [fromMs] on the recording timeline. [marks] should already be
     * filtered to this window. With [raw], the loudness series uses [BASE_STEP_MS] with no 200-point cap.
     */
    fun analyze(pcm: Pcm, fromMs: Long = 0L, marks: List<Mark> = emptyList(), raw: Boolean = false): JsonObject {
        val sr = pcm.sampleRate
        val n = pcm.size
        val durationMs = n.toLong() * 1000L / sr
        val toMs = fromMs + durationMs
        val resolutionHz = sr.toDouble() / FFT_SIZE

        if (n == 0) {
            return buildJsonObject {
                put("sample_rate", sr)
                put("from_ms", fromMs)
                put("to_ms", toMs)
                put("duration_ms", 0L)
                put("rms_dbfs", MIN_DB)
                put("peak_dbfs", MIN_DB)
                put("noise_floor_dbfs", MIN_DB)
                put("fft_resolution_hz", round1(resolutionHz))
                put("loudness_step_ms", BASE_STEP_MS)
                putJsonArray("loudness") {}
                put("events_columns", EVENT_COLUMNS)
                putJsonArray("events") {}
            }
        }

        val (overallRms, overallPeak) = rmsAndPeak(pcm.samples, 0, n)
        val baseFrames = windowedRms(pcm.samples, n, sr, BASE_STEP_MS)
        val sortedBase = baseFrames.sortedArray()
        val noiseFloor = sortedBase[(sortedBase.size * NOISE_FLOOR_PERCENTILE).toInt().coerceIn(0, sortedBase.lastIndex)]
        val enterDb = max(MIN_EVENT_DB, noiseFloor + EVENT_RISE_DB)
        val exitDb = enterDb - EVENT_HYSTERESIS_DB

        val allEvents = detectEvents(pcm.samples, n, sr, baseFrames, enterDb, exitDb, fromMs, durationMs)
        val periodMs = detectPeriod(allEvents)
        val keptEvents = if (allEvents.size <= MAX_EVENTS) allEvents
        else allEvents.sortedByDescending { it.peakDb }.take(MAX_EVENTS).sortedBy { it.fromMs }

        val loudStep = chooseLoudnessStep(durationMs, raw)
        val loudness = if (loudStep == BASE_STEP_MS) baseFrames else windowedRms(pcm.samples, n, sr, loudStep)
        val spec = spectrum(pcm.samples, 0, n, sr)

        return buildJsonObject {
            put("sample_rate", sr)
            put("from_ms", fromMs)
            put("to_ms", toMs)
            put("duration_ms", durationMs)
            put("rms_dbfs", round1(overallRms))
            put("peak_dbfs", round1(overallPeak))
            put("noise_floor_dbfs", round1(noiseFloor))
            put("event_threshold_dbfs", round1(enterDb))
            put("fft_resolution_hz", round1(spec.resolutionHz))
            spec.mainHz?.let { put("main_hz", round1(it)) }
            if (spec.peaks.isNotEmpty()) {
                put("peaks_columns", PEAK_COLUMNS)
                putJsonArray("peaks") {
                    spec.peaks.forEach { p ->
                        add(buildJsonArray { add(round1(p.hz)); add(round1(p.relDb)) })
                    }
                }
            }
            put("loudness_step_ms", loudStep)
            putJsonArray("loudness") { loudness.forEach { add(round1(it)) } }
            put("events_columns", EVENT_COLUMNS)
            putJsonArray("events") {
                keptEvents.forEach { ev ->
                    add(buildJsonArray {
                        add(ev.fromMs)
                        add(ev.toMs)
                        add(round1(ev.peakDb))
                        add(ev.mainHz?.let { JsonPrimitive(round1(it)) } ?: JsonNull)
                    })
                }
            }
            if (allEvents.size > MAX_EVENTS) put("events_truncated", allEvents.size - MAX_EVENTS)
            periodMs?.let { put("period_ms", it) }
            if (marks.isNotEmpty()) {
                put("mark_window_ms", MARK_WINDOW_MS)
                put("marks_columns", MARK_COLUMNS)
                putJsonArray("marks") {
                    marks.forEachIndexed { i, m ->
                        val localMs = m.tMs - fromMs
                        val s0 = (localMs * sr / 1000L).toInt().coerceIn(0, n)
                        val s1 = ((localMs + MARK_WINDOW_MS) * sr / 1000L).toInt().coerceIn(s0, n)
                        val (afterRms, afterPeak) = if (s1 > s0) rmsAndPeak(pcm.samples, s0, s1) else (null to null)
                        val nextMarkMs = marks.getOrNull(i + 1)?.tMs ?: Long.MAX_VALUE
                        val ev = allEvents.firstOrNull { it.fromMs >= m.tMs - BASE_STEP_MS / 2 && it.fromMs < nextMarkMs }
                        val delay = ev?.let { max(0L, it.fromMs - m.tMs) }
                        add(buildJsonArray {
                            add(m.label)
                            add(m.tMs)
                            add(afterRms?.let { JsonPrimitive(round1(it)) } ?: JsonNull)
                            add(afterPeak?.let { JsonPrimitive(round1(it)) } ?: JsonNull)
                            add(delay?.let(::JsonPrimitive) ?: JsonNull)
                            add(ev?.mainHz?.let { JsonPrimitive(round1(it)) } ?: JsonNull)
                        })
                    }
                }
            }
        }
    }

    private fun chooseLoudnessStep(durationMs: Long, raw: Boolean): Int {
        if (raw || durationMs <= 0L) return BASE_STEP_MS
        for (step in LOUDNESS_STEPS_MS) {
            if ((durationMs + step - 1) / step <= MAX_LOUDNESS_POINTS) return step
        }
        val minStep = (durationMs + MAX_LOUDNESS_POINTS - 1) / MAX_LOUDNESS_POINTS
        return (((minStep + 49) / 50) * 50).toInt()
    }

    private fun windowedRms(samples: ShortArray, n: Int, sr: Int, stepMs: Int): DoubleArray {
        val count = max(1, ((n.toLong() * 1000L + stepMs.toLong() * sr - 1) / (stepMs.toLong() * sr)).toInt())
        val out = DoubleArray(count)
        var actual = 0
        for (w in 0 until count) {
            val s0 = (w.toLong() * stepMs * sr / 1000L).toInt()
            if (s0 >= n) break
            val s1 = min(n, ((w + 1).toLong() * stepMs * sr / 1000L).toInt())
            if (s1 <= s0) continue
            out[actual++] = rmsAndPeak(samples, s0, s1).first
        }
        return if (actual == out.size) out else out.copyOf(actual)
    }

    private fun rmsAndPeak(samples: ShortArray, from: Int, to: Int): Pair<Double, Double> {
        if (to <= from) return MIN_DB to MIN_DB
        var sumSq = 0.0
        var peak = 0
        for (i in from until to) {
            val v = samples[i].toInt()
            sumSq += v.toDouble() * v.toDouble()
            val a = abs(v)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / (to - from))
        return dbfs(rms) to dbfs(peak.toDouble())
    }

    private fun detectEvents(
        samples: ShortArray, n: Int, sr: Int, frameDb: DoubleArray, enterDb: Double, exitDb: Double, offsetMs: Long, durationMs: Long,
    ): List<SoundEvent> {
        val raw = mutableListOf<Pair<Long, Long>>()
        var startMs = -1L
        for (i in frameDb.indices) {
            val tMs = i.toLong() * BASE_STEP_MS
            val db = frameDb[i]
            if (startMs < 0) {
                if (db >= enterDb) startMs = tMs
            } else if (db < exitDb) {
                raw += startMs to tMs
                startMs = -1L
            }
        }
        if (startMs >= 0) raw += startMs to durationMs
        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf<Pair<Long, Long>>()
        for (span in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && span.first - prev.second <= EVENT_GAP_MS) {
                merged[merged.lastIndex] = prev.first to span.second
            } else {
                merged += span
            }
        }
        return merged.map { (a, b) ->
            val s0 = (a * sr / 1000L).toInt().coerceIn(0, n)
            val s1 = (b * sr / 1000L).toInt().coerceIn(s0, n)
            val peakDb = rmsAndPeak(samples, s0, s1).second
            val hz = spectrum(samples, s0, s1, sr).mainHz
            SoundEvent(offsetMs + a, offsetMs + b, peakDb, hz)
        }
    }

    /** When 3 or more events have regular onset spacing, returns the median period in ms. */
    private fun detectPeriod(events: List<SoundEvent>): Long? {
        if (events.size < 3) return null
        val gaps = (0 until events.size - 1).map { events[it + 1].fromMs - events[it].fromMs }
        val sorted = gaps.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        if (median <= 0L) return null
        val tol = max(PERIOD_MIN_TOL_MS, (median * PERIOD_TOLERANCE).roundToLong())
        return if (gaps.all { abs(it - median) <= tol }) median else null
    }

    /** Averaged Hann-windowed power spectrum over [from]..[to]. */
    private fun spectrum(samples: ShortArray, from: Int, to: Int, sr: Int): Spectrum {
        val len = to - from
        val resHz = sr.toDouble() / FFT_SIZE
        if (len < 16) return Spectrum(null, emptyList(), resHz)

        val winLen = min(len, FFT_SIZE)
        val hann = DoubleArray(winLen) { k -> if (winLen > 1) 0.5 * (1.0 - cos(2.0 * PI * k / (winLen - 1))) else 1.0 }
        val starts: IntArray = if (len <= FFT_SIZE) {
            intArrayOf(from)
        } else {
            val hop = FFT_SIZE / 2
            val natural = (len - FFT_SIZE) / hop + 1
            if (natural <= MAX_FFT_FRAMES) {
                IntArray(natural) { from + it * hop }
            } else {
                val span = len - FFT_SIZE
                IntArray(MAX_FFT_FRAMES) { from + (it.toLong() * span / (MAX_FFT_FRAMES - 1)).toInt() }
            }
        }

        val half = FFT_SIZE / 2
        val power = DoubleArray(half + 1)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (s in starts) {
            var sum = 0.0
            for (k in 0 until winLen) sum += samples[s + k].toDouble()
            val mean = sum / winLen
            for (k in 0 until winLen) re[k] = (samples[s + k] - mean) * hann[k]
            for (k in winLen until FFT_SIZE) re[k] = 0.0
            im.fill(0.0)
            fft(re, im)
            for (k in 1..half) power[k] += re[k] * re[k] + im[k] * im[k]
        }

        val minBin = max(1, ceil(MIN_FREQ_HZ / resHz).toInt())
        if (minBin > half) return Spectrum(null, emptyList(), resHz)
        var maxPow = 0.0
        for (k in minBin..half) if (power[k] > maxPow) maxPow = power[k]
        if (maxPow <= 0.0) return Spectrum(null, emptyList(), resHz)

        val candidates = mutableListOf<Int>()
        for (k in minBin until half) {
            if (power[k] > 0.0 && power[k] >= power[k - 1] && power[k] >= power[k + 1]) candidates += k
        }
        if (power[half] > 0.0 && power[half] >= power[half - 1]) candidates += half
        candidates.sortByDescending { power[it] }

        val picked = mutableListOf<Int>()
        val peaks = mutableListOf<SpectralPeak>()
        for (k in candidates) {
            if (peaks.size >= MAX_SPECTRAL_PEAKS) break
            val relDb = 10.0 * log10(power[k] / maxPow)
            if (relDb < PEAK_FLOOR_REL_DB) break
            if (picked.any { abs(it - k) <= PEAK_MIN_SEP_BINS }) continue
            picked += k
            val hz = if (k in (minBin + 1) until half && power[k - 1] > 0.0 && power[k + 1] > 0.0) {
                val a = ln(power[k - 1])
                val b = ln(power[k])
                val c = ln(power[k + 1])
                val d = a - 2.0 * b + c
                val delta = if (d < 0.0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
                (k + delta) * resHz
            } else {
                k * resHz
            }
            peaks += SpectralPeak(hz, relDb)
        }
        return Spectrum(peaks.firstOrNull()?.hz, peaks, resHz)
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0 + 0.0
}
