package app.lookout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A point in normalized coordinates (0..1) of a frame. */
@Serializable
data class NPoint(val x: Double, val y: Double)

/**
 * A mark that the agent draws over the phone preview. [rect] is set for a box, [from] and [to] for
 * an arrow (the head is at [to]), and [to] alone for a label.
 */
@Serializable
data class PointMark(
    val shape: String,
    val color: String = "red",
    val label: String? = null,
    val rect: NRect? = null,
    val from: NPoint? = null,
    val to: NPoint? = null,
)

/** The marks on the preview now. [text] is a caption over the preview. */
class MarkSet(val id: Long, val marks: List<PointMark>, val text: String?, val deadlineMs: Long)

/** Holds the marks that the phone draws over its preview. */
class Pointer(private val clock: Clock = Clock.SYSTEM) {
    private val ids = AtomicLong(1)
    private val _current = MutableStateFlow<MarkSet?>(null)

    /** The phone draws this set until its deadline, or until [clear]. */
    val current: StateFlow<MarkSet?> = _current.asStateFlow()

    fun show(marks: List<PointMark>, text: String?, ttlMs: Long): MarkSet =
        MarkSet(ids.getAndIncrement(), marks, text, clock.now() + ttlMs).also { _current.value = it }

    /** Clears the marks. With [id], it clears them only if that set still shows. */
    fun clear(id: Long? = null) {
        if (id == null) _current.value = null else _current.update { if (it?.id == id) null else it }
    }

    /** The set that shows now, or null when there is none or it expired. */
    fun active(): MarkSet? = _current.value?.takeIf { it.deadlineMs > clock.now() }
}

/** Turns the agent's mark specs into [PointMark]s. */
object Marks {
    val SHAPES = listOf("box", "arrow", "label")
    val COLORS = mapOf(
        "red" to 0xFFFF3B30.toInt(),
        "yellow" to 0xFFFFD60A.toInt(),
        "green" to 0xFF30D158.toInt(),
        "blue" to 0xFF0A84FF.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
    )
    const val MAX_MARKS = 8
    const val MAX_LABEL = 60

    /** How far a default arrow tail sits from its head, as a fraction of the frame. */
    private const val TAIL = 0.18

    /**
     * Parses a list of specs {shape, at, from?, label?, color?}. [region] resolves a saved ROI name.
     * `at` and `from` take a grid cell (C3), a cell span (C2:E4), a point "x,y", a rect "x,y,w,h" or an ROI name.
     */
    fun parse(specs: JsonArray, grid: GridSpec, region: (String) -> NRect): List<PointMark> {
        if (specs.isEmpty()) throw bad("'marks' needs at least one mark")
        if (specs.size > MAX_MARKS) throw bad("At most $MAX_MARKS marks")
        return specs.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw bad("Mark ${i + 1} must be an object {shape, at, ...}")
            val known = setOf("shape", "at", "from", "label", "color")
            (o.keys - known).takeIf { it.isNotEmpty() }?.let { throw bad("Mark ${i + 1} has unknown key(s): ${it.joinToString()}") }
            val shape = o.str("shape") ?: "arrow"
            if (shape !in SHAPES) throw bad("Mark ${i + 1}: shape must be one of ${SHAPES.joinToString()}")
            val color = o.str("color") ?: "red"
            if (color !in COLORS) throw bad("Mark ${i + 1}: color must be one of ${COLORS.keys.joinToString()}")
            val label = o.str("label")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)
            val at = o.str("at")?.let { place(it, grid, region) } ?: throw bad("Mark ${i + 1} needs 'at'")
            when (shape) {
                "box" -> PointMark("box", color, label, rect = at.rect ?: throw bad("Mark ${i + 1}: a box needs a cell, a span, a rect or an ROI, not a point"))
                "label" -> PointMark("label", color, label ?: throw bad("Mark ${i + 1}: a label needs 'label'"), to = at.center)
                else -> {
                    val tail = o.str("from")?.let { place(it, grid, region).center } ?: defaultTail(at)
                    PointMark("arrow", color, label, from = tail, to = at.rect?.let { edgeToward(it, tail) } ?: at.center)
                }
            }
        }
    }

    /** A place on the frame: a region, or a single point when [rect] is null. */
    class Place(val center: NPoint, val rect: NRect?)

    fun place(spec: String, grid: GridSpec, region: (String) -> NRect): Place {
        val s = spec.trim()
        val nums = s.split(",").map { it.trim().toDoubleOrNull() }
        return when {
            s.contains(",") && nums.size == 2 && nums.all { it != null } -> {
                val (x, y) = nums.map { it!! }
                if (x !in 0.0..1.0 || y !in 0.0..1.0) throw bad("Point '$s' must lie inside 0..1")
                Place(NPoint(x, y), null)
            }
            s.contains(",") && nums.size == 4 && nums.all { it != null } -> regionPlace(NRect(nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!))
            s.contains(",") -> throw bad("'$s' must be a point \"x,y\" or a rect \"x,y,w,h\" in 0..1")
            s.contains(":") -> regionPlace(grid.span(s.substringBefore(":"), s.substringAfter(":")))
            CELL.matches(s.uppercase()) -> regionPlace(grid.span(s, s))
            else -> regionPlace(region(s))
        }
    }

    private fun regionPlace(r: NRect) = Place(NPoint(r.x + r.w / 2, r.y + r.h / 2), r)

    /**
     * A tail that points the arrow in from the side nearer the frame center, so it stays on screen.
     * For a region, the tail is [TAIL] from its edge, so the arrow keeps its length on a large target.
     */
    fun defaultTail(at: Place): NPoint {
        val tail = defaultTail(at.center)
        val r = at.rect ?: return tail
        val edge = edgeToward(r, tail)
        val dx = tail.x - at.center.x
        val dy = tail.y - at.center.y
        val n = hypot(dx, dy)
        return NPoint((edge.x + dx / n * TAIL).coerceIn(0.0, 1.0), (edge.y + dy / n * TAIL).coerceIn(0.0, 1.0))
    }

    private fun defaultTail(head: NPoint): NPoint {
        var dx = 0.5 - head.x
        var dy = 0.5 - head.y
        val len = hypot(dx, dy)
        if (len < 0.05) {
            dx = 1.0
            dy = -1.0
        }
        val n = hypot(dx, dy)
        return NPoint((head.x + dx / n * TAIL).coerceIn(0.0, 1.0), (head.y + dy / n * TAIL).coerceIn(0.0, 1.0))
    }

    /** Where the line from [tail] to the center of [r] crosses the edge of [r], so the arrow does not cover the target. */
    fun edgeToward(r: NRect, tail: NPoint): NPoint {
        val cx = r.x + r.w / 2
        val cy = r.y + r.h / 2
        val dx = tail.x - cx
        val dy = tail.y - cy
        if (tail.x in r.x..(r.x + r.w) && tail.y in r.y..(r.y + r.h)) return NPoint(cx, cy)
        val t = min(if (dx == 0.0) Double.MAX_VALUE else r.w / 2 / abs(dx), if (dy == 0.0) Double.MAX_VALUE else r.h / 2 / abs(dy))
        return NPoint(cx + dx * t, cy + dy * t)
    }

    /** The two ends of the arrowhead wings for an arrow from (x1, y1) to (x2, y2) in pixels. */
    fun arrowWings(x1: Double, y1: Double, x2: Double, y2: Double, length: Double): List<Pair<Double, Double>> {
        val len = max(hypot(x2 - x1, y2 - y1), 1e-6)
        val ux = (x2 - x1) / len
        val uy = (y2 - y1) / len
        val c = cos(Math.toRadians(28.0))
        val s = sin(Math.toRadians(28.0))
        return listOf(
            Pair(x2 - length * (ux * c - uy * s), y2 - length * (uy * c + ux * s)),
            Pair(x2 - length * (ux * c + uy * s), y2 - length * (uy * c - ux * s)),
        )
    }

    private val CELL = Regex("[A-Z][0-9]{1,2}")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content

    private fun bad(msg: String) = LookoutError("invalid_param", 400, msg)

    /** Parses `marks` as an array, or a JSON string of one (models often send that). */
    fun specs(e: JsonElement): JsonArray = when (e) {
        is JsonArray -> e
        is JsonPrimitive -> runCatching { LookoutJson.parseToJsonElement(e.content) as JsonArray }.getOrElse { throw bad("'marks' must be a list of marks") }
        is JsonObject -> JsonArray(listOf(e))
    }
}
