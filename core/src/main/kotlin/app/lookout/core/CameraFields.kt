package app.lookout.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * The camera fields of frame metadata. Each client gets a field only when it changed since the value last
 * sent to that client, so a series of looks does not repeat the same settings. Auto exposure moves the
 * continuous values a little on every frame, so those count as changed only past a tolerance.
 */
class CameraFields {
    private val sent = object : LinkedHashMap<String, MutableMap<String, JsonPrimitive>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, JsonPrimitive>>) = size > MAX_CLIENTS
    }

    /** The fields of [c] to send to [client]. [all] sends every field. ae_converged=false is always sent, as a warning. */
    @Synchronized
    fun select(client: String, c: CameraMeta, all: Boolean): JsonObject {
        val last = sent.getOrPut(client) { HashMap() }
        val out = fields(c).filter { (k, v) -> all || (k == AE && !v.content.toBoolean()) || !same(k, last[k], v) }
        last.putAll(out)
        return JsonObject(out)
    }

    private fun fields(c: CameraMeta): Map<String, JsonPrimitive> = buildMap {
        put("lens", JsonPrimitive(c.lens))
        put("zoom", JsonPrimitive(c.zoom))
        c.focusDistance?.let { put("focus_distance", JsonPrimitive(it)) }
        c.exposureTimeNs?.let { put("exposure_time_ns", JsonPrimitive(it)) }
        c.iso?.let { put("iso", JsonPrimitive(it)) }
        c.whiteBalance?.let { put("white_balance", JsonPrimitive(it)) }
        put(AE, JsonPrimitive(c.aeConverged))
    }

    private fun same(key: String, old: JsonPrimitive?, new: JsonPrimitive): Boolean {
        if (old == null) return false
        val a = old.content.toDoubleOrNull()
        val b = new.content.toDoubleOrNull()
        if (a == null || b == null) return old == new
        return when (key) {
            "zoom" -> abs(b - a) < ZOOM_STEP
            "focus_distance" -> abs(b - a) <= FOCUS_FRACTION * abs(a)
            "exposure_time_ns", "iso" -> abs(b - a) <= EXPOSURE_FRACTION * abs(a)
            else -> old == new
        }
    }

    companion object {
        const val MAX_CLIENTS = 32
        const val ZOOM_STEP = 0.01
        const val FOCUS_FRACTION = 0.05
        /** For exposure time and ISO. */
        const val EXPOSURE_FRACTION = 0.25
        private const val AE = "ae_converged"
    }
}
