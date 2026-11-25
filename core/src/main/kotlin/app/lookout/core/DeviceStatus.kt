package app.lookout.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException

/** Mirrors Android's PowerManager thermal status levels. */
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

@Serializable
data class DeviceState(
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
    val batteryHealth: String? = null,
    val thermal: Thermal = Thermal.NONE,
    val wifiRssi: Int? = null,
    val ip: String? = null,
) {
    val batteryLow: Boolean get() = batteryPct != null && batteryPct < LOW_BATTERY_PCT && charging != true
    val hot: Boolean get() = temperatureC != null && temperatureC >= HOT_TEMPERATURE_C

    /** The device fields of frame metadata: all of them with [all], else only those past a warning level. */
    fun metaFields(all: Boolean): JsonObject = buildJsonObject {
        if (all || batteryLow) {
            batteryPct?.let { put("battery_pct", it) }
            charging?.let { put("charging", it) }
        }
        if (all || hot) temperatureC?.let { put("temperature_c", it) }
        if (all || thermal >= WARN_THERMAL) put("thermal", thermal.name.lowercase())
    }

    companion object {
        const val LOW_BATTERY_PCT = 20
        const val HOT_TEMPERATURE_C = 40.0
        val WARN_THERMAL = Thermal.MODERATE
    }
}

fun interface DeviceStatus {
    fun read(): DeviceState
}

/** Parses an ISO 8601 time or a relative time such as -20m, -90s, -2h or "now". */
fun parseTime(text: String, nowMs: Long): Long {
    val t = text.trim()
    if (t == "now") return nowMs
    REL.matchEntire(t)?.let { m ->
        val n = m.groupValues[2].toLong()
        val unit = when (m.groupValues[3]) {
            "ms" -> 1L
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            else -> 86_400_000L
        }
        val sign = if (m.groupValues[1] == "+") 1 else -1
        return nowMs + sign * n * unit
    }
    return parseIso(t) ?: throw LookoutError("invalid_time", 400, "Time must be ISO 8601 or relative like -20m, got '$text'")
}

/** Parses an ISO 8601 time, or returns null. */
fun parseIso(text: String): Long? = try {
    Instant.parse(text.trim()).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

private val REL = Regex("([-+])([0-9]{1,9})(ms|s|m|h|d)")

private val ISO_MS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(java.time.ZoneOffset.UTC)

/** ISO 8601 in UTC with milliseconds. */
fun isoTime(ms: Long): String = ISO_MS.format(Instant.ofEpochMilli(ms))

private val CLOCK_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault())

/** Local wall-clock time, for labels burned into images. */
fun clockTime(ms: Long): String = CLOCK_TIME.format(Instant.ofEpochMilli(ms))
