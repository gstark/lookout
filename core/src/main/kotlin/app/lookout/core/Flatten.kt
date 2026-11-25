package app.lookout.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A top-down view of a flat part at a known scale, and the circles in it measured in mm.
 *
 * The reference is either 4 corners of a rectangle of known size ([Corners]), snapped to the rectangle's edges
 * (see [Rectify.refine]), or the 4 markers of the measuring mat ([Mat], see [MatMarkers]). A homography maps the
 * reference plane in mm to the source pixels. Each pixel of the view maps through it to the source, which is
 * pulled at full resolution or at [OVERSAMPLE] times the view scale, whichever is less, and sampled bilinearly.
 * [Circles] then measures the holes and round features in the view.
 *
 * Assumptions: the part is flat, the whole reference is in view, and the perspective is moderate.
 *
 * Lens distortion is not corrected: the map is an ideal pinhole projection. A phone camera corrects most of its
 * distortion itself; what is left (often 0.2 to 1 % of the distance from the image center, growing toward the
 * corners) shifts points between the reference corners. For an 80 × 50 mm part that fills half of the frame this
 * is about 0.1 to 0.3 mm, and less when the part sits near the center of the frame.
 *
 * On the mat, the view is of the mat plane. A part's top face lies above that plane by its thickness t, so at a
 * camera distance D it reads larger by t / D (a 1.6 mm board at 300 mm: 0.5 %).
 */
object Flatten {
    sealed interface Reference

    /** 4 [points] of a rectangle in normalized frame coordinates, in any order, of [widthMm] × [heightMm]. */
    class Corners(val points: List<NPoint>, val widthMm: Double, val heightMm: Double, val refine: Boolean = true) : Reference

    data object Mat : Reference

    class Options(
        val pxPerMm: Double? = null,
        val marginMm: Double = 0.0,
        val circles: Boolean = true,
        val minDMm: Double? = null,
        val maxDMm: Double? = null,
    )

    /** A circle in mm from the top-left reference corner, with the RMS distance of its edge points from the fit. */
    class Hole(val xMm: Double, val yMm: Double, val dMm: Double, val rmsMm: Double, val dark: Boolean)

    class Result(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val pxPerMm: Double,
        val widthMm: Double,
        val heightMm: Double,
        val marginMm: Double,
        /** The reference rectangle's top-left, top-right, bottom-right and bottom-left in the frame, 0..1. */
        val corners: List<NPoint>,
        /** For corners: how far each given corner was from the refined one, in mm. */
        val movedMm: List<Double>?,
        /** For corners with refine: the top, right, bottom and left side. */
        val sides: List<Rectify.Side>?,
        /** For the mat: the IDs of the markers found. */
        val markers: List<Int>?,
        /** For the mat with 2 or more markers: the RMS distance of the marker corners from the fitted map, in mm. */
        val fitRmsMm: Double?,
        /** In reading order, at most [MAX_CIRCLES]. Null when circles were not asked for. */
        val circles: List<Hole>?,
        val circlesFound: Int,
        val warnings: List<String>,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("reference", if (markers != null) "mat" else "corners")
            put("px_per_mm", pxPerMm)
            put("width", width)
            put("height", height)
            put("width_mm", widthMm)
            put("height_mm", heightMm)
            if (marginMm > 0) put("margin_mm", marginMm)
            putJsonArray("corners") {
                corners.forEachIndexed { i, c ->
                    addJsonObject {
                        put("at", "${round3(c.x)},${round3(c.y)}")
                        movedMm?.let { put("moved_mm", it[i]) }
                    }
                }
            }
            if (markers == null) put("x_axis", "corners[0] to corners[1]: width_mm, the side nearer horizontal in the frame")
            sides?.let { s ->
                putJsonObject("edges") {
                    SIDE_NAMES.forEachIndexed { i, name -> s[i].rmsPx.let { if (it != null) put(name, it) else put(name, "none") } }
                }
            }
            markers?.let { m -> putJsonArray("markers") { m.forEach { add(it) } } }
            fitRmsMm?.let { put("fit_rms_mm", it) }
            circles?.let { list ->
                putJsonObject("circles") {
                    putJsonArray("columns") { CIRCLE_COLUMNS.forEach { add(it) } }
                    putJsonArray("rows") {
                        list.forEach { c -> addJsonArray { add(c.xMm); add(c.yMm); add(c.dMm); add(c.rmsMm); add(if (c.dark) "dark" else "light") } }
                    }
                    if (circlesFound > list.size) put("found", circlesFound)
                }
            }
            if (warnings.isNotEmpty()) putJsonArray("warnings") { warnings.forEach { add(it) } }
        }
    }

    const val MAX_CIRCLES = 40
    /** The source is pulled at up to this many source px per view px. */
    const val OVERSAMPLE = 2.0
    /** For corners, each side is searched for its edge within this fraction of its length. */
    const val REFINE_RADIUS = 0.05
    /** The smallest default circle diameter, in view px. */
    const val MIN_D_PX = 8.0
    const val BACKGROUND = 0xFF202020.toInt()
    private val SIDE_NAMES = listOf("top", "right", "bottom", "left")
    private val CIRCLE_COLUMNS = listOf("x_mm", "y_mm", "d_mm", "rms_mm", "inside")
    /** Interior angles of a rectangle seen with moderate perspective. */
    private const val MIN_ANGLE = 60.0
    private const val MAX_ANGLE = 120.0
    /** A top side turned more than this from horizontal makes width and height ambiguous. */
    private const val AMBIGUOUS_DEG = 35.0
    /** Above this, the mat markers do not lie on one flat plane. */
    private const val MAX_FIT_RMS_MM = 0.5

    /**
     * 4 points from [specs], each "x,y" in 0..1 or a cell of the 8x6 grid. A REST query sends one comma-separated
     * list, so the numbers pair up in order.
     */
    fun parseCorners(specs: List<String>): List<NPoint> {
        val tokens = specs.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<NPoint>()
        var i = 0
        while (i < tokens.size) {
            val number = tokens[i].toDoubleOrNull() != null
            val spec = if (number) "${tokens[i]},${tokens.getOrElse(i + 1) { "" }}" else tokens[i]
            out += ScaleCalibration.parsePoint(spec, GridSpec.DEFAULT)
            i += if (number) 2 else 1
        }
        if (out.size != 4) throw LookoutError("invalid_param", 400, "corners must be 4 points, each \"x,y\" or a grid cell; got ${out.size}")
        return out
    }

    fun run(ops: ImageOps, img: Img, ref: Reference, opt: Options): Result {
        if (opt.marginMm < 0 || !opt.marginMm.isFinite()) throw LookoutError("invalid_param", 400, "margin_mm must be 0 or more")
        if (opt.pxPerMm != null && (opt.pxPerMm <= 0 || !opt.pxPerMm.isFinite())) throw LookoutError("invalid_param", 400, "px_per_mm must be above 0")
        return when (ref) {
            is Corners -> corners(ops, img, ref, opt)
            Mat -> mat(ops, img, opt)
        }
    }

    private fun rect(w: Double, h: Double) = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h))

    private fun corners(ops: ImageOps, img: Img, ref: Corners, opt: Options): Result {
        if (ref.points.size != 4) throw LookoutError("invalid_param", 400, "corners must be 4 points")
        if (ref.widthMm <= 0 || ref.heightMm <= 0) throw LookoutError("invalid_param", 400, "width_mm and height_mm must be above 0")
        val warnings = ArrayList<String>()
        val approx = Rectify.order(ref.points.map { Pt(it.x * img.width, it.y * img.height) })
        plausibility(approx, ref.widthMm, ref.heightMm, warnings)
        val plane = rect(ref.widthMm, ref.heightMm)
        val first = Homography.fit(plane, approx)
        val view = view(ref.widthMm, ref.heightMm, opt, warnings)
        val reach = REFINE_RADIUS * approx.indices.maxOf { approx[it].dist(approx[(it + 1) % 4]) } + 4
        val pulled = pull(ops, img, first, view, approx, reach)
        var corners = approx
        var sides: List<Rectify.Side>? = null
        if (ref.refine) {
            val r = Rectify.refine(pulled.luma(), approx.map(pulled.toWork::map), REFINE_RADIUS)
            val back = pulled.toSource
            corners = r.corners.map(back::map)
            sides = r.sides.map { Rectify.Side(it.found, it.rmsPx?.let { px -> px / pulled.scale }) }
            val missing = SIDE_NAMES.filterIndexed { i, _ -> !r.sides[i].found }
            if (missing.isNotEmpty()) {
                warnings += "No clear edge on the ${missing.joinToString(" and ")} side, so the corners of that side stay where you gave them."
            }
        }
        val toSource = Homography.fit(plane, corners)
        val toPlane = toSource.inverse()
        val moved = approx.mapIndexed { i, p -> toPlane.map(p).dist(plane[i]) }
        return finish(ops, img, pulled, toSource, view, opt, plane, moved, sides, null, null, warnings)
    }

    private fun mat(ops: ImageOps, img: Img, opt: Options): Result {
        val markers = MatMarkers.find(ops, img)
        if (markers.isEmpty()) {
            throw LookoutError("mat_not_found", 422, "No marker of the measuring mat was found. Check that the whole mat is in view, evenly lit and in focus.")
        }
        val warnings = ArrayList<String>()
        if (markers.size < 4) {
            val missing = (0..3) - markers.map { it.id }.toSet()
            warnings += "Only ${markers.size} of 4 mat markers found (missing ID ${missing.joinToString()}), so the scale is less accurate far from them. Check that the whole mat is in view."
        }
        val plane = markers.flatMap { MatMarkers.cornersMm(it.id) }
        val src = markers.flatMap { it.corners }
        val toSource = Homography.fit(plane, src)
        val fitRms = if (markers.size > 1) toSource.inverse().rms(src, plane) else null
        if (fitRms != null && fitRms > MAX_FIT_RMS_MM) warnings += "The markers do not fit one flat plane well (fit_rms_mm). Check that the mat lies flat."
        val frame = rect(MatMarkers.FRAME_W_MM, MatMarkers.FRAME_H_MM)
        val view = view(MatMarkers.FRAME_W_MM, MatMarkers.FRAME_H_MM, opt, warnings)
        val pulled = pull(ops, img, toSource, view, emptyList(), 2.0)
        return finish(ops, img, pulled, toSource, view, opt, frame, null, null, markers.map { it.id }, fitRms, warnings)
    }

    /** The view: [ppm] px per mm, [w] × [h] px, covering the reference rectangle and [margin] mm around it. */
    private class View(val ppm: Double, val w: Int, val h: Int, val margin: Double, val widthMm: Double, val heightMm: Double) {
        val toPlane = Homography.scaling(1 / ppm, 1 / ppm, -margin, -margin)
        val cornersMm get() = listOf(Pt(-margin, -margin), Pt(widthMm + margin, -margin), Pt(widthMm + margin, heightMm + margin), Pt(-margin, heightMm + margin))
    }

    private fun view(widthMm: Double, heightMm: Double, opt: Options, warnings: MutableList<String>): View {
        val long = max(widthMm, heightMm) + 2 * opt.marginMm
        val cap = Compositor.MAX_EDGE / long
        val want = opt.pxPerMm ?: (Compositor.DEFAULT_EDGE / long)
        if (want > cap) warnings += "px_per_mm was lowered to ${round3(cap)} so the view fits ${Compositor.MAX_EDGE} px."
        val ppm = min(want, cap)
        val w = max(1, ((widthMm + 2 * opt.marginMm) * ppm).roundToInt())
        val h = max(1, ((heightMm + 2 * opt.marginMm) * ppm).roundToInt())
        return View(ppm, w, h, opt.marginMm, widthMm, heightMm)
    }

    /** The part of [img] that the view and [extra] points cover, with [pad] source px, at no more than [OVERSAMPLE] times the view scale. */
    private fun pull(ops: ImageOps, img: Img, toSource: Homography, view: View, extra: List<Pt>, pad: Double): Pulled {
        val viewQuad = view.cornersMm.map(toSource::map)
        val box = Rectify.box(viewQuad + extra, pad, img.width, img.height)
            ?: throw LookoutError("invalid_corners", 400, "The reference lies outside the frame")
        val srcPerMm = viewQuad.indices.maxOf { i ->
            val mm = view.cornersMm[i].dist(view.cornersMm[(i + 1) % 4])
            viewQuad[i].dist(viewQuad[(i + 1) % 4]) / mm
        }
        return Rectify.pull(ops, img, box, min(1.0, OVERSAMPLE * view.ppm / srcPerMm))
    }

    private fun finish(
        ops: ImageOps, img: Img, pulled: Pulled, toSource: Homography, view: View, opt: Options, ref: List<Pt>,
        moved: List<Double>?, sides: List<Rectify.Side>?, markers: List<Int>?, fitRms: Double?, warnings: List<String>,
    ): Result {
        val argb = Rectify.warp(pulled, toSource * view.toPlane, view.w, view.h, BACKGROUND)
        var holes: List<Hole>? = null
        var found = 0
        if (opt.circles) {
            val minD = opt.minDMm ?: (MIN_D_PX / view.ppm)
            val maxD = opt.maxDMm ?: (min(view.widthMm, view.heightMm) / 2)
            if (minD <= 0 || maxD < minD) throw LookoutError("invalid_param", 400, "min_d_mm and max_d_mm must be above 0, with max_d_mm at least min_d_mm")
            val all = Circles.find(Raster.of(argb, view.w, view.h), minD * view.ppm, maxD * view.ppm)
            found = all.size
            holes = readingOrder(all.sortedBy { it.rms / it.d }.take(MAX_CIRCLES).map { c ->
                Hole(c.x / view.ppm - view.margin, c.y / view.ppm - view.margin, c.d / view.ppm, c.rms / view.ppm, c.dark)
            })
        }
        val corners = ref.map(toSource::map).map { NPoint(it.x / img.width, it.y / img.height) }
        return Result(argb, view.w, view.h, view.ppm, view.widthMm, view.heightMm, view.margin, corners, moved, sides, markers, fitRms, holes, found, warnings)
    }

    /** The view of [r] with a cross on each circle and its row number in the circle table. */
    fun draw(ops: ImageOps, r: Result): Img {
        val img = ops.image(r.width, r.height, r.argb)
        val stroke = max(1f, r.width / 800f)
        val size = max(11f, r.width / 90f)
        r.circles?.forEachIndexed { i, c ->
            val x = ((c.xMm + r.marginMm) * r.pxPerMm).roundToInt()
            val y = ((c.yMm + r.marginMm) * r.pxPerMm).roundToInt()
            val arm = max(3, (c.dMm * r.pxPerMm / 2).roundToInt())
            ops.drawLine(img, x - arm, y, x + arm, y, Colors.RED, stroke)
            ops.drawLine(img, x, y - arm, x, y + arm, Colors.RED, stroke)
            val label = "${i + 1}"
            val lx = (x + arm / 2 + 2).coerceIn(0, max(0, r.width - ops.textWidth(label, size) - 4))
            val ly = (y - arm / 2 - size.roundToInt() - 2).coerceIn(0, max(0, r.height - size.roundToInt() - 4))
            ops.drawRect(img, PixelRect(lx, ly, ops.textWidth(label, size) + 4, (size * 1.25f).roundToInt()), Colors.SHADE, 0f, fill = true)
            ops.drawText(img, label, lx + 2, ly, size, Colors.WHITE)
        }
        return img
    }

    /** [holes] top to bottom in rows, and left to right in each row. A hole starts a new row when it is below the first hole of the row by more than that hole's radius (at least 1 mm). */
    fun readingOrder(holes: List<Hole>): List<Hole> {
        val rows = ArrayList<MutableList<Hole>>()
        for (h in holes.sortedBy { it.yMm }) {
            val row = rows.lastOrNull()
            if (row != null && h.yMm - row[0].yMm <= max(1.0, row[0].dMm / 2)) row += h else rows += mutableListOf(h)
        }
        return rows.flatMap { r -> r.sortedBy { it.xMm } }
    }

    /** Warns when [q] is far from a rectangle of [w] × [h] seen with moderate perspective, or when its axes are ambiguous. */
    private fun plausibility(q: List<Pt>, w: Double, h: Double, warnings: MutableList<String>) {
        val angles = Rectify.angles(q)
        if (angles.any { it < MIN_ANGLE || it > MAX_ANGLE }) {
            warnings += "The corners are far from a rectangle seen from above (corner angles ${angles.minOf { it.roundToInt() }}° to ${angles.maxOf { it.roundToInt() }}°). Check the corners, or take the photo more from above."
        }
        val tilt = Math.toDegrees(atan2(abs(q[1].y - q[0].y), abs(q[1].x - q[0].x)))
        val across = (q[0].dist(q[1]) + q[3].dist(q[2])) / 2
        val down = (q[0].dist(q[3]) + q[1].dist(q[2])) / 2
        val seen = ln(across / down)
        val given = ln(w / h)
        if (tilt > AMBIGUOUS_DEG) {
            warnings += "The rectangle is turned ${tilt.roundToInt()}° in the frame, so width and height can swap: width_mm went to corners[0] to corners[1]."
        } else if (abs(given) > ln(1.15) && abs(seen + given) < abs(seen - given)) {
            warnings += "The sides in the photo fit width_mm and height_mm the other way round. width_mm is the side nearer horizontal in the frame; swap them if that is wrong."
        }
    }
}
