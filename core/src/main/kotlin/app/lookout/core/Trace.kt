package app.lookout.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** Turns a brightness series from an ROI into high and low runs, for LED blink patterns and flicker. */
object Trace {
    class Sample(val tMs: Long, val r: Int, val g: Int, val b: Int) {
        val luma: Int get() = (r * 299 + g * 587 + b * 114) / 1000
    }

    class Run(val high: Boolean, val fromMs: Long, var toMs: Long)

    /** Below this luma range the ROI counts as steady and has no runs. */
    const val MIN_RANGE = 12
    const val MAX_RUNS = 200

    fun analyze(samples: List<Sample>, frameMs: Double, threshold: Int?, raw: Boolean): JsonObject {
        val lumas = samples.map { it.luma }
        val lo = lumas.min()
        val hi = lumas.max()
        val steady = threshold == null && hi - lo < MIN_RANGE
        val th = threshold ?: ((lo + hi) / 2)
        // Hysteresis keeps sensor noise near the threshold from making false transitions.
        val band = if (threshold != null) 0 else ((hi - lo) * 0.1).roundToInt()
        val runs = mutableListOf<Run>()
        if (!steady) {
            var level = lumas[0] > th
            for ((i, s) in samples.withIndex()) {
                val l = lumas[i]
                if (level && l < th - band) level = false else if (!level && l > th + band) level = true
                val last = runs.lastOrNull()
                if (last == null || last.high != level) runs += Run(level, s.tMs, s.tMs) else last.toMs = s.tMs
            }
        }
        // A run lasts until the next one starts; the last run lasts one frame past its last sample.
        fun length(i: Int): Long = (if (i + 1 < runs.size) runs[i + 1].fromMs else runs[i].toMs + frameMs.roundToInt()) - runs[i].fromMs
        val inner = runs.indices.drop(1).dropLast(1)
        val highs = inner.filter { runs[it].high }.map { length(it) }.sorted()
        val lows = inner.filter { !runs[it].high }.map { length(it) }.sorted()
        val periods = inner.filter { runs[it].high && it + 1 in inner }.map { length(it) + length(it + 1) }.sorted()
        return buildJsonObject {
            put("samples", samples.size)
            put("frame_interval_ms", (frameMs * 10).roundToInt() / 10.0)
            putJsonObject("luma") {
                put("min", lo)
                put("max", hi)
                put("mean", lumas.average().roundToInt())
            }
            put("threshold", th)
            put("steady", steady)
            put("transitions", (runs.size - 1).coerceAtLeast(0))
            if (highs.isNotEmpty()) put("high_ms", stats(highs))
            if (lows.isNotEmpty()) put("low_ms", stats(lows))
            if (periods.isNotEmpty()) {
                val median = periods[periods.size / 2]
                put("period_ms_median", median)
                if (median > 0) put("frequency_hz", (10_000.0 / median).roundToInt() / 10.0)
            }
            putJsonArray("runs") {
                runs.take(MAX_RUNS).forEachIndexed { i, r ->
                    add(buildJsonObject {
                        put("level", if (r.high) "high" else "low")
                        put("from_ms", r.fromMs)
                        put("ms", length(i))
                        if (i == 0 || i == runs.size - 1) put("partial", true)
                    })
                }
            }
            if (runs.size > MAX_RUNS) put("runs_truncated", runs.size - MAX_RUNS)
            if (raw) {
                putJsonArray("raw") {
                    samples.forEach { s -> addJsonArray { add(s.tMs); add(s.luma); add(s.r); add(s.g); add(s.b) } }
                }
            }
        }
    }

    private fun stats(sorted: List<Long>) = buildJsonObject {
        put("min", sorted.first())
        put("median", sorted[sorted.size / 2])
        put("max", sorted.last())
        put("count", sorted.size)
    }
}
