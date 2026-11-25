package app.lookout.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The changes an agent acts on, newest first. The top entry is always [LookoutCore.SERVER_VERSION]. */
object Changelog {
    class Entry(val version: String, val date: String, val lines: List<String>)

    val entries = listOf(
        Entry(
            "0.2.0", "2026-11-25",
            listOf(
                "Removed capture. Use look detail=full for a full-resolution still; REST is POST /v1/frame.",
                "Frame metadata has camera fields only when they change; verbose=true gives all of them.",
                "Device fields come only at a warning level. focus_distance is now focus_mm.",
                "history and clip tiles are arrays in the order of tile_columns; activity uses activity_columns.",
                "A recording summary leaves out fields at their usual value; quiet appears only when true.",
                "wait_for, ask and point with buttons default to timeout_ms 50000. Keep waits at or under 50 s.",
                "A dark frame returns JSON with dark=true and no image. force=true returns it anyway.",
                "inline=false leaves the image out; image_url and video_url work for 10 min with no token.",
                "New crop: a closer view of a kept frame_id or baseline, with no new shot.",
                "New flatten: a top-down view of a flat part at a fixed scale, with holes measured in mm.",
                "New scale: set mm per px from two points; look, crop, diff and track then report mm.",
                "look: sharp, settle_ms with detail=full, focus=roi, save_as, grid=ticks, per-call camera settings.",
                "look and record take guide_text and guide_image; a long wait returns state=waiting.",
                "wait_for condition=request returns a guided call; recording_done takes clip={...} and sheet.",
                "record: audio=true, and marks with at or marks=[{label, at}] also after the end.",
                "clip: mode=track (edge position per frame), mode=audio, at_marks with offset_ms, at_ms numbers.",
                "diff: image=false for JSON only; with an roi it gives dx, dy of the content.",
                "describe lists every tool in tools and shows changes since the version you last saw.",
                "MCP sends notifications/tools/list_changed on a GET /mcp event stream after an update.",
                "Results can name a better tool in hint, at most once per 10 minutes for each kind.",
            ),
        ),
        Entry("0.1.0", "2026-11-20", listOf("First release.")),
    )

    /** The entries newer than [version]. Null gives only the newest entry. */
    fun since(version: String?): List<Entry> =
        if (version == null) entries.take(1) else entries.filter { compare(it.version, version) > 0 }

    fun json(list: List<Entry>): JsonArray = JsonArray(list.map { e ->
        buildJsonObject {
            put("version", e.version)
            put("date", e.date)
            putJsonArray("lines") { e.lines.forEach { add(it) } }
        }
    })

    /** True for a version like 1.2.3. */
    fun isVersion(v: String): Boolean = VERSION.matches(v)

    /** Compares two versions like 1.2.3 by number. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toInt() }
        val y = b.split('.').map { it.toInt() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private val VERSION = Regex("\\d+(\\.\\d+){0,2}")
}
