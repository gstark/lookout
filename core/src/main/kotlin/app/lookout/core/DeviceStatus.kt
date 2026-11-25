package app.lookout.core

import kotlinx.serialization.Serializable
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
)

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
