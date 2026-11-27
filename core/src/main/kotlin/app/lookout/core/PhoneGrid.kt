package app.lookout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The cell grid that the phone draws over its preview. The person turns it on with the Grid chip, and a guide with a
 * target box also shows it. The preview shows the whole frame, so its cells are the full-frame cells of [GridSpec.DEFAULT].
 */
class PhoneGrid(private val events: EventLog, private val human: HumanIO, private val sleep: Sleep, private val clock: Clock) {
    private val _since = MutableStateFlow<Long?>(null)

    /** When the person turned the grid on, or null when it is off. */
    val since: StateFlow<Long?> = _since.asStateFlow()

    fun set(on: Boolean) {
        if (on == (_since.value != null)) return
        _since.value = if (on) clock.now() else null
        events.add("grid", buildJsonObject { put("shown", on); put("grid", GridSpec.DEFAULT.toString()) })
    }

    /** The grid on the phone screen now, or null when the screen shows none. */
    fun json(): JsonObject? {
        if (sleep.asleep.value) return null
        val person = _since.value
        val guide = human.current.value?.fit != null
        if (person == null && !guide) return null
        val g = GridSpec.DEFAULT
        return buildJsonObject {
            put("shown", true)
            put("grid", g.toString())
            put("by", if (person != null) "person" else "guide")
            person?.let { put("since", isoTime(it)) }
            // A held guided shot replaces the live view, so the cells the person names are on that frame.
            human.held.value?.let { put("held_frame_id", it.frame.id) }
            put("note", note(g))
        }
    }

    companion object {
        fun note(g: GridSpec): String {
            val last = g.cellLabel(g.cols - 1, g.rows - 1)
            val c = minOf(2, g.cols - 1)
            val r = minOf(2, g.rows - 1)
            return "The person sees cells A1..$last over the whole frame: columns A..${g.columnLabel(g.cols - 1)} left to right, " +
                "rows 1..${g.rows} top to bottom. Column k (A=1) and row r cover x (k-1)/${g.cols}..k/${g.cols}, " +
                "y (r-1)/${g.rows}..r/${g.rows} in 0..1; ${g.cellLabel(c, r)} is x ${fmt(c.toDouble() / g.cols)}..${fmt((c + 1.0) / g.cols)}, " +
                "y ${fmt(r.toDouble() / g.rows)}..${fmt((r + 1.0) / g.rows)}. They are the cells of grid=$g, roi from/to and point at, also on a crop."
        }

        private fun fmt(v: Double) = "%.4f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    }
}
