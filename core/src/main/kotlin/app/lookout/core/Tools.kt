package app.lookout.core

import app.lookout.core.Capability.Kind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.max
import kotlin.math.min

/** A resolved region: a registered ROI or an ad-hoc rect. */
class RoiRef(val name: String?, val rect: NRect, val defaults: RoiDefaults?) {
    fun label(): String = name ?: "${fmt(rect.x)},${fmt(rect.y)},${fmt(rect.w)},${fmt(rect.h)}"

    fun json(): JsonElement = if (name != null) JsonPrimitive(name) else LookoutJson.encodeToJsonElement(rect)

    private fun fmt(d: Double) = "%.3f".format(d)
}

/** The capabilities. Each one is exposed identically as an MCP tool and a REST route. */
class Tools(private val core: LookoutCore) {
    private val ops get() = core.ops
    private val store get() = core.store
    private val comp get() = core.compositor
    private val clock get() = core.clock

    private val roiParam = Param("roi", PType.STRING, "Name of a saved ROI, or an ad-hoc rect \"x,y,w,h\" in normalized 0..1 coordinates.")
    private val widthParam = Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568).")
    private val gridParam = Param("grid", PType.STRING, "Draw a labelled grid, e.g. \"8x6\" (columns A.., rows 1..). \"true\" means 8x6.")
    private val stampParam = Param("stamp", PType.BOOLEAN, "Burn the time and frame_id into a footer band.")
    private val qualityParam = Param("quality", PType.INTEGER, "JPEG quality 10..100.")
    private val lensParam = Param("lens", PType.STRING, "Lens id from describe. Applies for this call only.")
    private val focusSchema = buildJsonObject {
        put("type", "object")
        put("description", "Focus: {mode:\"auto\"} | {mode:\"manual\", distance:<diopters>} | {mode:\"point\", x, y}. A string \"auto\", \"manual:2.5\" or \"point:0.5,0.4\" also works.")
        putJsonObject("properties") {
            putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add("auto"); add("manual"); add("point") } }
            putJsonObject("distance") { put("type", "number") }
            putJsonObject("x") { put("type", "number") }
            putJsonObject("y") { put("type", "number") }
        }
    }
    private val rectSchema = buildJsonObject {
        put("type", "object")
        put("description", "Normalized rect {x,y,w,h}, 0..1 from the top-left of the full frame.")
        putJsonObject("properties") { for (k in listOf("x", "y", "w", "h")) putJsonObject(k) { put("type", "number") } }
        putJsonArray("required") { listOf("x", "y", "w", "h").forEach { add(it) } }
    }

    val describe = Capability(
        "describe",
        "Orient yourself in one call: lenses and their capabilities, current camera settings, saved ROIs and baselines, " +
            "the history buffer span, battery and thermal state, and the API version. Cheap; call it first.",
        emptyList(), Kind.JSON,
    ) { _, _ -> CapResult(describeJson()) }

    val status = Capability(
        "status",
        "Battery, charging, temperature, thermal status, uptime, Wi-Fi RSSI and camera state.",
        emptyList(), Kind.JSON,
    ) { _, _ -> CapResult(statusJson()) }

    val look = Capability(
        "look",
        "Get the current view. Cheap and fast (about 1024 px, under 1 s when warm); prefer it over capture. " +
            "fresh=true (default) guarantees the frame was taken after your call arrived, so it is safe right after you act. " +
            "Use settle_ms to wait until the scene stops moving and exposure has converged. " +
            "Use roi to crop to a saved region; small regions come from a full-resolution still for real detail. " +
            "Use grid to get cell labels you can name in roi definitions.",
        listOf(
            roiParam, widthParam, gridParam, stampParam, qualityParam, lensParam,
            Param("fresh", PType.BOOLEAN, "Only return a frame captured after this request arrived. Default true."),
            Param("settle_ms", PType.INTEGER, "Wait until motion has stayed below threshold for this many ms and AE/AF converged."),
            Param("after_frame_id", PType.INTEGER, "Return the first frame newer than this id."),
            Param("after", PType.STRING, "Return the first frame captured after this time (ISO 8601 or relative like -5s)."),
        ),
        Kind.IMAGE,
    ) { a, ctx -> look(a, ctx, freshDefault = true) }

    val capture = Capability(
        "capture",
        "Take a full-resolution still (slower and larger than look). Use it to read small text such as part markings, " +
            "a display or a multimeter, ideally with roi. Lens, zoom, focus, torch and exposure apply for this shot only. " +
            "save_as stores the full frame as a named baseline for diff.",
        listOf(
            roiParam, lensParam, gridParam, stampParam, qualityParam,
            Param("width", PType.INTEGER, "Long edge of the returned image in px. Default and max 1568."),
            Param("zoom", PType.NUMBER, "Zoom ratio for this shot."),
            Param("focus", PType.OBJECT, "Focus for this shot.", schema = focusSchema),
            Param("torch", PType.BOOLEAN, "Torch on for this shot."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index for this shot."),
            Param("save_as", PType.STRING, "Save the full still as a baseline with this name."),
        ),
        Kind.IMAGE,
    ) { a, ctx -> capture(a, ctx) }

    val camera = Capability(
        "camera",
        "Change persistent camera settings: lens, zoom, focus, AE/AWB lock, torch, and power mode " +
            "(warm keeps history and instant fresh frames; cold opens the camera per request; auto stays warm while needed). " +
            "Returns the settings now in effect. Call with no arguments to read them.",
        listOf(
            lensParam.copy(description = "Lens id from describe."),
            Param("zoom", PType.NUMBER, "Zoom ratio."),
            Param("focus", PType.OBJECT, "Focus setting.", schema = focusSchema),
            Param("ae_lock", PType.BOOLEAN, "Lock auto exposure."),
            Param("awb_lock", PType.BOOLEAN, "Lock auto white balance."),
            Param("torch", PType.BOOLEAN, "Torch on or off."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index."),
            Param("mode", PType.STRING, "Power mode. \"default\" means warm while charging, cold on battery.", enum = listOf("warm", "cold", "auto", "default")),
        ),
        Kind.JSON,
    ) { a, _ -> camera(a) }

    val waitFor = Capability(
        "wait_for",
        "Block until a visual condition happens, instead of polling. change: the view (or roi) differs from the moment you called by more than threshold. " +
            "motion: something moves. still: nothing moved for for_ms (e.g. a print finished). human_ack: the person taps Done on the phone. " +
            "On a match you get the before and after frames side by side with changed areas outlined, and the score. " +
            "On timeout you get matched:false and the latest frame, never an error. Keep timeout_ms at or under 120000 " +
            "and call it again in a loop for long jobs.",
        listOf(
            Param("condition", PType.STRING, "What to wait for.", required = true, enum = listOf("change", "motion", "still", "human_ack")),
            roiParam,
            Param("threshold", PType.NUMBER, "Fraction of pixels (0..1) that must change. Defaults: change 0.02, motion 0.01."),
            Param("for_ms", PType.INTEGER, "For still: how long the scene must stay still. Default 10000."),
            Param("timeout_ms", PType.INTEGER, "Give up after this many ms. Default 120000, max 600000."),
            Param("message", PType.STRING, "For human_ack: the text shown on the phone."),
        ),
        Kind.JSON,
    ) { a, ctx -> waitFor(a, ctx) }

    val history = Capability(
        "history",
        "See what happened while you were not watching: one contact-sheet image of evenly spaced frames from the ring buffer " +
            "(default 1 frame every 2 s, about 30 minutes), each tile labelled with its time. events_only picks tiles at detected motion instead. " +
            "Needs warm mode.",
        listOf(
            Param("since", PType.STRING, "Start of the window: ISO 8601 or relative like -20m. Default -20m."),
            Param("until", PType.STRING, "End of the window. Default now."),
            Param("tiles", PType.INTEGER, "Number of tiles, 1..36. Default 12."),
            roiParam,
            Param("events_only", PType.BOOLEAN, "Pick tiles at detected motion and stillness events."),
        ),
        Kind.IMAGE,
    ) { a, ctx -> history(a, ctx) }

    val diff = Capability(
        "diff",
        "Compare two frames, or a saved baseline against now. Returns a side-by-side image with changed regions boxed, " +
            "plus {score, changed, regions}. a and b are frame ids or baseline names; b defaults to a fresh frame.",
        listOf(
            Param("a", PType.STRING, "Frame id or baseline name.", required = true),
            Param("b", PType.STRING, "Frame id or baseline name. Default: a fresh frame now."),
            roiParam,
            Param("threshold", PType.NUMBER, "Score above which changed is true. Default 0.02."),
        ),
        Kind.JSON,
    ) { a, ctx -> diff(a, ctx) }

    val roi = Capability(
        "roi",
        "Manage named regions of interest. set takes a rect or grid cells (from/to, e.g. from=C2 to=E4 on the 8x6 grid that " +
            "look draws on the full frame), plus optional per-ROI camera defaults. list returns all. delete removes one.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("set", "list", "delete")),
            Param("name", PType.STRING, "ROI name: lowercase letters, digits, - and _."),
            Param("rect", PType.OBJECT, "The region.", schema = rectSchema),
            Param("from", PType.STRING, "First grid cell, e.g. C2."),
            Param("to", PType.STRING, "Last grid cell, e.g. E4."),
            Param("grid", PType.STRING, "Grid for from/to. Default 8x6."),
            Param("defaults", PType.OBJECT, "Camera defaults when this ROI is used: {lens, zoom, focus, torch}.", schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("lens") { put("type", "string") }
                    putJsonObject("zoom") { put("type", "number") }
                    put("focus", focusSchema)
                    putJsonObject("torch") { put("type", "boolean") }
                }
            }),
        ),
        Kind.JSON,
    ) { a, _ -> roiTool(a) }

    val baseline = Capability(
        "baseline",
        "Manage reference frames for diff. save takes a full-resolution still now and stores it under name (e.g. bed-empty). " +
            "list returns all. delete removes one.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("save", "list", "delete")),
            Param("name", PType.STRING, "Baseline name."),
            roiParam.copy(description = "ROI to remember with the baseline; diff uses it when you give none."),
        ),
        Kind.JSON,
    ) { a, ctx -> baselineTool(a, ctx) }

    val say = Capability(
        "say",
        "Speak text aloud on the phone (text to speech), for the person near the camera.",
        listOf(Param("text", PType.STRING, "What to say.", required = true)),
        Kind.JSON,
    ) { a, _ ->
        val text = a.string("text")!!
        val spoken = core.human.say(text)
        core.events.add("say", buildJsonObject { put("text", text) })
        CapResult(buildJsonObject { put("spoken", spoken) })
    }

    val ask = Capability(
        "ask",
        "Show a full-screen message on the phone with optional buttons and wait for a tap. Returns {answered, choice}. " +
            "Combine with wait_for(change) to ask the person to do something and then see it happen.",
        listOf(
            Param("text", PType.STRING, "The message.", required = true),
            Param("buttons", PType.STRING_ARRAY, "Button labels. Default [\"OK\"]."),
            Param("timeout_ms", PType.INTEGER, "Give up after this many ms. Default 60000, max 600000."),
        ),
        Kind.JSON,
    ) { a, _ ->
        val text = a.string("text")!!
        val buttons = a.strings("buttons") ?: listOf("OK")
        if (buttons.size > 6) throw LookoutError("invalid_param", 400, "At most 6 buttons")
        val timeout = (a.long("timeout_ms") ?: 60_000L).coerceIn(1_000L, MAX_WAIT_MS)
        val choice = core.human.ask(text, buttons, timeout)
        core.events.add("ask", buildJsonObject {
            put("text", text)
            put("answered", choice != null)
            choice?.let { put("choice", it) }
        })
        CapResult(buildJsonObject {
            put("answered", choice != null)
            choice?.let { put("choice", it) }
        })
    }

    /** The MCP tools, in the order agents see them. status is REST-only; describe covers it. */
    val mcpTools: List<Capability> = listOf(describe, look, capture, waitFor, history, diff, roi, baseline, camera, say, ask)

    val all: List<Capability> = mcpTools + status

    // ---- handlers ----

    suspend fun look(a: Args, ctx: CallContext, freshDefault: Boolean): CapResult {
        guardImage()
        val roi = resolveRoi(a.raw("roi"))
        val width = (a.int("width") ?: core.settings.value.defaultWidth).coerceIn(16, Compositor.MAX_EDGE)
        val over = (roi?.defaults?.toSettings() ?: CameraSettings()).merge(CameraSettings(lens = a.string("lens")))
        val fresh = a.bool("fresh") ?: freshDefault
        val afterId = a.long("after_frame_id")
        val afterMs = a.string("after")?.let { parseTime(it, clock.now()) }
        val settleMs = a.long("settle_ms")?.coerceIn(0, MAX_WAIT_MS)
        return core.session.use {
            withSettings(over) {
                val got = obtainFrame(fresh || !over.isEmpty, afterId, afterMs, settleMs)
                var frame = got.frame
                var from = "analysis"
                if (roi != null) {
                    val px = roi.rect.toPixels(frame.image.width, frame.image.height)
                    if (max(px.w, px.h) < width * 0.75) {
                        frame = store.registerStill(core.source.captureStill())
                        from = "still"
                    }
                }
                imageResult("look", ctx, frame, roi, width, a, from, got.settled)
            }
        }
    }

    private suspend fun capture(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val roi = resolveRoi(a.raw("roi"))
        val width = (a.int("width") ?: Compositor.MAX_EDGE).coerceIn(16, Compositor.MAX_EDGE)
        val over = (roi?.defaults?.toSettings() ?: CameraSettings()).merge(
            CameraSettings(lens = a.string("lens"), zoom = a.double("zoom"), focus = a.focus("focus"), torch = a.bool("torch"), exposureComp = a.int("exposure_comp")),
        )
        val saveAs = a.string("save_as")?.let { checkName(it, "Baseline") }
        return core.session.use {
            withSettings(over) {
                val still = store.registerStill(core.source.captureStill())
                if (saveAs != null) saveBaseline(saveAs, still, roi)
                if (saveAs != null) core.events.add("baseline", buildJsonObject { put("saved", saveAs) })
                imageResult("capture", ctx, still, roi, width, a, "still", null)
            }
        }
    }

    private suspend fun camera(a: Args): CapResult {
        a.string("mode")?.let { m ->
            val mode = if (m == "default") null else PowerMode.parse(m)
            core.updateSettings { it.copy(powerMode = mode) }
        }
        val s = CameraSettings(
            lens = a.string("lens"), zoom = a.double("zoom"), focus = a.focus("focus"),
            aeLock = a.bool("ae_lock"), awbLock = a.bool("awb_lock"), torch = a.bool("torch"), exposureComp = a.int("exposure_comp"),
        )
        if (!s.isEmpty) {
            core.cameraSettings = core.source.apply(s)
            core.events.add("camera", buildJsonObject { put("applied", LookoutJson.encodeToJsonElement(s)) })
        }
        return CapResult(buildJsonObject {
            put("settings", LookoutJson.encodeToJsonElement(core.cameraSettings))
            put("mode", core.settings.value.powerMode?.wire ?: "default")
            put("effective_mode", core.session.effectiveMode().wire)
        })
    }

    private suspend fun waitFor(a: Args, ctx: CallContext): CapResult {
        val condition = a.string("condition")!!
        val roi = resolveRoi(a.raw("roi"))
        val sens = core.settings.value.sensitivity
        val threshold = (a.double("threshold") ?: (if (condition == "motion") LookoutCore.MOTION_THRESHOLD / sens else LookoutCore.CHANGE_THRESHOLD / sens)).coerceIn(0.0, 1.0)
        val forMs = (a.long("for_ms") ?: 10_000L).coerceIn(0, MAX_WAIT_MS)
        val timeout = (a.long("timeout_ms") ?: 120_000L).coerceIn(0, MAX_WAIT_MS)
        if (condition != "human_ack" && core.session.thermalLimited) {
            throw LookoutError("thermal", 503, "The phone is too hot for camera waits. Try again later.")
        }
        if (core.activeWaits.incrementAndGet() > LookoutCore.MAX_WAITS) {
            core.activeWaits.decrementAndGet()
            throw LookoutError("too_many_waits", 429, "At most ${LookoutCore.MAX_WAITS} wait_for calls can run at once")
        }
        try {
            val start = clock.now()
            return core.session.hold {
                val before = store.await(FRAME_TIMEOUT_MS) { it.capturedAtMs >= start }
                    ?: throw LookoutError("no_frame", 504, "No frame arrived from the camera")
                val remaining = max(0L, timeout - (clock.now() - start))
                val match: WaitMatch? = when (condition) {
                    "human_ack" -> {
                        val choice = core.human.ask(a.string("message") ?: "Tap Done when you have finished.", listOf("Done"), max(1_000L, remaining))
                        if (choice == null) null else WaitMatch(store.latest.value ?: before, ChangeResult(0.0, emptyList()))
                    }
                    else -> withTimeoutOrNull(remaining) { watch(condition, before, roi, threshold, forMs) }
                }
                waitResult(condition, roi, threshold, start, before, match, ctx)
            }
        } finally {
            core.activeWaits.decrementAndGet()
        }
    }

    private class WaitMatch(val frame: Frame, val change: ChangeResult)

    private suspend fun watch(condition: String, before: Frame, roi: RoiRef?, threshold: Double, forMs: Long): WaitMatch {
        val rect = roi?.rect ?: NRect.FULL
        val detector = core.detector
        val reference = detector.thumb(before.image, rect)
        var previous = reference
        var lastMotion = before.capturedAtMs
        var lastChange = ChangeResult(0.0, emptyList())
        val hit = store.frames.first { af ->
            val thumb = detector.thumb(af.frame.image, rect)
            when (condition) {
                "change" -> {
                    lastChange = detector.compare(reference, thumb)
                    lastChange.score > threshold
                }
                "motion" -> {
                    lastChange = detector.compare(previous, thumb)
                    previous = thumb
                    lastChange.score > threshold
                }
                else -> {
                    val step = detector.compare(previous, thumb)
                    previous = thumb
                    if (step.score > threshold) lastMotion = af.frame.capturedAtMs
                    lastChange = detector.compare(reference, thumb)
                    af.frame.capturedAtMs - lastMotion >= forMs
                }
            }
        }
        core.events.add(condition, buildJsonObject {
            put("frame_id", hit.frame.id)
            put("score", lastChange.score)
            roi?.let { put("roi", it.json()) }
        })
        return WaitMatch(hit.frame, lastChange)
    }

    private fun waitResult(condition: String, roi: RoiRef?, threshold: Double, start: Long, before: Frame, match: WaitMatch?, ctx: CallContext): CapResult {
        val rect = roi?.rect
        val latest = match?.frame ?: store.latest.value ?: before
        val image = if (match != null) {
            comp.sideBySide(
                comp.crop(before.image, rect), "before ${clockTime(before.capturedAtMs)} #${before.id}",
                comp.crop(match.frame.image, rect), "after ${clockTime(match.frame.capturedAtMs)} #${match.frame.id}",
                match.change.regions,
            )
        } else {
            comp.fit(comp.crop(latest.image, rect), Compositor.DEFAULT_EDGE)
        }
        val jpeg = ops.encodeJpeg(image, core.settings.value.jpegQuality)
        val id = core.composites.put(jpeg)
        core.recordServed("wait_for", ctx, jpeg.size, roi?.label())
        val json = buildJsonObject {
            put("matched", match != null)
            put("condition", condition)
            roi?.let { put("roi", it.json()) }
            put("threshold", threshold)
            put("waited_ms", clock.now() - start)
            put("before_frame_id", before.id)
            put("after_frame_id", latest.id)
            if (match != null) {
                put("score", match.change.score)
                put("regions", regionsJson(match.change.regions, rect))
                put("event", buildJsonObject {
                    put("type", condition)
                    put("at", isoTime(match.frame.capturedAtMs))
                })
            }
            put("image_id", id)
            put("image_url", "/v1/frames/$id.jpg")
        }
        return CapResult(json, ImageOut(jpeg, id))
    }

    private suspend fun history(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val now = clock.now()
        val since = parseTime(a.string("since") ?: "-20m", now)
        val until = a.string("until")?.let { parseTime(it, now) } ?: now
        if (until <= since) throw LookoutError("invalid_time", 400, "until must be after since")
        val n = (a.int("tiles") ?: 12).coerceIn(1, 36)
        val roi = resolveRoi(a.raw("roi"))
        val frames = store.buffered().filter { it.capturedAtMs in since..until }
        if (frames.isEmpty()) {
            throw LookoutError("no_history", 404, "No buffered frames in that window. History fills only in warm mode (camera mode=warm).")
        }
        val eventsOnly = a.bool("events_only") == true
        val targets: List<Long> = if (eventsOnly) {
            val ev = core.events.since(0, setOf("motion", "still", "change")).filter { it.ts in since..until }.map { it.ts }
            if (ev.size <= n) ev else List(n) { ev[it * ev.size / n] }
        } else {
            val from = max(since, frames.first().capturedAtMs)
            val to = min(until, frames.last().capturedAtMs)
            List(n) { i -> from + ((i + 0.5) * (to - from) / n).toLong() }
        }
        val picked = targets.map { t -> frames.minBy { kotlin.math.abs(it.capturedAtMs - t) } }.distinctBy { it.id }
        if (picked.isEmpty()) throw LookoutError("no_history", 404, "No motion events in that window. Try without events_only.")
        val tiles = picked.map { comp.crop(ops.decode(it.jpeg), roi?.rect) to "${clockTime(it.capturedAtMs)} #${it.id}" }
        val sheet = comp.contactSheet(tiles)
        val jpeg = ops.encodeJpeg(sheet, core.settings.value.jpegQuality)
        val id = core.composites.put(jpeg)
        core.recordServed("history", ctx, jpeg.size, roi?.label())
        return CapResult(buildJsonObject {
            put("since", isoTime(since))
            put("until", isoTime(until))
            put("events_only", eventsOnly)
            roi?.let { put("roi", it.json()) }
            putJsonArray("tiles") {
                picked.forEach { f -> add(buildJsonObject { put("frame_id", f.id); put("captured_at", isoTime(f.capturedAtMs)) }) }
            }
            put("width", sheet.width)
            put("height", sheet.height)
            put("image_id", id)
        }, ImageOut(jpeg, id))
    }

    private suspend fun diff(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val aKey = a.string("a")!!
        val bKey = a.string("b")
        val threshold = a.double("threshold") ?: (LookoutCore.CHANGE_THRESHOLD / core.settings.value.sensitivity)
        val roi = resolveRoi(a.raw("roi")) ?: core.baselines.get(aKey)?.roi?.let { resolveRoi(JsonPrimitive(it)) }
        val left = resolveImage(aKey)
        val right = if (bKey != null) resolveImage(bKey) else core.session.use {
            val f = obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
            ResolvedImage(f.image, "now #${f.id}", f.id, f.capturedAtMs)
        }
        val rect = roi?.rect
        val change = core.detector.compare(core.detector.thumb(left.image, rect ?: NRect.FULL), core.detector.thumb(right.image, rect ?: NRect.FULL))
        val image = comp.sideBySide(comp.crop(left.image, rect), "a: ${left.label}", comp.crop(right.image, rect), "b: ${right.label}", change.regions)
        val jpeg = ops.encodeJpeg(image, core.settings.value.jpegQuality)
        val id = core.composites.put(jpeg)
        core.recordServed("diff", ctx, jpeg.size, roi?.label())
        return CapResult(buildJsonObject {
            put("score", change.score)
            put("changed", change.score > threshold)
            put("threshold", threshold)
            put("regions", regionsJson(change.regions, rect))
            put("a", left.label)
            put("b", right.label)
            roi?.let { put("roi", it.json()) }
            put("image_id", id)
            put("image_url", "/v1/frames/$id.jpg")
        }, ImageOut(jpeg, id))
    }

    private fun roiTool(a: Args): CapResult = when (a.string("action")) {
        "list" -> CapResult(buildJsonObject { put("rois", LookoutJson.encodeToJsonElement(core.rois.list())) })
        "delete" -> {
            val name = a.string("name") ?: throw LookoutError("missing_param", 400, "delete needs a name")
            if (!core.rois.delete(name)) throw LookoutError("roi_not_found", 404, "No ROI named '$name'")
            CapResult(buildJsonObject { put("deleted", name) })
        }
        else -> {
            val name = a.string("name") ?: throw LookoutError("missing_param", 400, "set needs a name")
            val from = a.string("from")
            val to = a.string("to")
            val rect = a.rect("rect") ?: when {
                from != null -> GridSpec.parse(a.string("grid") ?: GridSpec.DEFAULT.toString()).span(from, to ?: from)
                else -> throw LookoutError("missing_param", 400, "set needs rect or from/to grid cells")
            }
            val defaults = a.obj("defaults")?.let { LookoutJson.decodeFromJsonElement(RoiDefaults.serializer(), it) }
            val saved = core.rois.put(Roi(name, rect, defaults))
            core.events.add("roi", buildJsonObject { put("set", name) })
            CapResult(buildJsonObject { put("roi", LookoutJson.encodeToJsonElement(saved)) })
        }
    }

    private suspend fun baselineTool(a: Args, ctx: CallContext): CapResult = when (a.string("action")) {
        "list" -> CapResult(buildJsonObject { put("baselines", LookoutJson.encodeToJsonElement(core.baselines.list())) })
        "delete" -> {
            val name = a.string("name") ?: throw LookoutError("missing_param", 400, "delete needs a name")
            if (!core.baselines.delete(name)) throw LookoutError("baseline_not_found", 404, "No baseline named '$name'")
            CapResult(buildJsonObject { put("deleted", name) })
        }
        else -> {
            guardImage()
            val name = checkName(a.string("name") ?: throw LookoutError("missing_param", 400, "save needs a name"), "Baseline")
            val roi = resolveRoi(a.raw("roi"))
            core.session.use {
                val still = store.registerStill(core.source.captureStill())
                val info = saveBaseline(name, still, roi)
                val preview = ops.encodeJpeg(comp.fit(comp.crop(still.image, roi?.rect), Compositor.DEFAULT_EDGE), core.settings.value.jpegQuality)
                core.recordServed("baseline", ctx, preview.size, roi?.label())
                CapResult(buildJsonObject { put("baseline", LookoutJson.encodeToJsonElement(info)) }, ImageOut(preview))
            }
        }
    }

    // ---- shared helpers ----

    class Obtained(val frame: Frame, val settled: Boolean?)

    /** Waits for a frame that meets the freshness rules, then optionally for the scene to settle. */
    suspend fun obtainFrame(fresh: Boolean, afterId: Long?, afterMs: Long?, settleMs: Long?): Obtained {
        val start = clock.now()
        val ok: (Frame) -> Boolean = { f ->
            (!fresh || f.capturedAtMs >= start) && (afterId == null || f.id > afterId) && (afterMs == null || f.capturedAtMs > afterMs)
        }
        // A camera that was already streaming has settled exposure; only a fresh open waits for AE.
        val warm = store.latest.value?.let { start - it.capturedAtMs < WARM_WINDOW_MS } == true
        val frame = (if (warm) null else store.await(AE_WAIT_MS) { ok(it) && it.camera.aeConverged })
            ?: store.await(FRAME_TIMEOUT_MS, ok)
            ?: throw LookoutError("no_frame", 504, "No matching frame arrived from the camera in time")
        if (settleMs == null || settleMs <= 0) return Obtained(frame, null)
        val deadline = clock.now() + settleMs + SETTLE_EXTRA_MS
        var latest = frame
        while (true) {
            val quietSince = max(store.lastMotionAtMs, if (fresh) start else 0L)
            if (latest.capturedAtMs - quietSince >= settleMs && latest.camera.aeConverged && latest.camera.afConverged) return Obtained(latest, true)
            if (clock.now() > deadline) return Obtained(latest, false)
            val prevId = latest.id
            latest = store.await(FRAME_TIMEOUT_MS) { it.id > prevId } ?: return Obtained(latest, false)
        }
    }

    private fun imageResult(tool: String, ctx: CallContext, frame: Frame, roi: RoiRef?, width: Int, a: Args, from: String, settled: Boolean?): CapResult {
        var img = comp.fit(comp.crop(frame.image, roi?.rect), width)
        parseGrid(a.string("grid"))?.let { img = comp.grid(img, it) }
        if (a.bool("stamp") == true) img = comp.stamp(img, "${clockTime(frame.capturedAtMs)}  #${frame.id}  ${roi?.label() ?: ""}".trim())
        val quality = (a.int("quality") ?: core.settings.value.jpegQuality).coerceIn(10, 100)
        val jpeg = ops.encodeJpeg(img, quality)
        core.recordServed(tool, ctx, jpeg.size, roi?.label())
        val meta = frameMeta(frame, img, roi, from).let { m ->
            if (settled == null) m else JsonObject(m + ("settled" to JsonPrimitive(settled)))
        }
        return CapResult(meta, ImageOut(jpeg))
    }

    fun frameMeta(frame: Frame, out: Img, roi: RoiRef?, from: String): JsonObject {
        val d = core.device.read()
        val c = frame.camera
        return buildJsonObject {
            put("frame_id", frame.id)
            put("captured_at", isoTime(frame.capturedAtMs))
            put("age_ms", clock.now() - frame.capturedAtMs)
            put("source", from)
            put("lens", c.lens)
            put("zoom", c.zoom)
            c.focusDistance?.let { put("focus_distance", it) }
            c.exposureTimeNs?.let { put("exposure_time_ns", it) }
            c.iso?.let { put("iso", it) }
            c.whiteBalance?.let { put("white_balance", it) }
            put("ae_converged", c.aeConverged)
            roi?.let { put("roi", it.json()) }
            put("width", out.width)
            put("height", out.height)
            put("brightness", "%.3f".format(frame.brightness).toDouble())
            d.batteryPct?.let { put("battery_pct", it) }
            d.charging?.let { put("charging", it) }
            d.temperatureC?.let { put("temperature_c", it) }
            put("thermal", d.thermal.name.lowercase())
        }
    }

    private fun parseGrid(v: String?): GridSpec? = when (v?.lowercase()) {
        null, "", "false", "0", "no" -> null
        "true", "1", "yes" -> GridSpec.DEFAULT
        else -> GridSpec.parse(v)
    }

    fun resolveRoi(raw: JsonElement?): RoiRef? = when (raw) {
        null -> null
        is JsonObject, is JsonArray -> RoiRef(null, Args(buildJsonObject { put("r", raw) }).rect("r")!!, null)
        is JsonPrimitive -> {
            val s = raw.content.trim()
            if (s.isEmpty()) null
            else if (s.contains(",")) RoiRef(null, Args(buildJsonObject { put("r", s) }).rect("r")!!, null)
            else core.rois.require(s).let { RoiRef(it.name, it.rect, it.defaults) }
        }
    }

    private class ResolvedImage(val image: Img, val label: String, val frameId: Long?, val capturedAtMs: Long?)

    private fun resolveImage(key: String): ResolvedImage {
        key.toLongOrNull()?.let { id ->
            store.latest.value?.takeIf { it.id == id }?.let { return ResolvedImage(it.image, "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs) }
            store.stillById(id)?.let { return ResolvedImage(it.image, "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs) }
            store.bufferedById(id)?.let { return ResolvedImage(ops.decode(it.jpeg), "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs) }
            throw LookoutError("frame_not_found", 404, "Frame $id is no longer in memory")
        }
        val info = core.baselines.get(key) ?: throw LookoutError("baseline_not_found", 404, "No frame id or baseline named '$key'")
        val jpeg = core.baselines.jpeg(key) ?: throw LookoutError("baseline_not_found", 404, "Baseline '$key' has no image")
        return ResolvedImage(ops.decode(jpeg), "baseline $key", info.frameId, info.createdAt)
    }

    /** A JPEG for /v1/frames/{id}.jpg: a composite (c12), a buffered or held frame (42) or a baseline name. */
    fun frameJpeg(id: String): ByteArray? {
        core.composites.get(id)?.let { return it }
        id.toLongOrNull()?.let { n ->
            store.bufferedById(n)?.let { return it.jpeg }
            val f = store.latest.value?.takeIf { it.id == n } ?: store.stillById(n) ?: return null
            return ops.encodeJpeg(comp.fit(f.image, Compositor.MAX_EDGE), core.settings.value.jpegQuality)
        }
        return core.baselines.jpeg(id)
    }

    private fun saveBaseline(name: String, still: Frame, roi: RoiRef?): BaselineInfo {
        val info = BaselineInfo(name, clock.now(), still.id, still.image.width, still.image.height, roi?.name)
        core.baselines.save(info, ops.encodeJpeg(still.image, 92))
        return info
    }

    private suspend fun <T> withSettings(over: CameraSettings, block: suspend () -> T): T {
        if (over.isEmpty) return block()
        val before = core.cameraSettings
        core.source.apply(over)
        try {
            return block()
        } finally {
            val revert = CameraSettings(
                lens = over.lens?.let { before.lens ?: core.source.lenses().firstOrNull()?.id },
                zoom = over.zoom?.let { before.zoom ?: 1.0 },
                focus = over.focus?.let { before.focus ?: FocusSetting("auto") },
                torch = over.torch?.let { before.torch ?: false },
                exposureComp = over.exposureComp?.let { before.exposureComp ?: 0 },
            )
            runCatching { core.source.apply(revert) }
        }
    }

    private fun guardImage() {
        if (core.paused.value) throw LookoutError("paused", 503, "Lookout is paused on the phone")
        if (!core.imageLimiter.tryTake()) throw LookoutError("rate_limited", 429, "More than ${LookoutCore.IMAGE_RATE.toInt()} image requests per second")
    }

    private fun regionsJson(regions: List<NRect>, within: NRect?): JsonElement = buildJsonArray {
        regions.forEach { r -> add(LookoutJson.encodeToJsonElement(if (within == null) r else r.within(within))) }
    }

    fun describeJson(): JsonObject {
        val span = store.bufferSpan()
        val s = core.settings.value
        return buildJsonObject {
            put("name", core.deviceName)
            put("api_version", LookoutCore.API_VERSION)
            put("server_version", LookoutCore.SERVER_VERSION)
            put("lenses", LookoutJson.encodeToJsonElement(core.source.lenses()))
            put("camera", LookoutJson.encodeToJsonElement(core.cameraSettings))
            put("mode", s.powerMode?.wire ?: "default")
            put("effective_mode", core.session.effectiveMode().wire)
            put("camera_open", core.session.open.value)
            put("paused", core.paused.value)
            put("rois", LookoutJson.encodeToJsonElement(core.rois.list()))
            put("baselines", LookoutJson.encodeToJsonElement(core.baselines.list()))
            putJsonObject("history") {
                put("frames", store.buffered().size)
                span?.let { (from, to) -> put("from", isoTime(from)); put("to", isoTime(to)) }
                put("interval_ms", s.bufferIntervalMs)
                put("max_minutes", s.bufferMinutes)
            }
            putJsonObject("defaults") {
                put("width", s.defaultWidth)
                put("max_width", Compositor.MAX_EDGE)
                put("quality", s.jpegQuality)
                put("grid", GridSpec.DEFAULT.toString())
            }
            put("device", LookoutJson.encodeToJsonElement(core.device.read()))
        }
    }

    fun statusJson(): JsonObject {
        val latest = store.latest.value
        return buildJsonObject {
            put("device", LookoutJson.encodeToJsonElement(core.device.read()))
            put("uptime_ms", clock.now() - core.startedAt)
            put("camera_open", core.session.open.value)
            core.session.lastError?.let { put("camera_error", it) }
            put("mode", core.session.effectiveMode().wire)
            put("paused", core.paused.value)
            put("active_waits", core.activeWaits.get())
            if (latest != null) {
                put("latest_frame_id", latest.id)
                put("latest_age_ms", clock.now() - latest.capturedAtMs)
                put("brightness", "%.3f".format(latest.brightness).toDouble())
                put("lens_covered", latest.brightness < DARK_FRAME)
            }
        }
    }

    companion object {
        const val MAX_WAIT_MS = 600_000L
        const val FRAME_TIMEOUT_MS = 5_000L
        const val AE_WAIT_MS = 1_500L
        const val WARM_WINDOW_MS = 1_500L
        const val SETTLE_EXTRA_MS = 10_000L
        const val DARK_FRAME = 0.04

        val SERVER_INSTRUCTIONS = """
            Lookout is a phone camera on the user's desk or in their room. Use it to see physical things.
            - Call describe first to learn the lenses, saved ROIs and baselines.
            - Prefer look (fast, about 1024 px). Use capture only to read small text, ideally with an roi.
            - look defaults to fresh=true, so a frame taken right after you act shows the result. Add settle_ms if things are still moving.
            - Use grid=8x6 on a full-frame look, then roi action=set with from/to cells to name a region.
            - To wait for something, call wait_for; never poll look in a loop. Keep timeout_ms at or under 120000 and call wait_for again for long jobs such as a 3D print.
            - history shows what happened while you were away as one contact sheet (warm mode only).
            - say and ask talk to the person near the phone. Pair ask or say with wait_for(change) to see them act.
        """.trimIndent()
    }
}
