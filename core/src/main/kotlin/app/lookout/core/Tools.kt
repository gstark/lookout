package app.lookout.core

import app.lookout.core.Capability.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
import kotlin.math.roundToInt

/** A resolved region: a registered ROI or an ad-hoc rect. */
class RoiRef(val name: String?, val rect: NRect, val defaults: RoiDefaults?) {
    fun label(): String = name ?: "${fmt(rect.x)},${fmt(rect.y)},${fmt(rect.w)},${fmt(rect.h)}"

    fun json(): JsonElement = if (name != null) JsonPrimitive(name) else LookoutJson.encodeToJsonElement(rect)

    private fun fmt(d: Double) = round3(d).toString()
}

/** The capabilities. Each one is exposed identically as an MCP tool and a REST route. */
class Tools(private val core: LookoutCore) {
    private val ops get() = core.ops
    private val store get() = core.store
    private val comp get() = core.compositor
    private val clock get() = core.clock

    private val sentCamera = CameraFields()
    private val hints = Hints(core.clock)
    private val uploads = TokenBucket(UPLOAD_BURST.toDouble(), 1000.0 / UPLOAD_EVERY_MS, core.clock)

    private val roiParam = Param("roi", PType.STRING, "Name of a saved ROI, or an ad-hoc rect \"x,y,w,h\" in normalized 0..1 coordinates.")
    private val gridParam = Param("grid", PType.STRING, "A labelled cell grid (\"8x6\", or \"true\" for 8x6), or \"ticks\" for edge rulers in full-frame 0..1 coordinates.")
    private val stampParam = Param("stamp", PType.BOOLEAN, "Burn the time and frame_id into a footer band.")
    private val qualityParam = Param("quality", PType.INTEGER, "JPEG quality 10..100. Changes file size only, not token cost.")
    private val lensParam = Param("lens", PType.STRING, "Lens id from describe. Applies for this call only.")
    private val guideTextParam = Param(
        "guide_text", PType.STRING,
        "Show this instruction on the phone beside the live preview, e.g. \"Hold the board label-side up, 20 cm below the camera\". " +
            "The call then waits until the person taps Ready.",
    )
    private val guideImageParam = Param(
        "guide_image", PType.STRING,
        "An example of the shot you want, shown beside the live preview: a frame_id, an image_id (such as c12, also from upload_url in describe), " +
            "a baseline name, or a base64 JPEG or PNG or data: URL. The call then waits until the person taps Ready.",
    )
    private val marksSchema = buildJsonObject {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("shape") { put("type", "string"); putJsonArray("enum") { Marks.SHAPES.forEach { add(it) } }; put("description", "Default arrow.") }
                putJsonObject("at") { put("type", "string"); put("description", "C3, C2:E4, \"x,y\", \"x,y,w,h\" or an ROI name.") }
                putJsonObject("from") { put("type", "string"); put("description", "For an arrow: where the tail starts. Default: toward the frame center.") }
                putJsonObject("label") { put("type", "string"); put("description", "Short text beside the mark, up to ${Marks.MAX_LABEL} characters.") }
                putJsonObject("color") { put("type", "string"); putJsonArray("enum") { Marks.COLORS.keys.forEach { add(it) } }; put("description", "Default red.") }
            }
            putJsonArray("required") { add("at") }
        }
    }
    private val guideMarksParam = Param("guide_marks", PType.OBJECT, "Marks to draw on guide_image, as in point, on its 8x6 grid.", schema = marksSchema)
    private val readyTimeoutParam = Param("ready_timeout_ms", PType.INTEGER, "With a guide: how long the person has to tap Ready. Default $DEFAULT_READY_MS, max $MAX_WAIT_MS.")
    private val waitMsParam = Param(
        "wait_ms", PType.INTEGER,
        "With a guide: how long this call waits for the shot. Default $DEFAULT_GUIDE_WAIT_MS, max $MAX_GUIDE_WAIT_MS. " +
            "After that it returns state=waiting and a request_id for wait_for(condition=request).",
    )
    private val focusSchema = buildJsonObject {
        put("type", "object")
        put("description", "Focus: \"auto\", \"manual:<diopters>\" (mm = 1000 / diopters), \"point:<x>,<y>\", or \"roi\" (focus and lock on the roi of a look). Objects {mode, distance, x, y} also work.")
        putJsonObject("properties") {
            putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add("auto"); add("manual"); add("point"); add("roi") } }
            putJsonObject("distance") { put("type", "number"); put("description", "Focus distance in diopters (mm = 1000 / diopters).") }
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
    private val clipOptions = listOf(
        Param("mode", PType.STRING, "sheet (images, the default), trace, track or audio (numbers only).", enum = listOf("sheet", "trace", "track", "audio")),
        Param("from_ms", PType.INTEGER, "Start of the window, ms from the first frame. Default 0."),
        Param("to_ms", PType.INTEGER, "End of the window. Default the end of the clip."),
        Param("tiles", PType.INTEGER, "For sheet: number of tiles, as many as fit (at most $MAX_CLIP_TILES). Default $SHEET_TILES."),
        Param("pick", PType.STRING, "For sheet: even spacing or the frames with the most motion. Default even.", enum = listOf("even", "motion")),
        Param("at_ms", PType.INTEGER_ARRAY, "For sheet: exact times to show, e.g. [1200, 1250]. One time gives one large frame. Thin roi strips fit more tiles."),
        Param("at", PType.STRING_ARRAY, "For sheet: exact phone wall times to show, ISO 8601. Use instead of at_ms."),
        Param("at_marks", PType.BOOLEAN, "For sheet: one tile at each mark of the recording, labelled with it. Use instead of at_ms."),
        Param("offset_ms", PType.INTEGER, "With at_marks: show each mark at t_ms + offset_ms, e.g. 500 to see the result. Default 0."),
        roiParam.copy(description = "Crop to this region. Default: the roi of the recording. Use \"0,0,1,1\" for the full video frame."),
        Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568). Default 1568 for a sheet, 1024 for one frame."),
        Param("threshold", PType.INTEGER, "For trace: the luma (0..255) between low and high. Default: halfway between the darkest and brightest frame."),
        Param("raw", PType.BOOLEAN, "For trace, track and audio: also return every frame, or every 20 ms loudness step for audio."),
        Param("axis", PType.STRING, "For track: the direction of travel. Default: the long side of the roi.", enum = listOf("x", "y")),
        Param("edge", PType.STRING, "For track: the edge to follow. Default strongest. rising is dark to light along the axis.", enum = Track.Edge.WIRES),
        Param("mm_per_px", PType.NUMBER, "For track: the scale at the part. Adds mm columns. Default: the stored scale, when valid."),
        Param("image", PType.BOOLEAN, "For track: also return the time stack, one line of the roi per frame. Default false."),
    )

    val describe = Capability(
        "describe",
        "Start here: what Lookout sees and can do, and what changed.",
        "Orient yourself in one call: lenses, camera settings, phone tilt, saved ROIs, baselines and scale, the history span, recording support, battery and sleep state. " +
            "tools gives one line for each tool; read it, because your client may load only some tool schemas. " +
            "changes lists what is new since the version you last saw, once. Cheap; call it first.",
        listOf(
            Param("changes", PType.STRING, "\"all\", or a version such as 0.1.0, for the changes since then. Default: the changes since the version you last saw."),
        ),
        Kind.JSON,
    ) { a, ctx -> CapResult(describeJson(ctx, a.string("changes"))) }

    val status = Capability(
        "status",
        "Battery, heat and camera state, without waking Lookout.",
        "Battery, charging, temperature, thermal state, Wi-Fi signal, uptime and camera state. It does not wake Lookout from sleep.",
        emptyList(), Kind.JSON, wakes = { false },
    ) { _, _ -> CapResult(statusJson()) }

    val look = Capability(
        "look",
        "See the scene now. The default way to look.",
        "Get the current view. " +
            "detail=fast (default) gives a preview frame of about 1024 px, under 1 s when warm; the first look after an idle period or a sleep takes a few seconds while the camera opens. " +
            "detail=full takes a slower full-resolution still: use it only for small text, ideally with an roi. A small roi also comes from a still. " +
            "The frame is taken after your call arrives, so it shows the result of what you just did. " +
            "Lens, zoom, focus, torch and exposure_comp apply to this call only. With guide_text or guide_image, the person sets up the shot and taps Ready first; " +
            "the settings apply while the guide shows, so the preview matches the shot. " +
            "A guided look is handheld: exposure stays at or under 1/30 s, the torch turns on in the dark unless you pass torch, and after Ready " +
            "the shot waits for a steady phone (steady, steady_wait_ms). The person can tap Ready when level instead: the shot fires when the level is green " +
            "(released_by=level, level_deg). A handheld frame has handheld=true and blur_px, the blur that the gyro predicts. " +
            "For a closer view of a frame you already have, use crop. To read text or numbers, read costs fewer tokens.",
        listOf(
            Param("detail", PType.STRING, "fast (preview frame, default) or full (full-resolution still).", enum = listOf("fast", "full")),
            roiParam, gridParam, stampParam, qualityParam, lensParam,
            Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568). Default 1024 (defaults.width) for fast, 1568 for full. An image costs about width × height / 750 tokens."),
            Param("fresh", PType.BOOLEAN, "detail=fast: only a frame captured after this call arrived. Default true; false on REST /v1/frame."),
            Param("settle_ms", PType.INTEGER, "Wait until nothing moved for this many ms and AE and AF converged, at most 10 s longer (then settled=false). With detail=full, the still is taken after that."),
            Param("sharp", PType.BOOLEAN, "Return the sharpest of a short burst: $SHARP_FAST_FRAMES preview frames, or $SHARP_STILLS stills for a still."),
            Param("after_frame_id", PType.INTEGER, "detail=fast only: return the first frame newer than this id."),
            Param("after", PType.STRING, "detail=fast only: return the first frame captured after this time (ISO 8601 or relative like -5s)."),
            Param("zoom", PType.NUMBER, "Zoom ratio for this call."),
            Param("focus", PType.OBJECT, "Focus for this call.", schema = focusSchema),
            Param("torch", PType.BOOLEAN, "Torch on for this call."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index for this call."),
            Param("save_as", PType.STRING, "Also save the sharpest of a still burst as a baseline with this name. Implies detail=full. Refuses dark or blurred frames unless force=true."),
            Param("force", PType.BOOLEAN, "Return a dark image anyway, or save a dark or blurred baseline."),
            Param("verbose", PType.BOOLEAN, "Return all camera and device fields. Default: camera fields only when they changed, device fields only at a warning level."),
            guideTextParam, guideImageParam, guideMarksParam, readyTimeoutParam, waitMsParam,
        ),
        Kind.IMAGE, images = core.composites,
    ) { a, ctx -> look(a, ctx) }

    val read = Capability(
        "read",
        "Read a display, meter or label as text and numbers, no image.",
        "Read text and numbers with on-device OCR and return them as JSON, not an image, so it costs few tokens: a multimeter, a power supply, a caliper, a printer LCD or a label. " +
            "Give an roi to read one display. " +
            "value and unit are the first number (\"12.04 V\" gives 12.04 and V), numbers lists all of them, and lines have boxes in full-frame 0..1 coordinates. " +
            "Seven-segment digits can read badly; check with look. To wait for a value, use wait_for(condition=value).",
        listOf(
            roiParam, lensParam,
            Param("source", PType.STRING, "still (sharper, default) or frame (faster). A running recording forces frame.", enum = listOf("still", "frame")),
            Param("image", PType.BOOLEAN, "Also return the region with the found lines outlined."),
            Param("duration_ms", PType.INTEGER, "Log the value over time: read preview frames for this long (max $MAX_SAMPLE_MS) and return a table of t_ms, value and text, with min, max, mean and last."),
            Param("every_ms", PType.INTEGER, "With duration_ms: how often to read. Default 1000, min $MIN_READ_EVERY_MS."),
        ),
        Kind.IMAGE, images = core.composites,
    ) { a, ctx -> readTool(a, ctx) }

    val camera = Capability(
        "camera",
        "Set lens, zoom, focus, torch or power mode for later calls.",
        "Change persistent camera settings: lens, zoom, focus, AE and AWB lock, torch, exposure and power mode. " +
            "Returns the settings in effect; call it with no arguments to read them. The torch turns off when Lookout sleeps and stays off.",
        listOf(
            lensParam.copy(description = "Lens id from describe."),
            Param("zoom", PType.NUMBER, "Zoom ratio."),
            Param("focus", PType.OBJECT, "Focus setting.", schema = focusSchema),
            Param("ae_lock", PType.BOOLEAN, "Lock auto exposure."),
            Param("awb_lock", PType.BOOLEAN, "Lock auto white balance."),
            Param("torch", PType.BOOLEAN, "Torch on or off."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index."),
            Param(
                "mode", PType.STRING,
                "Power mode. warm keeps the camera open (history, instant frames); cold opens it for each call; auto stays warm for 5 minutes after a call. " +
                    "default: warm while charging, else auto.",
                enum = listOf("warm", "cold", "auto", "default"),
            ),
        ),
        Kind.JSON,
    ) { a, _ -> camera(a) }

    val waitFor = Capability(
        "wait_for",
        "Wait for a change, motion, stillness, a value or a tap. Never poll look.",
        "Block until something happens, instead of polling look. change: the view (or roi) differs from the moment you called by more than threshold. " +
            "motion: something moves. still: nothing moved for for_ms (e.g. a print finished). human_ack: the person taps Done on the phone. " +
            "value: OCR of the roi is above or below a limit, or contains text (e.g. a nozzle at 200 °C, a display showing DONE). " +
            "request: a guided look or record finished; returns its result. " +
            "recording_done: a recording finished; returns its summary and, when it saw motion, a motion contact sheet. " +
            "A match returns the before and after frames side by side with the changes outlined. " +
            "A timeout returns matched=false and the latest frame, not an error. " +
            "Keep timeout_ms at or under 50000 (some clients stop a call at 60 s) and call it again for long jobs.",
        listOf(
            Param("condition", PType.STRING, "What to wait for.", required = true, enum = listOf("change", "motion", "still", "human_ack", "recording_done", "request", "value")),
            roiParam,
            Param("threshold", PType.NUMBER, "Fraction of pixels (0..1) that must change. Defaults: change 0.02, motion 0.01."),
            Param("for_ms", PType.INTEGER, "For still: how long the scene must stay still. Default 10000."),
            Param("timeout_ms", PType.INTEGER, "Give up after this many ms. Default $DEFAULT_WAIT_MS, max $MAX_WAIT_MS."),
            Param("message", PType.STRING, "For human_ack: the text shown on the phone."),
            Param("request_id", PType.STRING, "For request: the request_id of a guided call that returned state=waiting."),
            Param("recording_id", PType.STRING, "For recording_done: the recording. Default: the running one, else the newest."),
            Param("sheet", PType.BOOLEAN, "For recording_done: return a motion contact sheet when the recording saw motion. Default true."),
            Param("exact", PType.BOOLEAN, "For recording_done: also wait for the exact motion pass. Default false."),
            Param("clip", PType.OBJECT, "For recording_done: return this clip in place of the default sheet. It takes the parameters of the clip tool, without recording_id. It wins over sheet=false.", schema = paramsSchema(clipOptions, describe = false)),
            Param("above", PType.NUMBER, "For value: match when the value is above this."),
            Param("below", PType.NUMBER, "For value: match when the value is below this."),
            Param("text", PType.STRING, "For value: match when the read text contains this (case-insensitive)."),
            Param("every_ms", PType.INTEGER, "For value: how often to read. Default 1000, min $MIN_READ_EVERY_MS."),
            Param("confirm", PType.INTEGER, "For value: how many reads in a row must match, so one misread digit does not end the wait. Default 2."),
        ),
        Kind.JSON_AND_IMAGE, images = core.composites,
    ) { a, ctx -> waitFor(a, ctx) }

    val history = Capability(
        "history",
        "See what happened while you were away (warm mode).",
        "See what happened while you were not watching: one contact sheet of evenly spaced frames from the ring buffer, each tile labelled with its time and frame_id. " +
            "describe gives the span (by default one frame every 2 s for 30 minutes). Needs warm mode.",
        listOf(
            Param("since", PType.STRING, "Start of the window: ISO 8601 or relative like -20m. Default -20m."),
            Param("until", PType.STRING, "End of the window. Default now."),
            Param("tiles", PType.INTEGER, "Number of tiles, 1..36. Default 12."),
            roiParam,
            Param("events_only", PType.BOOLEAN, "Pick tiles at detected motion and stillness events."),
        ),
        Kind.IMAGE, images = core.composites,
    ) { a, ctx -> history(a, ctx) }

    val diff = Capability(
        "diff",
        "Compare two frames; measure how far a part moved.",
        "Compare two frames, or a baseline against now. Returns {score, changed, regions} and a side-by-side image with the changes boxed. " +
            "It first aligns b to a for a small camera shift (global_shift_px); aligned=false means it could not. " +
            "With an roi it gives dx, dy: how far the content moved, in px of a. With image=false that measures one step of a mechanism cheaply. " +
            "camera_moved=true means the phone tilted more than ${LookoutCore.MOVED_DEG.toInt()} degrees between a and b, so the frames do not line up.",
        listOf(
            Param("a", PType.STRING, "Frame id or baseline name.", required = true),
            Param("b", PType.STRING, "Frame id or baseline name. Default: a fresh frame now."),
            roiParam,
            Param("threshold", PType.NUMBER, "Score above which changed is true. Default 0.02."),
            Param("image", PType.BOOLEAN, "Return the side-by-side image. Default true. Set false for JSON only."),
            Param("mm_per_px", PType.NUMBER, "The scale at the part. Adds dx_mm and dy_mm. Default: the stored scale, when valid."),
        ),
        Kind.JSON_AND_IMAGE, images = core.composites,
    ) { a, ctx -> diff(a, ctx) }

    val roi = Capability(
        "roi",
        "Name a region of the view for later calls.",
        "Manage named regions of interest. " +
            "set takes a rect, or grid cells from/to on the 8x6 grid that look draws on the full frame, plus optional camera defaults. " +
            "list returns all; delete removes one.",
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

    val scale = Capability(
        "scale",
        "Calibrate mm per px, so results report mm.",
        "Calibrate the scale from two points a known distance apart, so look, crop, diff and clip mode=track report mm. " +
            "It is valid for one plane at one distance from the camera. A moved phone clears it, and a lens or zoom change makes it stale.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("set", "get", "clear")),
            Param("from", PType.STRING, "For set: first point in the look frame, \"x,y\" in 0..1 or a grid cell like C3."),
            Param("to", PType.STRING, "For set: second point in the look frame, \"x,y\" in 0..1 or a grid cell like F3."),
            Param("mm", PType.NUMBER, "For set: real distance between from and to in mm."),
            Param("grid", PType.STRING, "For set: grid for cell names. Default 8x6."),
        ),
        Kind.JSON,
    ) { a, _ -> scaleTool(a) }

    val baseline = Capability(
        "baseline",
        "Save a reference frame to diff against later.",
        "Manage reference frames for diff. save takes a still burst, stores the sharpest frame under name (e.g. bed-empty) and returns a preview. " +
            "It refuses dark or blurred frames unless force=true. list returns all; delete removes one.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("save", "list", "delete")),
            Param("name", PType.STRING, "Baseline name."),
            roiParam.copy(description = "ROI to remember with the baseline; diff uses it when you give none."),
            Param("force", PType.BOOLEAN, "For save: save even when the frame is dark or blurred."),
        ),
        Kind.JSON_AND_IMAGE, images = core.composites,
    ) { a, ctx -> baselineTool(a, ctx) }

    private val markAtSchema = buildJsonObject { putJsonArray("type") { add("string"); add("integer") } }

    val record = Capability(
        "record",
        "Record video of fast events (blink codes, a fall) or sound.",
        "Record video at full frame rate, for events that look and wait_for miss: a servo sweep, an LED blink code, a part falling, a display glitch. " +
            "start returns once the encoder runs, so trigger the event after it returns. " +
            "Call action=mark with a label as you act, or add marks later with at or marks (for example from a script log). " +
            "Then call wait_for(condition=recording_done), and clip to look closer. One recording at a time; look detail=full is unavailable while it runs. " +
            "An roi means the same region in look, record and clip. The 16:9 video can be narrower than a look frame; video_crop gives the part it covers. " +
            "At high fps, PWM-dimmed LEDs and screens can show bands or flicker; that comes from the camera, not the device.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("start", "mark", "stop", "list")),
            Param("fps", PType.INTEGER, "For start: frames per second. Default 30. describe lists the rates this lens supports (usually 30 and 60)."),
            Param("resolution", PType.STRING, "For start: video size. Default 1080p.", enum = VideoResolution.entries.map { it.wire }),
            Param("duration_ms", PType.INTEGER, "For start: length of the recording. Default ${Recordings.DEFAULT_DURATION_MS}, max ${Recordings.MAX_DURATION_MS}."),
            Param("audio", PType.BOOLEAN, "For start: also record sound. Default false. Needs \"Allow sound recording\" in Setup on the phone."),
            roiParam.copy(description = "For start: the region that the motion score covers and that clip crops to by default. The video is never cropped."),
            Param("label", PType.STRING, "For start: a name for the recording. For mark: what you did, e.g. \"sent M106\"."),
            Param("at", PType.STRING, "For mark: when it happened, an ISO 8601 time or t_ms. Default now.", schema = markAtSchema),
            Param("marks", PType.OBJECT, "For mark: several marks at once, in place of label and at. All are added or none.", schema = buildJsonObject {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("label") { put("type", "string") }
                        put("at", JsonObject(markAtSchema + ("description" to JsonPrimitive("An ISO 8601 time or t_ms. Default now."))))
                    }
                    putJsonArray("required") { add("label") }
                }
            }),
            Param("recording_id", PType.STRING, "For mark: the recording. Default: the running one, else the newest."),
            Param("client_now", PType.STRING, "For mark: your clock now, ISO 8601. Lookout shifts each ISO at by phone clock minus yours. It ignores network delay."),
            guideTextParam.copy(description = "For start: ${guideTextParam.description}"),
            guideImageParam.copy(description = "For start: ${guideImageParam.description}"),
            guideMarksParam, readyTimeoutParam, waitMsParam,
        ),
        Kind.JSON,
    ) { a, ctx -> recordTool(a, ctx) }

    val clip = Capability(
        "clip",
        "Inspect a recording: frames, LED timing, part position, sound.",
        "Look inside a finished recording without spending context on every frame. " +
            "Times are t_ms from the first frame; wall time = started_at + t_ms, accurate to time_precision_ms. " +
            "mode=sheet (default): a contact sheet of 12 tiles across the clip. Narrow it with from_ms and to_ms, pick=motion, at_ms, at or at_marks. " +
            "tiles are arrays in the order of tile_columns. Tile i is at x = (i % cols) × (tile_w + gap), y = (i / cols) × (tile_h + band + gap) of layout. " +
            "mode=trace: the brightness of the roi on every frame as high and low runs with durations and frequency, for blink codes and flicker. " +
            "mode=track: the position of one edge on every frame, in a thin roi across its path, with holds, moves and where it settled after each mark. " +
            "mode=audio: loudness over time, sound events with frequency and period, and delays after each mark. " +
            "trace, track and audio read the video file and return no image (track gives one with image=true). " +
            "While the summary has motion_source=preview, pick=motion and the motion column use preview motion. " +
            "For tools such as ffmpeg, download video_url from the summary.",
        listOf(Param("recording_id", PType.STRING, "The recording. Default: the newest.")) + clipOptions,
        Kind.IMAGE, images = core.composites,
    ) { a, ctx -> clipTool(a, ctx) }

    val say = Capability(
        "say",
        "Speak to the person near the phone.",
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
        "Ask the person a question; wait for the tapped answer.",
        "Show a full-screen message on the phone with buttons and wait for a tap. Returns {answered, choice}. " +
            "Pair it with wait_for(change) to ask the person to do something and then see it happen.",
        listOf(
            Param("text", PType.STRING, "The message.", required = true),
            Param("buttons", PType.STRING_ARRAY, "Button labels, at most 6. Default [\"OK\"]."),
            Param("timeout_ms", PType.INTEGER, "Give up after this many ms. Default $DEFAULT_WAIT_MS, max $MAX_WAIT_MS."),
        ),
        Kind.JSON,
    ) { a, _ ->
        val text = a.string("text")!!
        val buttons = a.strings("buttons") ?: listOf("OK")
        if (buttons.size > 6) throw LookoutError("invalid_param", 400, "At most 6 buttons")
        val timeout = (a.long("timeout_ms") ?: DEFAULT_WAIT_MS).coerceIn(1_000L, MAX_WAIT_MS)
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

    val point = Capability(
        "point",
        "Show the person where to act, with marks on the preview.",
        "Draw marks over the live preview on the phone, so the person sees where to act: \"plug the red wire into this pin\". " +
            "Place each mark with at: a grid cell (C3, on the 8x6 grid that look draws), a span (C2:E4), a point \"x,y\", a rect \"x,y,w,h\" or an ROI name. " +
            "An arrow points at at (from sets its tail), a box outlines it, and a label writes text there. New marks replace the old ones. " +
            "Without buttons the call returns at once and the marks stay for timeout_ms. With buttons (e.g. " +
            "[\"Done\"]) it waits for a tap, then clears the marks and returns {answered, choice}.",
        listOf(
            Param("action", PType.STRING, "show (default) or clear.", enum = listOf("show", "clear")),
            Param("marks", PType.OBJECT, "The marks, at most ${Marks.MAX_MARKS}.", schema = marksSchema),
            Param("text", PType.STRING, "An instruction for the person, shown with the marks."),
            Param("buttons", PType.STRING_ARRAY, "Wait for the person to tap one of these, at most 4, e.g. [\"Done\", \"Can't find it\"]."),
            Param("timeout_ms", PType.INTEGER, "How long the marks stay (default 60000), or with buttons how long to wait for a tap (default $DEFAULT_WAIT_MS). Max $MAX_WAIT_MS."),
            Param("preview", PType.BOOLEAN, "Also return the current frame with the marks drawn, to check where they land."),
            gridParam.copy(description = "Grid for cell names in at and from. Default 8x6."),
        ),
        Kind.IMAGE, images = core.composites,
    ) { a, ctx -> pointTool(a, ctx) }

    val crop = Capability(
        "crop",
        "A closer view of a frame you have, with no new shot.",
        "A closer view of a frame you already have, with no new shot: a region of a frame_id from a result, or of a baseline. " +
            "It uses the best resolution kept (full resolution for a still), so it works after the scene changed, while recording and while Lookout sleeps. " +
            "A frame that a result returned stays for ${Retention.URL_LIFE_MS / 60_000} minutes.",
        listOf(
            Param("frame", PType.STRING, "A frame_id from a result, or a baseline name.", required = true),
            roiParam,
            Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568). Default 1024. Never more than the region has."),
            gridParam, stampParam, qualityParam,
        ),
        Kind.IMAGE, wakes = { false }, images = core.composites,
    ) { a, ctx -> cropTool(a, ctx) }

    val flatten = Capability(
        "flatten",
        "Measure a flat part and its holes in mm, top-down.",
        "A top-down view of a flat part at a fixed scale, with its holes and round features measured in mm. " +
            "Give the corners of a rectangle of known size (for example the board outline, read off a look with grid=ticks) with width_mm and height_mm, or mat=true to use the Lookout measuring mat. " +
            "It assumes a flat part, the whole reference in view and moderate perspective. Lens distortion is not corrected. " +
            "With frame it uses no camera and does not wake Lookout.",
        listOf(
            Param("frame", PType.STRING, "A frame_id or baseline name. Default: a new full-resolution still, the sharpest of a burst."),
            Param("corners", PType.STRING_ARRAY, "4 corners of the rectangle in the look frame, \"x,y\" in 0..1 or 8x6 grid cells, in any order."),
            Param("width_mm", PType.NUMBER, "Real length of the rectangle side that is nearer horizontal in the frame."),
            Param("height_mm", PType.NUMBER, "Real length of the other side."),
            Param("mat", PType.BOOLEAN, "Use the 4 markers of the measuring mat instead of corners. mm are then from the center of marker 0."),
            Param("refine", PType.BOOLEAN, "Snap the corners to the rectangle's edges. Default true."),
            Param("px_per_mm", PType.NUMBER, "Scale of the view. Default: about 1024 px on the long edge. At most 1568 px."),
            Param("margin_mm", PType.NUMBER, "Extra area around the rectangle, for a part that sticks out. Default 0."),
            Param("circles", PType.BOOLEAN, "Find and measure circles. Default true."),
            Param("min_d_mm", PType.NUMBER, "Smallest circle diameter in mm. Default: 8 px of the view."),
            Param("max_d_mm", PType.NUMBER, "Largest circle diameter. Default half the short side."),
            gridParam.copy(description = "\"ticks\" for rulers in mm along the edges, or a cell grid like \"8x6\"."),
            qualityParam,
        ),
        Kind.IMAGE, wakes = { it.raw("frame") == null }, images = core.composites,
    ) { a, ctx -> flattenTool(a, ctx) }

    val caption = Capability(
        "caption",
        "Label what you do now, so images show it.",
        "Show text over the camera view, e.g. \"sent M106\", so images show what you did at the moment the frame was taken. " +
            "The phone preview shows it, and images of frames taken while it shows have it at the bottom: look, crop, wait_for, history, diff and clip. " +
            "During a recording it also adds a mark with the text. A new caption replaces the old one. The video file itself has no text; the marks give the times.",
        listOf(
            Param("action", PType.STRING, "show (default) or clear.", enum = listOf("show", "clear")),
            Param("text", PType.STRING, "For show: the text, up to ${Captions.MAX_TEXT} characters."),
            Param("duration_ms", PType.INTEGER, "For show: remove the text after this many ms (100..${Captions.MAX_DURATION_MS}). Default: it stays until cleared or replaced."),
        ),
        Kind.JSON,
    ) { a, _ -> captionTool(a) }

    val done = Capability(
        "done",
        "Finished for now: let the phone sleep.",
        "Tell Lookout that you have finished and will not need the camera for a while. " +
            "It clears your caption and marks, then sleeps: the light goes off, the camera closes and the phone screen can turn off. " +
            "A running wait, recording or question delays the sleep until it ends. Your next call wakes it; the light stays off.",
        emptyList(), Kind.JSON, wakes = { false },
    ) { _, _ -> doneTool() }

    /** The MCP tools, in the order agents see them. status is REST-only; describe covers it. */
    val mcpTools: List<Capability> = listOf(describe, look, crop, flatten, waitFor, history, record, clip, diff, roi, scale, baseline, camera, say, ask, point, caption, read, done)

    val all: List<Capability> = mcpTools + status

    // ---- handlers ----

    private suspend fun look(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val roi = resolveRoi(a.raw("roi"))
        val saveAs = a.string("save_as")?.let { checkName(it, "Baseline") }
        val full = a.string("detail") == "full" || saveAs != null
        val sharp = a.bool("sharp") == true
        val force = a.bool("force") == true
        val width = (a.int("width") ?: if (full) Compositor.MAX_EDGE else core.settings.value.defaultWidth).coerceIn(16, Compositor.MAX_EDGE)
        val over = (roi?.defaults?.toSettings() ?: CameraSettings()).merge(
            CameraSettings(lens = a.string("lens"), zoom = a.double("zoom"), focus = a.focus("focus"), torch = a.bool("torch"), exposureComp = a.int("exposure_comp")),
        ).let { s ->
            if (s.focus?.mode != "roi") s
            else {
                val r = roi ?: throw LookoutError("invalid_param", 400, "focus=roi needs an roi")
                s.copy(focus = FocusSetting("roi", region = r.rect))
            }
        }
        val fresh = a.bool("fresh") ?: true
        val afterId = a.long("after_frame_id")
        val afterMs = a.string("after")?.let { parseTime(it, clock.now()) }
        val settleMs = a.long("settle_ms")?.coerceIn(0, MAX_WAIT_MS)
        val guide = guide(a, "look")
        // A guided shot holds the settings from the prompt on, so the preview shows the shot the person frames.
        suspend fun take(settings: CameraSettings): CapResult {
            val started = clock.now()
            return core.session.use { opened ->
                val timing = Timing(started, opened)
                withSettings(settings) {
                    if (full) {
                        val got = settleMs?.let { obtainFrame(true, null, null, it) }
                        got?.let { timing.waited(it) }
                        val shot = if (sharp || saveAs != null) sharpStill(roi, timing, got?.settled) else stillShot(timing, got?.settled)
                        val dark = isDark(shot.frame, force)
                        val blur = if (saveAs == null || dark || force) null else blurHint(shot)
                        if (saveAs != null && !dark && blur == null) {
                            saveBaseline(saveAs, shot.frame, roi)
                            core.events.add("baseline", buildJsonObject { put("saved", saveAs) })
                        }
                        return@withSettings imageResult("look", ctx, shot, roi, width, a, got?.settled, timing, dark, notSaved = saveAs != null && (dark || blur != null), blurHint = blur)
                    }
                    // A frame from before Ready or before the settings changed would not show the shot asked for.
                    val got = obtainFrame(fresh || guide != null || !over.isEmpty, afterId, afterMs, settleMs)
                    timing.waited(got)
                    // A small roi of a preview frame has too few pixels, so it comes from a still.
                    val useStill = roi != null && !core.recordings.isRecording &&
                        roi.rect.toPixels(got.frame.image.width, got.frame.image.height).let { max(it.w, it.h) < width * 0.75 }
                    val shot = when {
                        useStill && sharp -> sharpStill(roi, timing, got.settled)
                        useStill -> stillShot(timing, got.settled)
                        sharp -> sharpFast(got.frame, roi, timing, got.settled)
                        else -> Shot(got.frame, "analysis", moving = moving(got.frame, got.settled))
                    }
                    imageResult("look", ctx, shot, roi, width, a, got.settled, timing, isDark(shot.frame, force))
                }
            }
        }
        // A person holds the phone for a guided look, so the exposure is kept short while the guide shows.
        return if (guide == null) take(over) else guided(look, guide, a, ctx, over.merge(CameraSettings(handheld = true)), steady = true) { take(CameraSettings()) }
    }

    /** The guide of a guided shot: the text and example image shown on the phone, and how long to wait. */
    private class Guide(val text: String, val image: ByteArray?, val timeoutMs: Long, val waitMs: Long)

    /** The guide that [a] asks for, checked before anything shows on the phone, or null when there is none. */
    private suspend fun guide(a: Args, tool: String): Guide? {
        val text = a.string("guide_text")?.trim()?.takeIf { it.isNotEmpty() }
        val marks = a.raw("guide_marks")?.let { m -> Marks.parse(Marks.specs(m), GridSpec.DEFAULT) { core.rois.require(it).rect } }
        val image = a.string("guide_image")?.let { guideImage(it, marks.orEmpty()) }
        if (marks != null && image == null) throw LookoutError("invalid_param", 400, "guide_marks needs guide_image")
        if (text == null && image == null) return null
        val timeout = (a.long("ready_timeout_ms") ?: DEFAULT_READY_MS).coerceIn(1_000L, MAX_WAIT_MS)
        val wait = (a.long("wait_ms") ?: DEFAULT_GUIDE_WAIT_MS).coerceIn(0L, MAX_GUIDE_WAIT_MS)
        val shown = text ?: if (tool == "record") "Set up the shot like the example. Recording starts when you tap Ready." else "Set up the shot like the example. The photo is taken when you tap Ready."
        return Guide(shown, image, timeout, wait)
    }

    /**
     * Shows [g] on the phone and runs [shot] when the person taps Ready. Both run in the core scope, so the shot
     * still happens when this call ends first. [over] applies while the prompt shows, so the preview matches the shot.
     * Returns the result of [cap] when it comes within wait_ms, else state=waiting.
     * With [steady], the shot waits [AFTER_TAP_MS] for the tap to end, then for the [SteadyGate].
     */
    private suspend fun guided(
        cap: Capability, g: Guide, a: Args, ctx: CallContext, over: CameraSettings = CameraSettings(), steady: Boolean = false,
        shot: suspend () -> CapResult,
    ): CapResult {
        val req = core.guided.launch(cap.name, clock.now() + g.timeoutMs) { request ->
            withSettings(over) {
                val shownAt = clock.now()
                val deadline = shownAt + g.timeoutMs
                // Ready when level needs the gravity sensor that drives the level on the phone.
                val buttons = if (steady && core.source.level() != null) listOf(READY, READY_LEVEL, CANCEL) else listOf(READY, CANCEL)
                val choice = core.human.guide(SHOT_HEADING, g.text, g.image, buttons, g.timeoutMs)
                val readyAfter = clock.now() - shownAt
                core.events.add("guide", buildJsonObject {
                    put("tool", cap.name)
                    put("request_id", request.id)
                    put("text", g.text)
                    put("image", g.image != null)
                    put("released_by", when (choice) { READY -> "tap"; READY_LEVEL -> "level"; null -> "timeout"; else -> "cancel" })
                    if (choice == READY || choice == READY_LEVEL) put("ready_after_ms", readyAfter)
                })
                when (choice) {
                    READY, READY_LEVEL -> request.release()
                    null -> throw LookoutError("ready_timeout", 408, "The person did not tap Ready within ${g.timeoutMs} ms")
                    else -> throw LookoutError("declined", 409, "The person tapped Cancel on the phone")
                }
                if (steady && choice == READY) delay(AFTER_TAP_MS)
                // In the dark even a short exposure is too dim to read, so a handheld shot gets the torch, unless the agent chose.
                val lens = store.latest.value?.camera?.lens?.let { lensOf(it) }
                val torch = steady && over.torch == null && lens?.hasFlash == true && (store.latest.value?.brightness ?: 1.0) < DARK_FRAME
                var releasedBy = "tap"
                var levelDeg: Double? = null
                val (gate, r) = withSettings(if (torch) CameraSettings(torch = true) else CameraSettings()) {
                    if (torch) awaitExposure()
                    val gate = when {
                        !steady -> null
                        choice == READY_LEVEL -> levelWait(lens, deadline).let { (by, gate) -> releasedBy = by; gate }
                        else -> steadyGate.await(lens)
                    }
                    if (choice == READY_LEVEL) levelDeg = core.source.level()?.offDeg?.let { kotlin.math.round(it * 10) / 10 }
                    gate to shot()
                }
                if (releasedBy == "level") core.human.shotTaken()
                val released = buildMap {
                    put("released_by", JsonPrimitive(releasedBy))
                    put("ready_after_ms", JsonPrimitive(readyAfter))
                    gate?.steady?.let { put("steady", JsonPrimitive(it)); put("steady_wait_ms", JsonPrimitive(gate.waitedMs)) }
                    levelDeg?.let { put("level_deg", JsonPrimitive(it)) }
                    if (torch) put("torch", JsonPrimitive(true))
                }
                cap.output(CapResult(JsonObject(r.json + released), r.image), a, ctx)
            }
        }
        req.await(g.waitMs)?.let { return it.getOrThrow() }
        return CapResult(buildJsonObject { waiting(req) })
    }

    private fun JsonObjectBuilder.waiting(req: GuidedRequests.Request) {
        put("state", "waiting")
        put("request_id", req.id)
        put("expires_at", isoTime(req.expiresAt))
        put("next", "Call wait_for(condition=request, request_id=${req.id}).")
    }

    /** wait_for(condition=request): the result of a guided call, its end by Cancel or timeout, or state=waiting. */
    private suspend fun requestDone(a: Args, start: Long, timeout: Long): CapResult {
        val id = a.string("request_id") ?: throw LookoutError("missing_param", 400, "request needs request_id")
        val req = core.guided.get(id)
        val outcome = req.await(timeout) ?: return CapResult(buildJsonObject {
            put("matched", false)
            put("condition", "request")
            put("waited_ms", clock.now() - start)
            waiting(req)
        })
        val e = outcome.exceptionOrNull() as? LookoutError
        val state = when (e?.code) {
            "declined" -> "declined"
            "ready_timeout" -> "timeout"
            else -> return outcome.getOrThrow()
        }
        return CapResult(buildJsonObject {
            put("matched", true)
            put("condition", "request")
            put("request_id", id)
            put("state", state)
            put("message", e?.message)
        })
    }

    /**
     * The example image of a guide, with [marks] drawn on it. [value] is a frame_id, an image_id (c12) or a baseline name,
     * or else base64 JPEG or PNG data or a data: URL. A reference is a short token that matches the id and name patterns.
     */
    private suspend fun guideImage(value: String, marks: List<PointMark>): ByteArray {
        val v = value.trim()
        val img = if (NAME_PATTERN.matches(v)) {
            val jpeg = try {
                frameJpeg(v)
            } catch (e: LookoutError) {
                throw LookoutError("invalid_param", 400, "'guide_image' $v is not a kept frame_id, image_id or baseline name: ${e.message}")
            }
            ops.decode(jpeg)
        } else {
            val b64 = v.substringAfter("base64,", v).filterNot { it.isWhitespace() }
            val bytes = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
            bytes?.let { runCatching { ops.decode(it) }.getOrNull() }
                ?: throw LookoutError("invalid_param", 400, "'guide_image' must be a frame_id, an image_id, a baseline name, a base64 JPEG or PNG, or a data: URL")
        }
        return guideJpeg(img, marks)
    }

    /** [img] scaled to a size the phone can show, with [marks] drawn on it, as a JPEG. */
    private fun guideJpeg(img: Img, marks: List<PointMark>): ByteArray {
        val edge = max(img.width, img.height)
        val shown = if (edge > GUIDE_EDGE) {
            ops.scale(img, (img.width * GUIDE_EDGE / edge).coerceAtLeast(1), (img.height * GUIDE_EDGE / edge).coerceAtLeast(1))
        } else img
        return ops.encodeJpeg(comp.marks(shown, marks), 85)
    }

    /**
     * Stores an image that a script uploaded, for guide_image, and returns its image_id. It is scaled as a guide image is.
     * At most [UPLOAD_BURST] uploads at once, then one per [UPLOAD_EVERY_MS], so uploads cannot flush the composite store.
     */
    fun upload(bytes: ByteArray): String {
        if (!uploads.tryTake()) throw LookoutError("rate_limited", 429, "Too many uploads. Wait ${UPLOAD_EVERY_MS / 1000} s")
        val img = runCatching { ops.decode(bytes) }.getOrNull() ?: throw LookoutError("invalid_image", 400, "The body must be a JPEG or PNG image")
        return core.composites.put(guideJpeg(img, emptyList()))
    }

    private suspend fun pointTool(a: Args, ctx: CallContext): CapResult {
        if (a.string("action") == "clear") {
            core.pointer.clear()
            core.events.add("point", buildJsonObject { put("cleared", true) })
            return CapResult(buildJsonObject { put("cleared", true) })
        }
        val specs = a.raw("marks")?.let(Marks::specs) ?: throw LookoutError("missing_param", 400, "show needs marks")
        val grid = parseGrid(a.string("grid")) ?: GridSpec.DEFAULT
        val marks = Marks.parse(specs, grid) { core.rois.require(it).rect }
        val text = a.string("text")?.trim()?.takeIf { it.isNotEmpty() }
        val buttons = a.strings("buttons").orEmpty()
        if (buttons.size > 4) throw LookoutError("invalid_param", 400, "At most 4 buttons")
        val timeout = (a.long("timeout_ms") ?: if (buttons.isEmpty()) 60_000L else DEFAULT_WAIT_MS).coerceIn(1_000L, MAX_WAIT_MS)
        if (a.bool("preview") == true) guardImage()
        // With buttons, the panel beside the preview shows the text, so the caption stays empty.
        val set = core.pointer.show(marks, text.takeIf { buttons.isEmpty() }, timeout)
        val preview = if (a.bool("preview") == true) pointPreview(ctx, marks) else null
        core.events.add("point", buildJsonObject {
            put("marks", marks.size)
            text?.let { put("text", it) }
        })
        val choice = if (buttons.isEmpty()) null else try {
            core.human.guide(POINT_HEADING, text ?: "Look at the marks on the preview.", null, buttons, timeout)
        } finally {
            core.pointer.clear(set.id)
        }
        if (buttons.isNotEmpty()) {
            core.events.add("point", buildJsonObject { put("answered", choice != null); choice?.let { put("choice", it) } })
        }
        val json = buildJsonObject {
            put("marks", LookoutJson.encodeToJsonElement(marks))
            if (buttons.isEmpty()) put("expires_at", isoTime(set.deadlineMs))
            else {
                put("answered", choice != null)
                choice?.let { put("choice", it) }
            }
            preview?.let { (meta, _) -> put("preview", meta) }
        }
        return CapResult(json, preview?.second)
    }

    private fun captionTool(a: Args): CapResult {
        if (a.string("action") == "clear") {
            val ended = core.captions.clear()
            core.events.add("caption", buildJsonObject { put("cleared", ended != null) })
            return CapResult(buildJsonObject { put("cleared", ended != null) })
        }
        val text = a.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: throw LookoutError("missing_param", 400, "show needs text")
        if (text.length > Captions.MAX_TEXT) throw LookoutError("invalid_param", 400, "text is longer than ${Captions.MAX_TEXT} characters")
        val duration = a.long("duration_ms")?.also {
            if (it !in 100..Captions.MAX_DURATION_MS) throw LookoutError("invalid_param", 400, "duration_ms must be 100..${Captions.MAX_DURATION_MS}")
        }
        val c = core.captions.show(text, duration)
        val markedId = core.recordings.activeId
        val mark = markedId?.let { id -> runCatching { core.recordings.mark(id, listOf(Recordings.MarkAt(text, null))).single() }.getOrNull() }
        core.events.add("caption", buildJsonObject {
            put("text", text)
            duration?.let { put("duration_ms", it) }
        })
        return CapResult(buildJsonObject {
            put("text", c.text)
            put("shown_at", isoTime(c.fromMs))
            c.untilMs?.let { put("until", isoTime(it)) }
            mark?.let { m -> put("recording_id", markedId); put("t_ms", m.tMs) }
        })
    }

    /** [img] with the caption that showed at [atMs], if any. */
    private fun captioned(img: Img, atMs: Long?): Img =
        atMs?.let { core.captions.at(it) }?.let { comp.caption(img, it.text) } ?: img

    private suspend fun readTool(a: Args, ctx: CallContext): CapResult {
        val reader = requireReader()
        guardImage()
        val roi = resolveRoi(a.raw("roi"))
        val over = (roi?.defaults?.toSettings() ?: CameraSettings()).merge(CameraSettings(lens = a.string("lens")))
        a.long("duration_ms")?.let { return sampleValues(reader, roi, over, it, a) }
        val useStill = a.string("source") != "frame" && !core.recordings.isRecording
        return core.session.use {
            withSettings(over) {
                val frame = if (useStill) store.registerStill(still()) else obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
                store.keep(frame)
                val r = readFrame(reader, frame, roi)
                core.events.add("read", buildJsonObject {
                    put("lines", r.lines.size)
                    r.value?.let { put("value", it.value) }
                    roi?.let { put("roi", it.label()) }
                })
                val json = buildJsonObject {
                    put("text", r.text)
                    r.value?.let { put("value", it.value); it.unit?.let { u -> put("unit", u) } }
                    put("numbers", LookoutJson.encodeToJsonElement(r.numbers))
                    putJsonArray("lines") {
                        r.lines.forEach { l ->
                            add(buildJsonObject {
                                put("text", l.text)
                                put("box", LookoutJson.encodeToJsonElement(roi?.let { l.box.within(it.rect) } ?: l.box))
                                l.confidence?.let { put("confidence", it) }
                            })
                        }
                    }
                    put("frame_id", frame.id)
                    put("captured_at", isoTime(frame.capturedAtMs))
                    put("source", if (useStill) "still" else "analysis")
                    roi?.let { put("roi", it.json()) }
                    put("ocr_ms", r.ms)
                }
                if (a.bool("image") != true) return@withSettings CapResult(json)
                val img = comp.outline(comp.fit(comp.crop(frame.image, roi?.rect), Compositor.DEFAULT_EDGE), r.lines.map { it.box })
                val jpeg = ops.encodeJpeg(img, core.settings.value.jpegQuality)
                core.recordServed("read", ctx, jpeg.size, roi?.label())
                CapResult(json, ImageOut(jpeg))
            }
        }
    }

    /** Reads a value from preview frames every every_ms for duration_ms and returns the table. */
    private suspend fun sampleValues(reader: TextReader, roi: RoiRef?, over: CameraSettings, durationMs: Long, a: Args): CapResult {
        val duration = durationMs.coerceIn(1_000L, MAX_SAMPLE_MS)
        val every = (a.long("every_ms") ?: 1_000L).coerceIn(MIN_READ_EVERY_MS, duration)
        return guardedWait {
            core.session.use {
                withSettings(over) {
                    val start = clock.now()
                    val samples = mutableListOf<Pair<Long, Reading>>()
                    while (clock.now() - start < duration) {
                        val tick = clock.now()
                        val frame = obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
                        samples += (frame.capturedAtMs - start) to readFrame(reader, frame, roi)
                        kotlinx.coroutines.delay(max(0L, every - (clock.now() - tick)))
                    }
                    val values = samples.mapNotNull { it.second.value?.value }
                    core.events.add("read", buildJsonObject {
                        put("samples", samples.size)
                        values.lastOrNull()?.let { put("value", it) }
                        roi?.let { put("roi", it.label()) }
                    })
                    CapResult(buildJsonObject {
                        put("count", samples.size)
                        put("read", values.size)
                        if (values.isNotEmpty()) {
                            put("min", values.min())
                            put("max", values.max())
                            put("mean", values.average())
                            put("last", values.last())
                        }
                        samples.firstNotNullOfOrNull { it.second.value?.unit }?.let { put("unit", it) }
                        put("columns", buildJsonArray { add("t_ms"); add("value"); add("text") })
                        putJsonArray("samples") {
                            samples.forEach { (t, r) ->
                                add(buildJsonArray {
                                    add(t)
                                    add(r.value?.value?.let(::JsonPrimitive) ?: JsonNull)
                                    add(r.text)
                                })
                            }
                        }
                        roi?.let { put("roi", it.json()) }
                        put("started_at", isoTime(start))
                    })
                }
            }
        }
    }

    private fun requireReader(): TextReader =
        core.textReader ?: throw LookoutError("read_unsupported", 501, "This Lookout server cannot read text (no on-device OCR)")

    private class Reading(val lines: List<TextLine>, val text: String, val numbers: List<NumberReading>, val ms: Long) {
        val value: NumberReading? get() = numbers.firstOrNull()
    }

    private suspend fun readFrame(reader: TextReader, frame: Frame, roi: RoiRef?): Reading {
        val started = clock.now()
        val lines = reader.read(readable(comp.crop(frame.image, roi?.rect)))
        val text = lines.joinToString("\n") { it.text }
        return Reading(lines, text, Numbers.parse(text), clock.now() - started)
    }

    /** OCR misses small glyphs, so a small crop is scaled up (at most 4x) before it is read. */
    private fun readable(img: Img): Img {
        val edge = max(img.width, img.height)
        if (edge >= READ_EDGE) return img
        val s = min(4.0, READ_EDGE.toDouble() / edge)
        return ops.scale(img, (img.width * s).roundToInt(), (img.height * s).roundToInt())
    }

    /** The current frame with [marks] drawn, as the person sees it on the phone. */
    private suspend fun pointPreview(ctx: CallContext, marks: List<PointMark>): Pair<JsonObject, ImageOut> = core.session.use {
        val frame = obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
        store.keep(frame)
        val img = comp.marks(comp.fit(frame.image, core.settings.value.defaultWidth), marks)
        val jpeg = ops.encodeJpeg(img, core.settings.value.jpegQuality)
        core.recordServed("point", ctx, jpeg.size, null)
        frameMeta(frame, img, null, "analysis", ctx, verbose = false) to ImageOut(jpeg)
    }

    private suspend fun doneTool(): CapResult {
        core.release()
        return CapResult(buildJsonObject {
            put("asleep", core.sleep.asleep.value)
            if (!core.sleep.asleep.value) putJsonArray("awake_for") { core.sleepBlockers().forEach { add(it) } }
        })
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
        if (s.focus?.mode == "roi") throw LookoutError("invalid_param", 400, "focus=roi is only supported on look (with an roi)")
        if (s.lens != null) guardLensChange()
        if (!s.isEmpty) core.applyCamera(s)
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
        val timeout = (a.long("timeout_ms") ?: DEFAULT_WAIT_MS).coerceIn(0, MAX_WAIT_MS)
        if (condition == "request") return guardedWait { requestDone(a, clock.now(), timeout) }
        if (condition != "human_ack" && condition != "recording_done" && core.session.thermalLimited) {
            throw LookoutError("thermal", 503, "The phone is too hot for camera waits. Try again later.")
        }
        val valueTest = if (condition == "value") valueTest(a) else null
        val reader = if (condition == "value") requireReader() else null
        val clipSpec = if (condition == "recording_done") a.obj("clip")?.let { parseClip(Args(it), validate = true) } else null
        val wantSheet = if (condition == "recording_done") a.bool("sheet") ?: true else true
        var lastRead: Reading? = null
        return guardedWait {
            val start = clock.now()
            if (condition == "recording_done") return@guardedWait recordingDone(a, ctx, start, timeout, clipSpec, wantSheet)
            return core.session.hold {
                val before = store.await(FRAME_TIMEOUT_MS) { it.capturedAtMs >= start }
                    ?: throw LookoutError("no_frame", 504, "No frame arrived from the camera")
                val remaining = max(0L, timeout - (clock.now() - start))
                val match: WaitMatch? = when (condition) {
                    "human_ack" -> {
                        val choice = core.human.ask(a.string("message") ?: "Tap Done when you have finished.", listOf("Done"), max(1_000L, remaining))
                        if (choice == null) null else WaitMatch(store.latest.value ?: before, ChangeResult(0.0, emptyList()))
                    }
                    "value" -> withTimeoutOrNull(remaining) { watchValue(reader!!, valueTest!!, before, roi, a) { lastRead = it } }
                    else -> withTimeoutOrNull(remaining) { watch(condition, before, roi, threshold, forMs) }
                }
                val result = waitResult(condition, roi, threshold, start, before, match, ctx)
                val last = lastRead
                if (condition != "value" || last == null) result
                else CapResult(JsonObject(result.json + mapOf(
                    "text" to JsonPrimitive(last.text),
                    "value" to (last.value?.value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                )), result.image)
            }
        }
    }

    /** Counts a long call against [LookoutCore.MAX_WAITS]. */
    private suspend inline fun <T> guardedWait(block: () -> T): T {
        if (core.activeWaits.incrementAndGet() > LookoutCore.MAX_WAITS) {
            core.activeWaits.decrementAndGet()
            throw LookoutError("too_many_waits", 429, "At most ${LookoutCore.MAX_WAITS} wait_for and read calls can run at once")
        }
        try {
            return block()
        } finally {
            core.activeWaits.decrementAndGet()
        }
    }

    /** The match test for wait_for(condition=value). */
    private fun valueTest(a: Args): (Reading) -> Boolean {
        val above = a.double("above")
        val below = a.double("below")
        val text = a.string("text")?.trim()?.takeIf { it.isNotEmpty() }
        if (above == null && below == null && text == null) throw LookoutError("missing_param", 400, "value needs above, below or text")
        return { r ->
            val v = r.value?.value
            (above == null || (v != null && v > above)) &&
                (below == null || (v != null && v < below)) &&
                (text == null || r.text.contains(text, ignoreCase = true))
        }
    }

    private suspend fun watchValue(reader: TextReader, test: (Reading) -> Boolean, before: Frame, roi: RoiRef?, a: Args, onRead: (Reading) -> Unit): WaitMatch {
        val every = (a.long("every_ms") ?: 1_000L).coerceAtLeast(MIN_READ_EVERY_MS)
        val confirm = (a.int("confirm") ?: 2).coerceIn(1, 10)
        var frame = before
        var hits = 0
        while (true) {
            val r = readFrame(reader, frame, roi)
            onRead(r)
            hits = if (test(r)) hits + 1 else 0
            if (hits >= confirm) {
                core.events.add("value", buildJsonObject {
                    put("frame_id", frame.id)
                    put("text", r.text)
                    r.value?.let { put("value", it.value) }
                    roi?.let { put("roi", it.json()) }
                })
                return WaitMatch(frame, ChangeResult(0.0, emptyList()))
            }
            val next = frame.capturedAtMs + every
            kotlinx.coroutines.delay(max(0L, next - clock.now()))
            val prevId = frame.id
            frame = store.await(FRAME_TIMEOUT_MS) { it.id > prevId } ?: frame
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
        store.keep(before)
        store.keep(latest)
        val image = if (match != null) {
            comp.sideBySide(
                captioned(comp.crop(before.image, rect), before.capturedAtMs), "before ${clockTime(before.capturedAtMs)} #${before.id}",
                captioned(comp.crop(match.frame.image, rect), match.frame.capturedAtMs), "after ${clockTime(match.frame.capturedAtMs)} #${match.frame.id}",
                match.change.regions,
            )
        } else {
            captioned(comp.fit(comp.crop(latest.image, rect), Compositor.DEFAULT_EDGE), latest.capturedAtMs)
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
            put("image_url", ctx.url(framePath(id)))
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
            val ev = core.events.since(0, setOf("motion", "still", "change", "moved")).filter { it.ts in since..until }.map { it.ts }
            if (ev.size <= n) ev else List(n) { ev[it * ev.size / n] }
        } else {
            val from = max(since, frames.first().capturedAtMs)
            val to = min(until, frames.last().capturedAtMs)
            List(n) { i -> from + ((i + 0.5) * (to - from) / n).toLong() }
        }
        val picked = targets.map { t -> frames.minBy { kotlin.math.abs(it.capturedAtMs - t) } }.distinctBy { it.id }
        if (picked.isEmpty()) throw LookoutError("no_history", 404, "No motion events in that window. Try without events_only.")
        val tiles = picked.map { captioned(comp.crop(ops.decode(it.jpeg), roi?.rect), it.capturedAtMs) to "${clockTime(it.capturedAtMs)} #${it.id}" }
        val sheet = comp.contactSheet(tiles)
        val jpeg = ops.encodeJpeg(sheet, core.settings.value.jpegQuality)
        val id = core.composites.put(jpeg)
        core.recordServed("history", ctx, jpeg.size, roi?.label())
        return CapResult(buildJsonObject {
            put("since", isoTime(since))
            put("until", isoTime(until))
            put("events_only", eventsOnly)
            roi?.let { put("roi", it.json()) }
            put("tile_columns", buildJsonArray { add("frame_id"); add("captured_at") })
            putJsonArray("tiles") { picked.forEach { f -> add(buildJsonArray { add(f.id); add(isoTime(f.capturedAtMs)) }) } }
            put("width", sheet.width)
            put("height", sheet.height)
            put("image_id", id)
            put("image_url", ctx.url(framePath(id)))
        }, ImageOut(jpeg, id))
    }

    private suspend fun diff(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val aKey = a.string("a")!!
        val bKey = a.string("b")
        val threshold = a.double("threshold") ?: (LookoutCore.CHANGE_THRESHOLD / core.settings.value.sensitivity)
        val wantImage = a.bool("image") ?: true
        val explicitMmPerPx = a.double("mm_per_px")?.also { if (it <= 0) throw LookoutError("invalid_param", 400, "mm_per_px must be above 0") }
        val roi = resolveRoi(a.raw("roi")) ?: core.baselines.get(aKey)?.roi?.let { resolveRoi(JsonPrimitive(it)) }
        val left = resolveImage(aKey)
        val right = if (bKey != null) resolveImage(bKey) else core.session.use {
            val f = obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
            ResolvedImage(f.image, "now #${f.id}", f.id, f.capturedAtMs, f.camera.tilt, f.camera.lens, f.camera.zoom, "analysis")
        }
        // The shift is in pixels of a, and b must show the same scale for it to mean a distance.
        val storedScale = if (explicitMmPerPx == null) validScale(left.lens, left.zoom)?.takeIf { validScale(right.lens, right.zoom) != null } else null
        val mmPerPx = explicitMmPerPx ?: storedScale?.mmPerPx(left.image.width)
        val rect = roi?.rect
        val camera = Shift.camera(ops, left.image, right.image, rect)
        // The part of the view that both frames show after the camera shift. Without a shift it is the roi.
        val view = camera?.let { Shift.overlap(rect ?: NRect.FULL, it) }
        val ra = view?.first ?: rect
        val rb = view?.second ?: rect
        val change = core.detector.compare(core.detector.thumb(left.image, ra ?: NRect.FULL), core.detector.thumb(right.image, rb ?: NRect.FULL))
        val content = if (rect != null) Shift.content(ops, left.image, ra ?: rect, right.image, camera) else null
        val out = if (wantImage) {
            val image = comp.sideBySide(
                captioned(comp.crop(left.image, ra), left.capturedAtMs), "a: ${left.label}",
                captioned(comp.crop(right.image, rb), right.capturedAtMs), "b: ${right.label}",
                change.regions,
            )
            val jpeg = ops.encodeJpeg(image, core.settings.value.jpegQuality)
            val id = core.composites.put(jpeg)
            core.recordServed("diff", ctx, jpeg.size, roi?.label())
            ImageOut(jpeg, id)
        } else null
        return CapResult(buildJsonObject {
            put("score", change.score)
            put("changed", change.score > threshold)
            put("threshold", threshold)
            put("regions", regionsJson(change.regions, ra))
            put("aligned", camera != null)
            val aw = left.image.width
            val ah = left.image.height
            camera?.let { s -> putJsonArray("global_shift_px") { add(s.dx * aw); add(s.dy * ah) } }
            content?.let { c ->
                if (c.score >= Shift.MIN_CONTENT) {
                    put("dx", c.dx * aw)
                    put("dy", c.dy * ah)
                    mmPerPx?.let {
                        put("dx_mm", c.dx * aw * it)
                        put("dy_mm", c.dy * ah * it)
                        put("mm_per_px", it)
                        if (storedScale != null) put("scale", "stored")
                    }
                }
                put("shift_confidence", c.score)
            }
            put("a", left.label)
            put("b", right.label)
            val lt = left.tilt
            val rt = right.tilt
            if (lt != null && rt != null) {
                val moved = lt.angleTo(rt)
                put("tilt_change_deg", kotlin.math.round(moved * 10) / 10)
                put("camera_moved", moved > LookoutCore.MOVED_DEG)
            }
            roi?.let { put("roi", it.json()) }
            out?.id?.let { id ->
                put("image_id", id)
                put("image_url", ctx.url(framePath(id)))
            }
        }, out)
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

    private suspend fun scaleTool(a: Args): CapResult = when (a.string("action")) {
        "get" -> CapResult(scaleJson(core.scale.get()))
        "clear" -> {
            val cleared = core.scale.clear()
            core.events.add("scale", buildJsonObject { put("cleared", cleared) })
            CapResult(buildJsonObject { put("cleared", cleared) })
        }
        else -> {
            val fromSpec = a.string("from") ?: throw LookoutError("missing_param", 400, "set needs from")
            val toSpec = a.string("to") ?: throw LookoutError("missing_param", 400, "set needs to")
            val mm = a.double("mm") ?: throw LookoutError("missing_param", 400, "set needs mm")
            val grid = GridSpec.parse(a.string("grid") ?: GridSpec.DEFAULT.toString())
            val from = ScaleCalibration.parsePoint(fromSpec, grid)
            val to = ScaleCalibration.parsePoint(toSpec, grid)
            val frame = store.latest.value ?: core.session.use {
                obtainFrame(fresh = false, afterId = null, afterMs = null, settleMs = null).frame
            }
            // The points are in this frame, so the scale holds for the lens and zoom it was taken with.
            val tilt = core.source.tilt() ?: frame.camera.tilt
            val saved = core.scale.set(
                ScaleCalibration.fromPoints(from, to, mm, frame.image.width, frame.image.height, frame.camera.lens, frame.camera.zoom, tilt, clock.now()),
            )
            core.events.add("scale", buildJsonObject { put("width_mm", saved.widthMm); put("mm", saved.mm) })
            CapResult(scaleJson(saved))
        }
    }

    private fun currentLens(): String? = core.cameraSettings.lens ?: store.latest.value?.camera?.lens ?: core.source.lenses().firstOrNull()?.id

    private fun currentZoom(): Double = core.cameraSettings.zoom ?: store.latest.value?.camera?.zoom ?: 1.0

    /** The stored scale when it holds for pixels taken with [lens] and [zoom]. Unknown lens or zoom gives none. */
    private fun validScale(lens: String?, zoom: Double?): ScaleCalibration? =
        if (lens == null || zoom == null) null else core.scale.get()?.takeIf { it.staleReason(lens, zoom) == null }

    private fun scaleJson(sc: ScaleCalibration?): JsonObject = buildJsonObject {
        if (sc == null) {
            put("scale", JsonNull)
            return@buildJsonObject
        }
        val reason = sc.staleReason(currentLens(), currentZoom())
        putJsonObject("scale") {
            put("width_mm", sc.widthMm)
            put("height_mm", sc.heightMm)
            put("from", LookoutJson.encodeToJsonElement(sc.from))
            put("to", LookoutJson.encodeToJsonElement(sc.to))
            put("mm", sc.mm)
            put("lens", sc.lens)
            put("zoom", sc.zoom)
            sc.tilt?.let { putJsonObject("tilt") { put("pitch_deg", it.pitchDeg); put("roll_deg", it.rollDeg) } }
            put("set_at", isoTime(sc.setAt))
        }
        put("valid", reason == null)
        reason?.let { put("stale_reason", it) }
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
            val force = a.bool("force") == true
            core.session.use {
                val shot = sharpStill(roi, null, null)
                val still = shot.frame
                fun JsonObjectBuilder.shotFields() {
                    if (shot.moving) put("moving", true)
                    put("sharpness", shot.sharpness)
                    put("burst", shot.burst)
                }
                if (isDark(still, force)) {
                    return@use CapResult(buildJsonObject {
                        put("saved", false)
                        put("dark", true)
                        shotFields()
                        put("hint", DARK_HINT)
                    })
                }
                val preview = ops.encodeJpeg(comp.fit(comp.crop(still.image, roi?.rect), Compositor.DEFAULT_EDGE), core.settings.value.jpegQuality)
                core.recordServed("baseline", ctx, preview.size, roi?.label())
                val blur = if (force) null else blurHint(shot)
                CapResult(buildJsonObject {
                    if (blur == null) put("baseline", LookoutJson.encodeToJsonElement(saveBaseline(name, still, roi)))
                    else {
                        put("saved", false)
                        put("blurred", true)
                        put("hint", blur)
                    }
                    shotFields()
                }, ImageOut(preview))
            }
        }
    }

    // ---- recordings ----

    private suspend fun recordTool(a: Args, ctx: CallContext): CapResult {
        val rec = core.recordings
        return when (a.string("action")!!) {
            "start" -> {
                if (core.paused.value) throw LookoutError("paused", 503, "Lookout is paused on the phone")
                val duration = a.long("duration_ms") ?: Recordings.DEFAULT_DURATION_MS
                if (duration !in 500..Recordings.MAX_DURATION_MS) {
                    throw LookoutError("invalid_param", 400, "duration_ms must be 500..${Recordings.MAX_DURATION_MS}")
                }
                val audio = a.bool("audio") == true
                if (audio) rec.checkAudio()
                val spec = RecordSpec(a.int("fps") ?: 30, VideoResolution.parse(a.string("resolution") ?: VideoResolution.FHD.wire), audio)
                val roi = resolveRoi(a.raw("roi"))
                val guide = guide(a, "record")
                suspend fun start(): CapResult {
                    val info = rec.start(spec, duration, roi, a.string("label"), currentLens(), currentZoom())
                    return CapResult(buildJsonObject {
                        put("recording", recordingJson(info, ctx))
                        put("server_time", isoTime(clock.now()))
                        put("next", "Trigger the event now. Call record(action=mark, label=...) as you act, or add marks with at after the end. " +
                            "Then call wait_for(condition=recording_done, recording_id=${info.id}). Add clip={...} to get a clip or trace directly, or sheet=false for the summary only.")
                    })
                }
                if (guide == null) start() else guided(record, guide, a, ctx) { start() }
            }
            "mark" -> markTool(a)
            "stop" -> {
                val info = rec.stop()
                CapResult(buildJsonObject {
                    put("recording_id", info.id)
                    put("state", "stopping")
                    put("next", "Call wait_for(condition=recording_done, recording_id=${info.id}). Add clip={...} to get a clip or trace directly, or sheet=false for the summary only.")
                })
            }
            else -> CapResult(buildJsonObject {
                rec.activeId?.let { put("active", it) }
                putJsonArray("recordings") {
                    rec.list().forEach { r ->
                        add(buildJsonObject {
                            put("recording_id", r.id)
                            put("state", r.state)
                            r.label?.let { put("label", it) }
                            put("started_at", isoTime(r.startedAt))
                            put("fps", r.fps)
                            put("resolution", r.resolution)
                            if (r.audio) put("audio", true)
                            r.durationMs?.let { put("duration_ms", it) }
                            put("activity_segments", r.activity.size)
                        })
                    }
                }
            })
        }
    }

    private fun markTool(a: Args): CapResult {
        val rec = core.recordings
        val info = rec.get(a.string("recording_id") ?: rec.activeId ?: rec.resolve(null).id)
        val list = a.objects("marks")
        if (list != null && (a.raw("label") != null || a.raw("at") != null)) throw LookoutError("invalid_param", 400, "Give marks, or label and at, not both")
        if (list != null && list.isEmpty()) throw LookoutError("invalid_param", 400, "marks is empty")
        val offset = a.string("client_now")?.let { clock.now() - (parseIso(it) ?: throw LookoutError("invalid_time", 400, "client_now must be ISO 8601, got '$it'")) }
        fun at(e: JsonElement?): Long? {
            val p = e?.takeUnless { it is JsonNull } ?: return null
            val text = (p as? JsonPrimitive)?.content ?: throw LookoutError("invalid_param", 400, "at must be an ISO time or t_ms")
            return text.toLongOrNull() ?: info.tMs((parseIso(text) ?: throw LookoutError("invalid_time", 400, "at must be ISO 8601 or t_ms, got '$text'")) + (offset ?: 0))
        }
        val wanted = list?.map { o ->
            val label = (o["label"] as? JsonPrimitive)?.content ?: throw LookoutError("missing_param", 400, "Each mark needs a label")
            Recordings.MarkAt(label, at(o["at"]))
        } ?: listOf(Recordings.MarkAt(a.string("label") ?: "mark", at(a.raw("at"))))
        val added = rec.mark(info.id, wanted)
        return CapResult(buildJsonObject {
            put("recording_id", info.id)
            putJsonArray("marks") {
                added.forEach { m -> add(buildJsonObject { put("t_ms", m.tMs); put("at", isoTime(info.startedAt + m.tMs)); put("label", m.label) }) }
            }
            offset?.let { put("clock_offset_ms", it) }
        })
    }

    /** The agent-facing summary of a recording. Fields at their usual value are left out. */
    fun recordingJson(r: RecordingInfo, ctx: CallContext): JsonObject = buildJsonObject {
        put("recording_id", r.id)
        put("state", r.state)
        r.label?.let { put("label", it) }
        put("started_at", isoTime(r.startedAt))
        put("time_precision_ms", r.precisionMs)
        val requested = requestedDiff(r)
        if (requested.isNotEmpty()) put("requested", requested)
        if (r.state == "recording") put("ends_at", isoTime(r.startedAt + r.plannedMs))
        if (r.width > 0) {
            put("width", r.width)
            put("height", r.height)
        }
        if (r.audio) put("audio", true)
        if (!r.videoCrop.isFull) put("video_crop", LookoutJson.encodeToJsonElement(r.videoCrop))
        r.roi?.let { put("roi", r.roiName?.let(::JsonPrimitive) ?: LookoutJson.encodeToJsonElement(it)) }
        r.durationMs?.let { put("duration_ms", it) }
        r.frames?.let { put("frames", it) }
        r.actualFps?.let { put("actual_fps", it) }
        r.dropped?.takeIf { it > 0 }?.let { put("dropped", it) }
        r.finalizeMs?.let { put("finalize_ms", it) }
        r.motionSource?.let { put("motion_source", it) }
        r.endedBy?.takeIf { it != "duration" }?.let { put("ended_by", it) }
        if (r.truncated) put("truncated", true)
        r.error?.let { put("error", it) }
        if (r.marks.isNotEmpty()) putJsonArray("marks") {
            r.marks.forEach { m -> add(buildJsonObject { put("t_ms", m.tMs); put("label", m.label) }) }
        }
        if (r.state == "ready") {
            if (r.activity.isEmpty()) put("quiet", true)
            else {
                put("activity_columns", ACTIVITY_COLUMNS)
                putJsonArray("activity") {
                    r.activity.forEach { s -> add(buildJsonArray { add(s.fromMs); add(s.toMs); add(s.peak); add(s.peakMs) }) }
                }
            }
            r.sizeBytes?.let { put("size_bytes", it) }
            put("video_url", ctx.url(videoPath(r.id)))
        }
    }

    /** The requested fps, resolution and duration that the recording did not reach. Unknown actual values count as different. */
    private fun requestedDiff(r: RecordingInfo): JsonObject = buildJsonObject {
        if (r.actualFps?.roundToInt() != r.fps) put("fps", r.fps)
        val res = VideoResolution.entries.firstOrNull { it.wire == r.resolution }
        if (res == null || maxOf(r.width, r.height) != res.width || minOf(r.width, r.height) != res.height) put("resolution", r.resolution)
        val d = r.durationMs
        if (d == null || kotlin.math.abs(d - r.plannedMs) > DURATION_SLACK_FRAMES * 1000L / r.fps) put("duration_ms", r.plannedMs)
    }

    private class ClipSpec(
        val mode: String,
        val fromMs: Long?,
        val toMs: Long?,
        val tiles: Int,
        val motion: Boolean,
        val atMs: List<Long>?,
        val at: List<String>?,
        val atMarks: Boolean,
        val offsetMs: Long,
        val hasRoi: Boolean,
        val roi: RoiRef?,
        val width: Int?,
        val threshold: Int?,
        val raw: Boolean,
        val axis: String?,
        val edge: Track.Edge,
        val mmPerPx: Double?,
        val image: Boolean,
    )

    private fun parseClip(a: Args, validate: Boolean = false): ClipSpec {
        if (validate) validateParams(clipOptions, a)
        val mode = a.string("mode") ?: "sheet"
        val fromMs = a.long("from_ms")
        val toMs = a.long("to_ms")
        val tiles = a.int("tiles") ?: SHEET_TILES
        if (tiles !in 1..MAX_CLIP_TILES) throw LookoutError("invalid_param", 400, "tiles must be 1..$MAX_CLIP_TILES")
        val motion = a.string("pick") == "motion"
        val atMs = a.longs("at_ms")
        if (atMs != null && atMs.size !in 1..MAX_CLIP_TILES) throw LookoutError("invalid_param", 400, "at_ms needs 1..$MAX_CLIP_TILES times")
        val at = a.strings("at")
        if (at != null) {
            if (at.size !in 1..MAX_CLIP_TILES) throw LookoutError("invalid_param", 400, "at needs 1..$MAX_CLIP_TILES times")
            val now = clock.now()
            at.forEach { parseTime(it, now) }
        }
        val atMarks = a.bool("at_marks") == true
        if (atMarks && (atMs != null || at != null)) throw LookoutError("invalid_param", 400, "Give one of at_ms, at and at_marks")
        val offsetMs = a.long("offset_ms") ?: 0L
        if (offsetMs != 0L && !atMarks) throw LookoutError("invalid_param", 400, "offset_ms needs at_marks=true")
        val rawRoi = a.raw("roi")
        val roi = rawRoi?.let { resolveRoi(it) }?.takeUnless { it.rect.isFull }
        val width = a.int("width")?.coerceIn(16, Compositor.MAX_EDGE)
        val threshold = a.int("threshold")?.also { if (it !in 0..255) throw LookoutError("invalid_param", 400, "threshold must be 0..255") }
        val raw = a.bool("raw") == true
        val edge = Track.Edge.parse(a.string("edge") ?: Track.Edge.STRONGEST.wire)
        val mmPerPx = a.double("mm_per_px")?.also { if (it <= 0) throw LookoutError("invalid_param", 400, "mm_per_px must be above 0") }
        return ClipSpec(
            mode, fromMs, toMs, tiles, motion, atMs, at, atMarks, offsetMs, rawRoi != null, roi, width, threshold, raw,
            a.string("axis"), edge, mmPerPx, a.bool("image") == true,
        )
    }

    private suspend fun recordingDone(a: Args, ctx: CallContext, start: Long, timeout: Long, clipSpec: ClipSpec?, wantSheet: Boolean): CapResult {
        val rec = core.recordings
        val id = a.string("recording_id") ?: rec.activeId ?: rec.resolve(null).id
        val current = rec.get(id)
        if (clipSpec?.roi != null) current.videoRoi(clipSpec.roi.rect)
        if (clipSpec?.mode == "audio" && !current.audio) {
            throw LookoutError("no_audio", 404, "Recording ${current.id} has no sound track. Record with audio=true.")
        }
        val info = rec.awaitDone(id, timeout, exact = a.bool("exact") == true)
        val json = buildJsonObject {
            put("matched", info != null)
            put("condition", "recording_done")
            put("waited_ms", clock.now() - start)
            put("recording", recordingJson(info ?: rec.get(id), ctx))
            if (info == null) put("next", "Still running. Call wait_for(condition=recording_done) again.")
        }
        if (info == null || info.state != "ready") return CapResult(json)
        if (clipSpec != null) {
            val c = renderClip(info, clipSpec, ctx, "wait_for")
            return CapResult(JsonObject(json + ("clip" to c.json)), c.image)
        }
        if (!wantSheet || info.activity.isEmpty()) return CapResult(json)
        val idx = rec.index(info)
        val roi = info.roi?.let { RoiRef(info.roiName, it, null) }
        val picks = pickFrames(idx, idx.ptsUs.indices.toList(), SHEET_TILES, motion = true)
        val sheet = sheet(info, idx, picks, roi, Compositor.MAX_EDGE, "wait_for", ctx)
        return CapResult(JsonObject(json + sheet.json), sheet.image)
    }

    private suspend fun clipTool(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val info = core.recordings.resolve(a.string("recording_id"))
        return renderClip(info, parseClip(a), ctx, "clip")
    }

    private suspend fun renderClip(info: RecordingInfo, spec: ClipSpec, ctx: CallContext, tool: String): CapResult = withContext(Dispatchers.Default) {
        val idx = core.recordings.index(info)
        if (spec.mode == "audio") return@withContext audioClip(info, idx, spec)
        val roi = if (spec.hasRoi) spec.roi else info.roi?.let { RoiRef(info.roiName, it, null) }
        val last = idx.tMs(idx.ptsUs.size - 1)
        val from = (spec.fromMs ?: 0L).coerceIn(0L, last)
        val to = (spec.toMs ?: last).coerceIn(from, last)
        val window = idx.ptsUs.indices.filter { idx.tMs(it) in from..to }.ifEmpty { listOf(idx.nearest(from)) }
        if (spec.mode == "trace") return@withContext trace(info, idx, window, roi, spec)
        if (spec.mode == "track") return@withContext track(info, idx, window, roi, spec, tool, ctx)
        val marks = if (spec.atMarks) markedFrames(info, idx, spec.offsetMs) else emptyMap()
        val atMs = spec.atMs ?: spec.at?.map { info.tMs(parseTime(it, clock.now())) }
        val picks = when {
            spec.atMarks -> marks.keys.sorted()
            atMs != null -> atMs.map { idx.nearest(it) }.distinct().sorted()
            else -> pickFrames(idx, window, spec.tiles, motion = spec.motion)
        }
        val width = spec.width ?: if (picks.size == 1) Compositor.DEFAULT_EDGE else Compositor.MAX_EDGE
        val sheet = sheet(info, idx, picks, roi, width, tool, ctx, marks)
        val (vw, vh) = videoSize(info)
        val px = (info.videoRoi(roi?.rect) ?: NRect.FULL).toPixels(vw, vh)
        val hint = hints.sheet(ctx.key, picks.size, max(px.w, px.h).toDouble() / max(1, min(px.w, px.h)))
        CapResult(buildJsonObject {
            put("recording_id", info.id)
            put("started_at", isoTime(info.startedAt))
            put("from_ms", from)
            put("to_ms", to)
            put("frames_in_window", window.size)
            put("frame_interval_ms", frameMs(info))
            sheet.json.forEach { (k, v) -> put(k, v) }
            hint?.let { put("hint", it) }
        }, sheet.image)
    }

    /** The frame at each mark plus [offsetMs], with the labels of the marks that land on it. */
    private fun markedFrames(info: RecordingInfo, idx: ClipIndex, offsetMs: Long): Map<Int, String> {
        if (info.marks.isEmpty()) throw LookoutError("invalid_param", 400, "Recording ${info.id} has no marks. Add them with record(action=mark), with at for past times.")
        if (info.marks.size > MAX_CLIP_TILES) throw LookoutError("invalid_param", 400, "at_marks gives ${info.marks.size} tiles, max $MAX_CLIP_TILES. Use at_ms for a subset.")
        return info.marks.groupBy({ idx.nearest(it.tMs + offsetMs) }, { it.label }).mapValues { it.value.joinToString(", ") }
    }

    private fun frameMs(info: RecordingInfo): Double = ((1000.0 / (info.actualFps ?: info.fps.toDouble())) * 10).roundToInt() / 10.0

    /** [n] frames from [window]: evenly spaced, or the strongest motion peaks (filled with even frames if there are few). */
    private fun pickFrames(idx: ClipIndex, window: List<Int>, n: Int, motion: Boolean): List<Int> {
        fun even(k: Int): List<Int> = if (window.size <= k) window else if (k == 1) listOf(window[window.size / 2]) else List(k) { window[it * (window.size - 1) / (k - 1)] }
        if (!motion) return even(n)
        val threshold = store.motionThreshold
        val radius = max(1, window.size / (n * 2))
        val picked = mutableListOf<Int>()
        for (i in window.sortedByDescending { idx.motion[it] }) {
            if (picked.size >= n || idx.motion[i] <= threshold) break
            if (picked.none { kotlin.math.abs(it - i) < radius }) picked += i
        }
        if (picked.size < n) {
            for (i in even(n)) if (picked.size < n && picked.none { kotlin.math.abs(it - i) < radius }) picked += i
        }
        return picked.sorted()
    }

    private class Sheet(val json: JsonObject, val image: ImageOut)

    /** A contact sheet of [picks]. [marks] gives mark labels by frame index, for the tile labels. */
    private suspend fun sheet(
        info: RecordingInfo, idx: ClipIndex, picks: List<Int>, roi: RoiRef?, width: Int, tool: String, ctx: CallContext,
        marks: Map<Int, String> = emptyMap(),
    ): Sheet =
        withContext(Dispatchers.Default) {
            val videoRoi = info.videoRoi(roi?.rect)
            val wanted = picks.associateBy { idx.ptsUs[it] }
            val edge = if (picks.size == 1) width else sheetTileEdge(info, videoRoi, picks.size, width)
            val images = HashMap<Int, Img>()
            core.recordings.decoder.decode(
                core.recordings.file(info), idx.ptsUs[picks.first()], idx.ptsUs[picks.last()],
                { pts -> if (pts in wanted) FrameRequest(videoRoi, edge) else null },
            ) { pts, img -> wanted[pts]?.let { i -> if (img != null) images[i] = img } }
            val shown = picks.filter { it in images }
            if (shown.isEmpty()) throw LookoutError("clip_decode_failed", 500, "Could not decode frames from ${info.id}")
            for (i in shown) images[i] = captioned(images[i]!!, info.startedAt + idx.tMs(i))
            // t_ms first: a label too wide for its tile loses words from the end.
            fun label(i: Int) = listOfNotNull("t=${idx.tMs(i)}ms #$i", marks[i]?.let { if (it.length > MARK_LABEL) it.take(MARK_LABEL - 1) + "…" else it }).joinToString("  ")
            val first = images[shown[0]]!!
            val img = if (shown.size == 1) {
                comp.stamp(comp.fit(first, width), "${info.id}  ${label(shown[0])}  ${roi?.label() ?: ""}".trim())
            } else {
                comp.contactSheet(shown.map { images[it]!! to label(it) }, width)
            }
            // The same grid that contactSheet draws, so a script can cut the tiles out.
            val layout = if (shown.size == 1) null else Compositor.sheetLayout(shown.size, first.height.toDouble() / first.width, width)
            val jpeg = ops.encodeJpeg(img, core.settings.value.jpegQuality)
            val id = core.composites.put(jpeg)
            core.recordServed(tool, ctx, jpeg.size, roi?.label())
            Sheet(buildJsonObject {
                put("tile_columns", if (marks.isEmpty()) TILE_COLUMNS else JsonArray(TILE_COLUMNS + JsonPrimitive("label")))
                putJsonArray("tiles") {
                    shown.forEach { i -> add(buildJsonArray { add(idx.tMs(i)); add(i); add(idx.motion[i]); marks[i]?.let { add(it) } }) }
                }
                roi?.let { put("roi", it.json()) }
                layout?.let { l ->
                    val (vw, vh) = videoSize(info)
                    putJsonObject("layout") {
                        put("cols", l.cols)
                        put("rows", l.rows)
                        put("tile_w", l.tileW)
                        put("tile_h", l.tileH)
                        put("band", l.band)
                        put("gap", Compositor.GAP)
                        put("px_per_source", l.tileW.toDouble() / (videoRoi ?: NRect.FULL).toPixels(vw, vh).w)
                    }
                }
                put("image_id", id)
                put("image_url", ctx.url(framePath(id)))
            }, ImageOut(jpeg, id))
        }

    /** The long edge of each tile when [n] tiles of [videoRoi] go in a sheet of [width]. Throws when the tiles would be too small to read. */
    private fun sheetTileEdge(info: RecordingInfo, videoRoi: NRect?, n: Int, width: Int): Int {
        val r = videoRoi ?: NRect.FULL
        val (w, h) = videoSize(info)
        val aspect = (r.h * h) / (r.w * w)
        val layout = Compositor.sheetLayout(n, aspect, width)
        if (layout.tileEdge < Compositor.MIN_TILE_EDGE) {
            throw LookoutError("invalid_param", 400,
                "$n tiles do not fit: at most ${Compositor.maxTiles(aspect, width, MAX_CLIP_TILES)} fit for this roi shape at width $width, " +
                    "each at least ${Compositor.MIN_TILE_EDGE} px on the long edge. Ask for fewer times, or a narrower window.")
        }
        return layout.tileEdge
    }

    /** The upright size of the video frames of [info], or 1080p for an old recording without one. */
    private fun videoSize(info: RecordingInfo): Pair<Int, Int> =
        if (info.width > 0 && info.height > 0) info.width to info.height else VideoResolution.FHD.width to VideoResolution.FHD.height

    /** mode=track: follows one edge through [window] at the full resolution of the roi. See [Track]. */
    private fun track(info: RecordingInfo, idx: ClipIndex, window: List<Int>, roi: RoiRef?, spec: ClipSpec, tool: String, ctx: CallContext): CapResult {
        val videoRoi = info.videoRoi(roi?.rect) ?: NRect.FULL
        val (vw, vh) = videoSize(info)
        val src = videoRoi.toPixels(vw, vh)
        if (src.w.toLong() * src.h > MAX_TRACK_PX) {
            throw LookoutError("invalid_param", 400,
                "mode=track needs a thin roi across the path of the edge. This one is ${src.w} x ${src.h} video px; keep it under $MAX_TRACK_PX px, for example 1080 x 40.")
        }
        val alongX = spec.axis?.let { it == "x" } ?: (src.w >= src.h)
        val length = if (alongX) src.w else src.h
        val slot = window.withIndex().associate { (n, i) -> idx.ptsUs[i] to n }
        val tracker = Track.Tracker(spec.edge)
        val points = ArrayList<Track.Point>(window.size)
        // One line of pixels per frame: time runs down for axis x and across for axis y, so space keeps its direction.
        val stack = if (spec.image) (if (alongX) ops.canvas(length, window.size, Colors.BLACK) else ops.canvas(window.size, length, Colors.BLACK)) else null
        var decodedLength = length
        core.recordings.decoder.decode(
            core.recordings.file(info), idx.ptsUs[window.first()], idx.ptsUs[window.last()],
            { pts -> if (pts in slot) FrameRequest(videoRoi, max(src.w, src.h)) else null },
        ) { pts, img ->
            val n = slot[pts] ?: return@decode
            if (img == null) return@decode
            val profile = Track.profile(ops.argb(img, img.width, img.height), img.width, img.height, alongX)
            decodedLength = profile.size
            val toSource = length.toDouble() / profile.size
            val (f, best) = tracker.next(profile)
            points += Track.Point((pts - idx.firstUs) / 1000, f?.pos?.times(toSource), f?.contrast ?: best)
            if (stack != null) {
                if (alongX) ops.drawImage(stack, ops.scale(img, length, 1), 0, n) else ops.drawImage(stack, ops.scale(img, 1, length), n, 0)
            }
        }
        if (points.isEmpty()) throw LookoutError("clip_decode_failed", 500, "Could not decode frames from ${info.id}")
        val from = points.first().tMs
        val to = points.last().tMs
        val marks = info.marks.filter { it.tMs in from..to }
        val crop = info.videoCrop
        // Source px along the axis to the normalized look-frame coordinate.
        fun look(px: Double) = if (alongX) crop.x + (src.x + px) / vw * crop.w else crop.y + (src.y + px) / vh * crop.h
        val storedScale = if (spec.mmPerPx == null) validScale(info.lens, info.zoom) else null
        val mmPerPx = spec.mmPerPx ?: storedScale?.mmPerPx(vw, crop)
        val result = Track.analyze(points, 1000.0 / (info.actualFps ?: info.fps.toDouble()), marks, mmPerPx, ::look, spec.raw)
        val json = buildJsonObject {
            put("recording_id", info.id)
            put("mode", "track")
            roi?.let { put("roi", it.json()) }
            put("axis", if (alongX) "x" else "y")
            put("edge", spec.edge.wire)
            put("from_ms", from)
            put("to_ms", to)
            put("length_px", length)
            put("px_per_source", decodedLength.toDouble() / length)
            putJsonArray("look_span") { add(look(0.0)); add(look(length.toDouble())) }
            if (storedScale != null) put("scale", "stored")
            result.forEach { (k, v) -> put(k, v) }
        }
        if (stack == null) return CapResult(json)
        val labels = marks.map { it.tMs to if (it.label.length > MARK_LABEL) it.label.take(MARK_LABEL - 1) + "…" else it.label }
        val img = comp.timeStack(stack, alongX, from, to, labels, spec.width ?: Compositor.MAX_EDGE)
        val jpeg = ops.encodeJpeg(img, core.settings.value.jpegQuality)
        val id = core.composites.put(jpeg)
        core.recordServed(tool, ctx, jpeg.size, roi?.label())
        return CapResult(buildJsonObject {
            json.forEach { (k, v) -> put(k, v) }
            put("image_time", if (alongX) "down" else "across")
            put("image_id", id)
            put("image_url", ctx.url(framePath(id)))
        }, ImageOut(jpeg, id))
    }

    private fun trace(info: RecordingInfo, idx: ClipIndex, window: List<Int>, roi: RoiRef?, spec: ClipSpec): CapResult {
        val videoRoi = info.videoRoi(roi?.rect)
        val wanted = window.toHashSet().map { idx.ptsUs[it] }.toHashSet()
        val samples = ArrayList<Trace.Sample>(window.size)
        core.recordings.decoder.decode(
            core.recordings.file(info), idx.ptsUs[window.first()], idx.ptsUs[window.last()],
            { pts -> if (pts in wanted) FrameRequest(videoRoi, TRACE_EDGE) else null },
        ) { pts, img ->
            if (img == null || pts !in wanted) return@decode
            val w = min(img.width, TRACE_EDGE)
            val h = min(img.height, TRACE_EDGE)
            val px = ops.argb(img, w, h)
            var r = 0L; var g = 0L; var b = 0L
            for (p in px) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
            samples += Trace.Sample((pts - idx.firstUs) / 1000, (r / px.size).toInt(), (g / px.size).toInt(), (b / px.size).toInt())
        }
        if (samples.isEmpty()) throw LookoutError("clip_decode_failed", 500, "Could not decode frames from ${info.id}")
        val result = Trace.analyze(samples, 1000.0 / (info.actualFps ?: info.fps.toDouble()), spec.threshold, spec.raw)
        return CapResult(buildJsonObject {
            put("recording_id", info.id)
            put("mode", "trace")
            roi?.let { put("roi", it.json()) }
            put("from_ms", samples.first().tMs)
            put("to_ms", samples.last().tMs)
            result.forEach { (k, v) -> put(k, v) }
        })
    }

    private fun audioClip(info: RecordingInfo, idx: ClipIndex, spec: ClipSpec): CapResult {
        if (!info.audio) throw LookoutError("no_audio", 404, "Recording ${info.id} has no sound track. Record with audio=true.")
        val totalMs = max(info.durationMs ?: 0L, idx.tMs(idx.ptsUs.size - 1))
        val from = (spec.fromMs ?: 0L).coerceIn(0L, totalMs)
        val to = spec.toMs?.coerceIn(from, totalMs)
        val fromUs = idx.firstUs + from * 1000L
        val toUs = if (to != null) idx.firstUs + to * 1000L else Long.MAX_VALUE
        val pcm = core.recordings.decoder.audio(core.recordings.file(info), fromUs, toUs)
            ?: throw LookoutError("no_audio", 404, "Recording ${info.id} has no sound track. Record with audio=true.")
        val endMs = from + pcm.size.toLong() * 1000L / pcm.sampleRate
        val marks = info.marks.filter { it.tMs in from..endMs }
        val result = Audio.analyze(pcm, from, marks, spec.raw)
        return CapResult(buildJsonObject {
            put("recording_id", info.id)
            put("mode", "audio")
            put("started_at", isoTime(info.startedAt))
            result.forEach { (k, v) -> put(k, v) }
        })
    }

    // ---- shared helpers ----

    class Obtained(val frame: Frame, val settled: Boolean?, val convergeMs: Long = 0L, val settleMs: Long = 0L)

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
        val convergedAt = clock.now()
        val convergeMs = max(0L, convergedAt - start)
        if (settleMs == null || settleMs <= 0) return Obtained(frame, null, convergeMs, 0L)
        val deadline = convergedAt + settleMs + SETTLE_EXTRA_MS
        var latest = frame
        while (true) {
            val quietSince = max(store.lastMotionAtMs, if (fresh) start else 0L)
            if (latest.capturedAtMs - quietSince >= settleMs && latest.camera.aeConverged && latest.camera.afConverged) {
                return Obtained(latest, true, convergeMs, max(0L, clock.now() - convergedAt))
            }
            if (clock.now() > deadline) return Obtained(latest, false, convergeMs, max(0L, clock.now() - convergedAt))
            val prevId = latest.id
            latest = store.await(FRAME_TIMEOUT_MS) { it.id > prevId } ?: return Obtained(latest, false, convergeMs, max(0L, clock.now() - convergedAt))
        }
    }

    /** Where the time of one look went. The parts are reported when the call was slow. */
    private inner class Timing(val started: Long, val opened: CameraSession.OpenResult) {
        var convergeMs = 0L
        var settleMs = 0L
        var burstMs = 0L
        var stillMs = 0L

        fun waited(got: Obtained) {
            convergeMs = got.convergeMs
            settleMs = got.settleMs
        }

        suspend fun <T> still(take: suspend () -> T): T {
            val start = clock.now()
            return take().also { stillMs += max(0L, clock.now() - start) }
        }

        suspend fun <T> burst(take: suspend () -> T): T {
            val start = clock.now()
            return take().also { burstMs += max(0L, clock.now() - start) }
        }
    }

    /** The frame of one look. [from] is still or analysis. [sharpness] and [burst] are set when it is the best of a burst. */
    private class Shot(val frame: Frame, val from: String, val sharpness: Double? = null, val burst: Int? = null, val moving: Boolean = false)

    /** True when the preview frames saw motion within [MOTION_WINDOW_MS] of [atMs]. */
    private fun movingAt(atMs: Long): Boolean {
        val last = store.lastMotionAtMs
        return last > 0L && kotlin.math.abs(atMs - last) < MOTION_WINDOW_MS
    }

    /** True when the scene moved as [frame] was taken. After a settle, only motion later than the frame counts. */
    private fun moving(frame: Frame, settled: Boolean?) =
        if (settled == true) store.lastMotionAtMs > frame.capturedAtMs else movingAt(frame.capturedAtMs)

    private suspend fun stillShot(timing: Timing, settled: Boolean?): Shot {
        val frame = timing.still { store.registerStill(still()) }
        return Shot(frame, "still", moving = moving(frame, settled))
    }

    /** Looks at up to [SHARP_FAST_FRAMES] preview frames within [SHARP_FAST_MS], from [first], and keeps the sharpest on [roi]. */
    private suspend fun sharpFast(first: Frame, roi: RoiRef?, timing: Timing, settled: Boolean?): Shot = timing.burst {
        val deadline = clock.now() + SHARP_FAST_MS
        val motionBefore = store.lastMotionAtMs
        var best = first
        var bestScore = Sharpness.score(ops, first.image, roi?.rect)
        var count = 1
        var lastId = first.id
        while (count < SHARP_FAST_FRAMES) {
            val next = store.await(deadline - clock.now()) { it.id > lastId } ?: break
            lastId = next.id
            count++
            val s = Sharpness.score(ops, next.image, roi?.rect)
            if (s > bestScore) {
                best = next
                bestScore = s
            }
        }
        Shot(best, "analysis", bestScore, count, if (settled == true) store.lastMotionAtMs > motionBefore else movingAt(best.capturedAtMs))
    }

    /**
     * Takes [SHARP_STILLS] stills one after another and keeps the sharpest on [roi]. Only the best so far stays in memory.
     * It counts as moving when the preview saw motion during the burst, or just before it without a settle.
     */
    private suspend fun sharpStill(roi: RoiRef?, timing: Timing?, settled: Boolean?): Shot {
        val startMs = clock.now()
        val motionBefore = store.lastMotionAtMs
        suspend fun burst(): Pair<RawFrame, Double> {
            var best: Pair<RawFrame, Double>? = null
            repeat(SHARP_STILLS) {
                val raw = still()
                val s = Sharpness.score(ops, raw.image, roi?.rect)
                if (best == null || s > best!!.second) best = raw to s
            }
            return best!!
        }
        val (raw, score) = timing?.still { burst() } ?: burst()
        val moving = store.lastMotionAtMs > motionBefore || (settled != true && movingAt(startMs))
        return Shot(store.registerStill(raw), "still", score, SHARP_STILLS, moving)
    }

    /** Why [shot] is not fit to be a baseline, or null when it is: the scene moved, or autofocus had not converged. */
    private fun blurHint(shot: Shot): String? {
        val unfocused = store.latest.value?.camera?.afConverged == false
        val cause = when {
            shot.moving && unfocused -> "motion was detected and autofocus had not converged"
            shot.moving -> "motion was detected during capture"
            unfocused -> "autofocus had not converged"
            else -> return null
        }
        return "Baseline was not saved because $cause. Wait until the scene is steady, or pass force=true to save anyway."
    }

    /** True for a black view (lens covered, phone face down, no light), which is not worth the tokens of an image. */
    private fun isDark(frame: Frame, force: Boolean) = !force && frame.brightness < DARK_FRAME

    /** The result of a look. A [dark] frame gives the metadata and no image. [blurHint] says why a baseline was not saved. */
    private fun imageResult(
        tool: String, ctx: CallContext, shot: Shot, roi: RoiRef?, width: Int, a: Args,
        settled: Boolean?, timing: Timing, dark: Boolean, notSaved: Boolean = false, blurHint: String? = null,
    ): CapResult {
        val frame = shot.frame
        store.keep(frame)
        val score = shot.sharpness ?: Sharpness.score(ops, frame.image, roi?.rect)
        var encodeMs = 0L
        var outImg: Img? = null
        var imageOut: ImageOut? = null
        if (!dark) {
            val encodeStart = clock.now()
            val img = render(frame.image, frame.capturedAtMs, "#${frame.id}", roi, width, a)
            val jpeg = ops.encodeJpeg(img, quality(a))
            encodeMs = max(0L, clock.now() - encodeStart)
            core.recordServed(tool, ctx, jpeg.size, roi?.label())
            outImg = img
            imageOut = ImageOut(jpeg)
        }
        val totalMs = max(0L, clock.now() - timing.started)
        val lens = frame.camera.lens
        val lensMinFocus = lensOf(lens)?.minFocusDistance
        val atFocusLimit = !dark && focusAtLimit(frame.camera.focusDistance, lensMinFocus)
        val focusLimitHint = if (atFocusLimit) {
            val minMm = dioptersToMm(lensMinFocus)
            "Focus is at the lens close limit${minMm?.let { " ($it mm)" } ?: ""}. Move the phone back or switch to a lens with closer focus."
        } else null
        val meta = buildJsonObject {
            frameMeta(frame, outImg, roi, shot.from, ctx, a.bool("verbose") == true).forEach { (k, v) -> put(k, v) }
            outImg?.let { img ->
                validScale(lens, frame.camera.zoom)?.let { sc ->
                    put("mm_per_px", sc.mmPerPxFor(frame.image.width, frame.image.height, roi?.rect, img.width))
                }
            }
            if (atFocusLimit) put("focus_at_limit", true)
            put("sharpness", score)
            if (shot.moving) put("moving", true)
            shot.burst?.let { put("burst", it) }
            if (settled != null) put("settled", settled)
            if (dark) put("dark", true)
            if (blurHint != null) put("blurred", true)
            if (notSaved) put("saved", false)
            if (timing.opened.coldStart) put("cold_start", true)
            val hint = when {
                dark -> DARK_HINT
                blurHint != null -> blurHint
                focusLimitHint != null -> focusLimitHint
                else -> (if (timing.opened.coldStart) coldStartHint() else null) ?: MOVING_HINT.takeIf { shot.moving }
            }
            val look = Hints.Look(
                clock.now(), roi?.label(), frame.id, still = shot.from == "still" && a.raw("save_as") == null,
                plain = a.raw("settle_ms") == null && a.raw("guide_text") == null && a.raw("guide_image") == null,
            )
            val better = hints.look(ctx.key, look, quiet = hint != null)
            (hint ?: better)?.let { put("hint", it) }
            if (totalMs > SLOW_LOOK_MS) {
                putJsonObject("timing") {
                    put("total_ms", totalMs)
                    if (timing.opened.openMs > 0) put("open_ms", timing.opened.openMs)
                    if (timing.convergeMs > 0) put("converge_ms", timing.convergeMs)
                    if (timing.settleMs > 0) put("settle_ms", timing.settleMs)
                    if (timing.burstMs > 0) put("burst_ms", timing.burstMs)
                    if (timing.stillMs > 0) put("still_ms", timing.stillMs)
                    if (encodeMs > 0) put("encode_ms", encodeMs)
                }
            }
        }
        return CapResult(meta, imageOut)
    }

    /** [image] cut to [roi] and fit to [width], with the grid, caption and stamp that [a] asks for. */
    private fun render(image: Img, capturedAtMs: Long, label: String, roi: RoiRef?, width: Int, a: Args): Img {
        var img = comp.fit(comp.crop(image, roi?.rect), width)
        when (val g = GridOverlay.parse(a.string("grid"))) {
            is GridOverlay.Cells -> img = comp.grid(img, g.spec)
            GridOverlay.Ticks -> img = comp.ticks(img, roi?.rect ?: NRect.FULL)
            null -> Unit
        }
        img = captioned(img, capturedAtMs)
        if (a.bool("stamp") == true) img = comp.stamp(img, "${clockTime(capturedAtMs)}  $label  ${roi?.label() ?: ""}".trim())
        return img
    }

    private fun quality(a: Args) = (a.int("quality") ?: core.settings.value.jpegQuality).coerceIn(10, 100)

    private suspend fun cropTool(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val src = resolveImage(a.string("frame")!!)
        val roi = resolveRoi(a.raw("roi"))
        val width = (a.int("width") ?: core.settings.value.defaultWidth).coerceIn(16, Compositor.MAX_EDGE)
        val sharpness = Sharpness.score(ops, src.image, roi?.rect)
        val img = render(src.image, src.capturedAtMs, src.baseline ?: "#${src.frameId}", roi, width, a)
        val jpeg = ops.encodeJpeg(img, quality(a))
        core.recordServed("crop", ctx, jpeg.size, roi?.label())
        return CapResult(buildJsonObject {
            if (src.baseline != null) put("baseline", src.baseline) else put("frame_id", src.frameId)
            put("captured_at", isoTime(src.capturedAtMs))
            put("source", src.source)
            put("source_width", src.image.width)
            put("source_height", src.image.height)
            roi?.let { put("roi", it.json()) }
            put("width", img.width)
            put("height", img.height)
            validScale(src.lens, src.zoom)?.let { sc ->
                put("mm_per_px", sc.mmPerPxFor(src.image.width, src.image.height, roi?.rect, img.width))
            }
            put("sharpness", sharpness)
        }, ImageOut(jpeg))
    }

    private suspend fun flattenTool(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val corners = a.strings("corners")
        val ref = when {
            a.bool("mat") == true && corners != null -> throw LookoutError("invalid_param", 400, "Give corners or mat=true, not both")
            a.bool("mat") == true -> Flatten.Mat
            corners != null -> Flatten.Corners(
                Flatten.parseCorners(corners),
                a.double("width_mm") ?: throw LookoutError("missing_param", 400, "corners need width_mm and height_mm"),
                a.double("height_mm") ?: throw LookoutError("missing_param", 400, "corners need width_mm and height_mm"),
                a.bool("refine") ?: true,
            )
            else -> throw LookoutError("missing_param", 400, "Give corners with width_mm and height_mm, or mat=true")
        }
        val opt = Flatten.Options(a.double("px_per_mm"), a.double("margin_mm") ?: 0.0, a.bool("circles") ?: true, a.double("min_d_mm"), a.double("max_d_mm"))
        val src = a.string("frame")?.let { resolveImage(it) } ?: core.session.use {
            val f = sharpStill(null, null, null).frame
            store.keep(f)
            ResolvedImage(f.image, "#${f.id}", f.id, f.capturedAtMs, f.camera.tilt, f.camera.lens, f.camera.zoom, "still")
        }
        val r = withContext(Dispatchers.Default) { Flatten.run(ops, src.image, ref, opt) }
        var img = Flatten.draw(ops, r)
        when (val g = GridOverlay.parse(a.string("grid"))) {
            is GridOverlay.Cells -> img = comp.grid(img, g.spec)
            GridOverlay.Ticks -> img = comp.ticks(img, -r.marginMm, r.widthMm + r.marginMm, -r.marginMm, r.heightMm + r.marginMm)
            null -> Unit
        }
        val jpeg = ops.encodeJpeg(img, quality(a))
        core.recordServed("flatten", ctx, jpeg.size, null)
        return CapResult(buildJsonObject {
            if (src.baseline != null) put("baseline", src.baseline) else put("frame_id", src.frameId)
            put("captured_at", isoTime(src.capturedAtMs))
            put("source", src.source)
            put("source_width", src.image.width)
            put("source_height", src.image.height)
            r.json().forEach { (k, v) -> put(k, v) }
        }, ImageOut(jpeg))
    }

    private fun coldStartHint(): String? = when {
        core.session.thermalLimited -> "The phone is too hot to stay warm, so the camera closes between calls."
        core.session.effectiveMode() == PowerMode.COLD -> "The camera was closed. camera(mode=warm) or mode=auto keeps it open between calls."
        else -> null
    }

    /**
     * The metadata of an image of [frame]. Camera fields come only when they changed since the last metadata
     * sent to this client, and device fields only past a warning level, unless [verbose].
     */
    private val steadyGate = SteadyGate(core.source, clock) { store.latest.value }

    /**
     * After Ready when level: shows the level and waits until it is green and the phone is steady, both at once,
     * or until the person taps Snap now (then only the steady gate). Returns how the shot was released and the gate.
     */
    private suspend fun levelWait(lens: LensInfo?, deadline: Long): Pair<String, SteadyGate.Result> = coroutineScope {
        val remaining = (deadline - clock.now()).coerceAtLeast(1_000L)
        val watch = async { steadyGate.await(lens, maxMs = remaining) { core.source.level()?.green == true } }
        val prompt = async { core.human.guide(LEVEL_HEADING, LEVEL_TEXT, null, listOf(SNAP_NOW, CANCEL), remaining, showLevel = true) }
        select {
            watch.onAwait { gate ->
                prompt.cancel()
                if (!gate.passed) throw LookoutError("ready_timeout", 408, "The level did not turn green within the time left")
                "level" to gate
            }
            prompt.onAwait { choice ->
                watch.cancel()
                when (choice) {
                    SNAP_NOW -> "snap_now" to steadyGate.await(lens)
                    null -> throw LookoutError("ready_timeout", 408, "The level did not turn green within the time left")
                    else -> throw LookoutError("declined", 409, "The person tapped Cancel on the phone")
                }
            }
        }
    }

    /** Waits, at most [TORCH_AE_MS], for a frame from after now with converged auto exposure, as after the torch turns on. */
    private suspend fun awaitExposure() {
        val from = clock.now()
        withTimeoutOrNull(TORCH_AE_MS) { store.latest.first { f -> f != null && f.capturedAtMs > from + TORCH_LIGHT_MS && f.camera.aeConverged } }
    }

    /** The lens with id [lens], or the first one that faces that way. */
    private fun lensOf(lens: String): LensInfo? = core.source.lenses().let { ls -> ls.firstOrNull { it.id == lens } ?: ls.firstOrNull { it.facing == lens } }

    private fun frameMeta(frame: Frame, out: Img?, roi: RoiRef?, from: String, ctx: CallContext, verbose: Boolean): JsonObject {
        val c = frame.camera
        return buildJsonObject {
            put("frame_id", frame.id)
            put("captured_at", isoTime(frame.capturedAtMs))
            put("age_ms", clock.now() - frame.capturedAtMs)
            put("source", from)
            sentCamera.select(ctx.key, c, verbose).forEach { (k, v) -> put(k, v) }
            c.tilt?.let { put("pitch_deg", it.pitchDeg); put("roll_deg", it.rollDeg) }
            // Only a handheld frame gets the blur fields; for a phone that rests on something they say nothing.
            if (Blur.handheld(c.rateRadS)) {
                put("handheld", true)
                Blur.predictedPx(c.rateRadS, c.exposureTimeNs, lensOf(c.lens)?.fovHDeg, c.zoom, frame.image.width)?.let { put("blur_px", it) }
            }
            roi?.let { put("roi", it.json()) }
            out?.let {
                put("width", it.width)
                put("height", it.height)
            }
            put("brightness", frame.brightness)
            core.device.read().metaFields(verbose).forEach { (k, v) -> put(k, v) }
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

    /**
     * A frame or a baseline. [source] is still, analysis, history or baseline. [baseline] is the name of a baseline.
     * [lens] and [zoom] are the camera state of the pixels, null when unknown.
     */
    private class ResolvedImage(
        val image: Img, val label: String, val frameId: Long?, val capturedAtMs: Long, val tilt: Tilt?,
        val lens: String?, val zoom: Double?, val source: String, val baseline: String? = null,
    )

    private suspend fun resolveImage(key: String): ResolvedImage {
        key.toLongOrNull()?.let { id ->
            val f = store.find(id)
            return ResolvedImage(f.image(), "#$id ${clockTime(f.capturedAtMs)}", id, f.capturedAtMs, f.tilt, f.lens, f.zoom, f.source)
        }
        val info = core.baselines.get(key) ?: throw LookoutError("baseline_not_found", 404, "No frame id or baseline named '$key'")
        val jpeg = core.baselines.jpeg(key) ?: throw LookoutError("baseline_not_found", 404, "Baseline '$key' has no image")
        return ResolvedImage(ops.decode(jpeg), "baseline $key", info.frameId, info.createdAt, info.tilt, info.lens, info.zoom, "baseline", key)
    }

    /** A JPEG for /v1/frames/{id}.jpg: a composite (c12), a frame (42) at the best resolution kept, or a baseline name. */
    suspend fun frameJpeg(id: String): ByteArray {
        core.composites.get(id)?.let { return it }
        if (core.composites.droppedForMemory(id)) throw LookoutError("image_dropped", 404, "Image $id was ${Retention.DROPPED_HINT}.")
        id.toLongOrNull()?.let { return store.find(it).jpeg() }
        return core.baselines.jpeg(id) ?: throw LookoutError("frame_not_found", 404, "No frame, composite or baseline '$id'")
    }

    private fun saveBaseline(name: String, still: Frame, roi: RoiRef?): BaselineInfo {
        val info = BaselineInfo(name, clock.now(), still.id, still.image.width, still.image.height, roi?.name, still.camera.tilt, still.camera.lens, still.camera.zoom)
        core.baselines.save(info, ops.encodeJpeg(still.image, 92))
        return info
    }

    private suspend fun <T> withSettings(over: CameraSettings, block: suspend () -> T): T {
        if (over.isEmpty) return block()
        if (over.lens != null) guardLensChange()
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
                handheld = over.handheld?.let { before.handheld ?: false },
            )
            runCatching { core.source.apply(revert) }
        }
    }

    /** A full-resolution still. The camera cannot take one while it records video. */
    private suspend fun still(): RawFrame {
        if (core.recordings.isRecording) {
            throw LookoutError("recording_active", 409, "Full-resolution stills are unavailable while a recording runs. Use look with detail=fast, or wait_for(condition=recording_done).")
        }
        return core.source.captureStill()
    }

    private fun guardLensChange() {
        if (core.recordings.isRecording) throw LookoutError("recording_active", 409, "The lens cannot change while a recording runs")
    }

    private fun guardImage() {
        if (core.paused.value) throw LookoutError("paused", 503, "Lookout is paused on the phone")
        if (!core.imageLimiter.tryTake()) throw LookoutError("rate_limited", 429, "More than ${LookoutCore.IMAGE_RATE.toInt()} image requests per second")
    }

    private fun regionsJson(regions: List<NRect>, within: NRect?): JsonElement = buildJsonArray {
        regions.forEach { r -> add(LookoutJson.encodeToJsonElement(if (within == null) r else r.within(within))) }
    }

    /** The MCP tool list, as tools/list sends it. */
    val toolList: JsonArray by lazy { JsonArray(mcpTools.map { it.toolJson() }) }

    /** A hash of [toolList]. A client whose last tool list had another hash has an old list. */
    val toolsHash: String by lazy { ClientState.hash(toolList.toString()) }

    /** The changelog entries for [ctx]: those since [changes] ("all" or a version), else those since the version it last saw. */
    private fun changesFor(ctx: CallContext, changes: String?): List<Changelog.Entry> {
        val last = core.clients.get(ctx.key).version
        val list = when {
            changes == "all" -> Changelog.entries
            changes != null -> {
                if (!Changelog.isVersion(changes)) throw LookoutError("invalid_param", 400, "'changes' must be \"all\" or a version such as 0.1.0")
                Changelog.since(changes)
            }
            last == LookoutCore.SERVER_VERSION -> emptyList()
            else -> Changelog.since(last)
        }
        core.clients.sawVersion(ctx.key)
        return list
    }

    fun describeJson(ctx: CallContext, changes: String?): JsonObject {
        val span = store.bufferSpan()
        val s = core.settings.value
        val news = changesFor(ctx, changes)
        return buildJsonObject {
            put("name", core.deviceName)
            ctx.baseUrl?.let {
                put("base_url", it)
                put("upload_url", ctx.url(UPLOAD_PATH, "POST"))
            }
            put("api_version", LookoutCore.API_VERSION)
            put("server_version", LookoutCore.SERVER_VERSION)
            put("server_time", isoTime(clock.now()))
            put("lenses", LookoutJson.encodeToJsonElement(core.source.lenses()))
            put("camera", LookoutJson.encodeToJsonElement(core.cameraSettings))
            put("orientation", s.orientation.wire)
            core.source.tilt()?.let { t -> putJsonObject("tilt") { put("pitch_deg", t.pitchDeg); put("roll_deg", t.rollDeg) } }
            put("mode", s.powerMode?.wire ?: "default")
            put("effective_mode", core.session.effectiveMode().wire)
            put("camera_open", core.session.open.value)
            put("paused", core.paused.value)
            put("asleep", core.sleep.asleep.value)
            put("sleep_after_min", s.sleepAfterMinutes)
            put("rois", LookoutJson.encodeToJsonElement(core.rois.list()))
            put("baselines", LookoutJson.encodeToJsonElement(core.baselines.list()))
            core.scale.get()?.let { sc ->
                val reason = sc.staleReason(currentLens(), currentZoom())
                putJsonObject("scale") {
                    put("width_mm", sc.widthMm)
                    put("height_mm", sc.heightMm)
                    put("lens", sc.lens)
                    put("zoom", sc.zoom)
                    put("valid", reason == null)
                    reason?.let { put("stale_reason", it) }
                }
            }
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
            putJsonObject("recording") {
                val caps = core.recordings.caps
                put("supported", caps != null)
                caps?.let {
                    put("fps", LookoutJson.encodeToJsonElement(it.fps))
                    put("resolutions", LookoutJson.encodeToJsonElement(it.resolutions))
                }
                put("audio", core.recordings.audioAvailable)
                put("max_duration_ms", Recordings.MAX_DURATION_MS)
                core.recordings.activeId?.let { put("active", it) }
                put("count", core.recordings.list().size)
            }
            put("device", LookoutJson.encodeToJsonElement(core.device.read()))
            putJsonObject("tools") { mcpTools.forEach { put(it.name, it.summary) } }
            if (core.clients.toolsChanged(ctx.key, toolsHash)) {
                put("tools_changed", true)
                put("tools_note", TOOLS_CHANGED)
            }
            if (news.isNotEmpty()) put("changes", Changelog.json(news))
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
            put("asleep", core.sleep.asleep.value)
            put("active_waits", core.activeWaits.get())
            if (latest != null) {
                put("latest_frame_id", latest.id)
                put("latest_age_ms", clock.now() - latest.capturedAtMs)
                put("brightness", latest.brightness)
                put("lens_covered", latest.brightness < DARK_FRAME)
            }
        }
    }

    companion object {
        const val MAX_WAIT_MS = 600_000L
        /** The default wait of wait_for, ask and point with buttons. Some MCP clients stop a call at 60 s. */
        const val DEFAULT_WAIT_MS = 50_000L
        const val DEFAULT_READY_MS = 120_000L
        /** How long a guided look or record call waits for Ready before it returns state=waiting. */
        const val DEFAULT_GUIDE_WAIT_MS = 45_000L
        const val MAX_GUIDE_WAIT_MS = 50_000L
        /** Uploads of guide images: this many at once, then one per [UPLOAD_EVERY_MS]. */
        const val UPLOAD_BURST = 4
        const val UPLOAD_EVERY_MS = 15_000L
        const val READY = "Ready"
        const val READY_LEVEL = "Ready when level"
        const val SNAP_NOW = "Snap now"
        const val LEVEL_HEADING = "Waiting for level"
        const val LEVEL_TEXT = "Hold the phone still. The photo is taken when the level turns green."
        /** After Ready, the shot waits this long for the tap to end. */
        const val AFTER_TAP_MS = 300L
        /** Auto exposure takes about this long to follow the torch. */
        const val TORCH_AE_MS = 1_500L
        const val TORCH_LIGHT_MS = 150L
        const val CANCEL = "Cancel"
        const val SHOT_HEADING = "The agent needs this shot"
        const val POINT_HEADING = "The agent points at this"
        private const val GUIDE_EDGE = 1280
        const val FRAME_TIMEOUT_MS = 5_000L
        const val AE_WAIT_MS = 1_500L
        const val WARM_WINDOW_MS = 1_500L
        const val SETTLE_EXTRA_MS = 10_000L
        const val DARK_FRAME = 0.04
        const val DARK_HINT = "The view is black (lens covered, phone face down, or no light). Use force=true to return the image anyway, or torch=true to turn the light on."
        const val SHARP_FAST_FRAMES = 5
        const val SHARP_FAST_MS = 800L
        const val SHARP_STILLS = 3
        const val MOTION_WINDOW_MS = 500L
        const val MOVING_HINT = "The scene was moving when this frame was taken. Use settle_ms to wait for stillness, or sharp=true to pick the sharpest frame from a burst."
        const val SHEET_TILES = 12
        /** The most tiles one clip call decodes, whatever their size. */
        const val MAX_CLIP_TILES = 200
        /** A recording ends this many frames off its requested duration and still counts as the full length. */
        private const val DURATION_SLACK_FRAMES = 2
        private val TILE_COLUMNS = buildJsonArray { add("t_ms"); add("index"); add("motion") }
        private val ACTIVITY_COLUMNS = buildJsonArray { add("from_ms"); add("to_ms"); add("peak"); add("peak_ms") }
        /** Longest mark label on a clip tile. */
        const val MARK_LABEL = 24
        const val TRACE_EDGE = 48
        /** The largest roi that mode=track decodes at full resolution on every frame. */
        const val MAX_TRACK_PX = 250_000
        const val READ_EDGE = 1024
        const val MIN_READ_EVERY_MS = 250L
        const val MAX_SAMPLE_MS = 120_000L
        const val SLOW_LOOK_MS = 2_000L
        const val TOOLS_CHANGED = "The tool list changed since you loaded it. Reload it with tools/list."

        val SERVER_INSTRUCTIONS = """
            Lookout is a phone camera on the user's desk or in their room. Use it to see physical things.
            - Call describe first. Its tools field lists every tool in one line; your client may load only some tool schemas. Its changes field lists what is new.
            - look shows the scene now (about 1024 px), taken after your call. Add settle_ms if things still move. Use detail=full only for small text, with an roi.
            - To read a display, meter or label, call read with an roi. It returns text and numbers, not an image.
            - An image costs about width × height / 750 tokens. A lower width or an roi costs less.
            - To wait for something, call wait_for; never poll look. Keep timeout_ms at or under 50000 and call it again for long jobs.
            - history shows what happened while you were away (warm mode only).
            - For events under a second (blink codes, a part falling) or sound, call record, then wait_for(condition=recording_done), then clip. clip mode=track measures how far and when a part moved.
            - To measure, set a scale once (scale action=set). diff with an roi and image=false gives dx, dy of one step. flatten measures a flat part and its holes in mm.
            - grid=8x6 gives cell names for roi from/to and point. grid=ticks draws edge rulers.
            - pitch_deg is -90 when the lens looks straight down. True verticals show turned clockwise by roll_deg. A moved event or camera_moved=true means the phone tilted: saved ROIs and baselines may not line up, and the scale is cleared.
            - say, ask and point talk to the person near the phone. Pair them with wait_for(change) to see the person act.
            - Call caption just before you act, so images show what you did at that moment.
            - A guided look or record can return state=waiting with a request_id. Then call wait_for(condition=request).
            - With a script, pass inline=false and download image_url or video_url. They need no token for 10 minutes.
            - When you finish and will not need the camera for a while, call done. The phone sleeps, and your next call wakes it.
        """.trimIndent()
    }
}
