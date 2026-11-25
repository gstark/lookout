package app.lookout.core

import app.lookout.core.Capability.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    private val roiParam = Param("roi", PType.STRING, "Name of a saved ROI, or an ad-hoc rect \"x,y,w,h\" in normalized 0..1 coordinates.")
    private val gridParam = Param("grid", PType.STRING, "Draw a labelled grid, e.g. \"8x6\" (columns A.., rows 1..). \"true\" means 8x6.")
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
        "An example of the shot you want, shown beside the live preview: a base64 JPEG or PNG, or a data: URL. The call then waits until the person taps Ready.",
    )
    private val readyTimeoutParam = Param("ready_timeout_ms", PType.INTEGER, "With guide_text or guide_image: how long to wait for Ready. Default 120000, max 600000.")
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
    private val clipOptions = listOf(
        Param("mode", PType.STRING, "sheet (images, the default) or trace (numbers only).", enum = listOf("sheet", "trace")),
        Param("from_ms", PType.INTEGER, "Start of the window, ms from the first frame. Default 0."),
        Param("to_ms", PType.INTEGER, "End of the window. Default the end of the clip."),
        Param("tiles", PType.INTEGER, "For sheet: number of tiles. Default 12."),
        Param("pick", PType.STRING, "For sheet: even spacing or the frames with the most motion. Default even.", enum = listOf("even", "motion")),
        Param("at_ms", PType.INTEGER_ARRAY, "For sheet: exact times to show, e.g. [1200, 1250]. One time gives one large frame. " +
            "The count is limited by sheet size, not by a fixed number: thin roi strips fit more."),
        Param("at", PType.STRING_ARRAY, "For sheet: exact phone wall times to show, ISO 8601. Use instead of at_ms."),
        Param("at_marks", PType.BOOLEAN, "For sheet: one tile at each mark of the recording, labelled with it. Use instead of at_ms."),
        Param("offset_ms", PType.INTEGER, "With at_marks: show each mark at t_ms + offset_ms, e.g. 500 to see the result. Default 0."),
        roiParam.copy(description = "Crop to this region. Default: the roi of the recording. Use \"0,0,1,1\" for the full frame."),
        Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568). Default 1568 for a sheet, 1024 for one frame."),
        Param("threshold", PType.INTEGER, "For trace: the luma (0..255) between low and high. Default: halfway between the darkest and brightest frame."),
        Param("raw", PType.BOOLEAN, "For trace: also return [t_ms, luma, r, g, b] for every frame."),
    )

    val describe = Capability(
        "describe",
        "Orient yourself in one call: lenses and their capabilities, current camera settings, the phone tilt, saved ROIs and baselines, " +
            "the history buffer span, battery and thermal state, and the API version. Cheap; call it first.",
        emptyList(), Kind.JSON,
    ) { _, _ -> CapResult(describeJson()) }

    val status = Capability(
        "status",
        "Battery, charging, temperature, thermal status, uptime, Wi-Fi RSSI and camera state. It does not wake Lookout from sleep.",
        emptyList(), Kind.JSON, wakes = false,
    ) { _, _ -> CapResult(statusJson()) }

    val look = Capability(
        "look",
        "Get the current view. detail=fast (default) returns a preview frame, about 1024 px. A warm look takes under 1 s; the first look after an idle period or a sleep takes a few seconds while the camera opens. Use it for nearly every look. " +
            "detail=full takes a full-resolution still, up to 1568 px. It is slower and larger, so use it only to read small text such as part markings, " +
            "ideally with roi. With detail=fast, a small roi also comes from a full-resolution still. " +
            "fresh=true (default) means the frame was taken after your call arrived, so it is safe right after you act. " +
            "Use settle_ms to wait until the scene stops moving. Lens, zoom, focus, torch and exposure apply for this call only. " +
            "Give guide_text and/or guide_image to ask the person to set up the shot first; the frame is taken after they tap Ready. " +
            "Use grid to get cell labels you can name in roi definitions.",
        listOf(
            Param("detail", PType.STRING, "fast (preview frame, default) or full (full-resolution still).", enum = listOf("fast", "full")),
            roiParam, gridParam, stampParam, qualityParam, lensParam,
            Param("width", PType.INTEGER, "Long edge of the returned image in px (16..1568); default 1024 (defaults.width) for fast, 1568 for full. Token cost is about width × height / 750."),
            Param("fresh", PType.BOOLEAN, "detail=fast only: return only a frame captured after this request arrived. Default true."),
            Param("settle_ms", PType.INTEGER, "Wait until motion has stayed below threshold for this many ms and AE/AF converged. With detail=full, the still is taken after that."),
            Param("after_frame_id", PType.INTEGER, "detail=fast only: return the first frame newer than this id."),
            Param("after", PType.STRING, "detail=fast only: return the first frame captured after this time (ISO 8601 or relative like -5s)."),
            Param("zoom", PType.NUMBER, "Zoom ratio for this call."),
            Param("focus", PType.OBJECT, "Focus for this call.", schema = focusSchema),
            Param("torch", PType.BOOLEAN, "Torch on for this call."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index for this call."),
            Param("save_as", PType.STRING, "Save the full still as a baseline with this name. Implies detail=full."),
            Param("force", PType.BOOLEAN, "Return the image even when the view is dark."),
            Param("verbose", PType.BOOLEAN, "Return all camera and device fields. Default: camera fields only when they changed, device fields only at a warning level."),
            guideTextParam, guideImageParam, readyTimeoutParam,
        ),
        Kind.IMAGE,
    ) { a, ctx -> look(a, ctx) }

    val read = Capability(
        "read",
        "Read text and numbers on the phone (on-device OCR) and return them as JSON, not an image, so it costs few tokens. " +
            "Use it for a multimeter, a power supply, a printer LCD or a label. Give roi to read one display; " +
            "lines come with boxes in full-frame 0..1 coordinates, and numbers come parsed with their units (\"12.04 V\" gives value 12.04, unit V). " +
            "value is the first number. By default it reads a full-resolution still; source=frame reads the preview frame, which is faster. " +
            "image=true also returns the region with the found lines outlined. duration_ms with every_ms logs the value over time as a table. " +
            "To wait for a value, use wait_for(condition=value). Seven-segment digits can read badly; check with look.",
        listOf(
            roiParam, lensParam,
            Param("source", PType.STRING, "still (sharper, default) or frame (faster). A running recording forces frame.", enum = listOf("still", "frame")),
            Param("image", PType.BOOLEAN, "Also return the region with the found lines outlined."),
            Param("duration_ms", PType.INTEGER, "Log the value over time: read preview frames for this long (max $MAX_SAMPLE_MS) and return a table of t_ms, value and text, with min, max, mean and last."),
            Param("every_ms", PType.INTEGER, "With duration_ms: how often to read. Default 1000, min $MIN_READ_EVERY_MS."),
        ),
        Kind.IMAGE,
    ) { a, ctx -> readTool(a, ctx) }

    val camera = Capability(
        "camera",
        "Change persistent camera settings: lens, zoom, focus, AE/AWB lock, torch, and power mode " +
            "(warm keeps history and instant fresh frames; cold opens the camera per request; auto stays warm while needed). " +
            "Returns the settings now in effect. Call with no arguments to read them. " +
            "The torch turns off when Lookout sleeps after it is idle, and stays off until you turn it on again.",
        listOf(
            lensParam.copy(description = "Lens id from describe."),
            Param("zoom", PType.NUMBER, "Zoom ratio."),
            Param("focus", PType.OBJECT, "Focus setting.", schema = focusSchema),
            Param("ae_lock", PType.BOOLEAN, "Lock auto exposure."),
            Param("awb_lock", PType.BOOLEAN, "Lock auto white balance."),
            Param("torch", PType.BOOLEAN, "Torch on or off."),
            Param("exposure_comp", PType.INTEGER, "Exposure compensation index."),
            Param("mode", PType.STRING, "Power mode. \"default\" means warm while charging, auto on battery (stays warm for 5 minutes after a call).", enum = listOf("warm", "cold", "auto", "default")),
        ),
        Kind.JSON,
    ) { a, _ -> camera(a) }

    val waitFor = Capability(
        "wait_for",
        "Block until a visual condition happens, instead of polling. change: the view (or roi) differs from the moment you called by more than threshold. " +
            "motion: something moves. still: nothing moved for for_ms (e.g. a print finished). human_ack: the person taps Done on the phone. " +
            "recording_done: a recording from record has finished; returns its summary and, by default, a motion contact sheet (pass sheet=false for no image, or clip={...} to get that clip directly). " +
            "value: on-device OCR reads the roi every every_ms until the first number is above or below a limit, or the text contains text " +
            "(e.g. a nozzle reaching 200 °C, or a display showing DONE). " +
            "On a match you get the before and after frames side by side with changed areas outlined, and the score. " +
            "On timeout you get matched:false and the latest frame, never an error. Keep timeout_ms at or under 120000 " +
            "and call it again in a loop for long jobs.",
        listOf(
            Param("condition", PType.STRING, "What to wait for.", required = true, enum = listOf("change", "motion", "still", "human_ack", "recording_done", "value")),
            roiParam,
            Param("threshold", PType.NUMBER, "Fraction of pixels (0..1) that must change. Defaults: change 0.02, motion 0.01."),
            Param("for_ms", PType.INTEGER, "For still: how long the scene must stay still. Default 10000."),
            Param("timeout_ms", PType.INTEGER, "Give up after this many ms. Default 120000, max 600000."),
            Param("message", PType.STRING, "For human_ack: the text shown on the phone."),
            Param("recording_id", PType.STRING, "For recording_done: the recording. Default: the running one, else the newest."),
            Param("sheet", PType.BOOLEAN, "For recording_done: return the default motion contact sheet. Default true. Ignored if clip is given."),
            Param("clip", PType.OBJECT, "For recording_done: return this clip in place of the default sheet. It takes the parameters of the clip tool, without recording_id. It wins over sheet=false.", schema = paramsSchema(clipOptions, describe = false)),
            Param("above", PType.NUMBER, "For value: match when the value is above this."),
            Param("below", PType.NUMBER, "For value: match when the value is below this."),
            Param("text", PType.STRING, "For value: match when the read text contains this (case-insensitive)."),
            Param("every_ms", PType.INTEGER, "For value: how often to read. Default 1000, min $MIN_READ_EVERY_MS."),
            Param("confirm", PType.INTEGER, "For value: how many reads in a row must match, so one misread digit does not end the wait. Default 2."),
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
        "Compare two frames, or a saved baseline against now. Returns {score, changed, regions} and a side-by-side image with changed regions boxed (pass image=false for JSON only). " +
            "a and b are frame ids or baseline names; b defaults to a fresh frame. " +
            "camera_moved=true means the phone tilted more than ${LookoutCore.MOVED_DEG.toInt()} degrees between a and b, so the frames no longer line up.",
        listOf(
            Param("a", PType.STRING, "Frame id or baseline name.", required = true),
            Param("b", PType.STRING, "Frame id or baseline name. Default: a fresh frame now."),
            roiParam,
            Param("threshold", PType.NUMBER, "Score above which changed is true. Default 0.02."),
            Param("image", PType.BOOLEAN, "Return the side-by-side image. Default true. Set false for JSON only."),
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

    private val markAtSchema = buildJsonObject { putJsonArray("type") { add("string"); add("integer") } }

    val record = Capability(
        "record",
        "Record video on the phone at full frame rate, for fast events that look and wait_for miss: a servo sweep, an LED blink code, " +
            "a part falling, a display glitch. start returns once the encoder is rolling, so trigger the event after it returns. " +
            "Call action=mark with a label as you act, or give at (an ISO time or t_ms) or a list marks=[{label, at}], also after the end, " +
            "for example from a script log. The summary gives marks as t_ms on the clip's timeline, and clip at_marks=true shows them. " +
            "Then call wait_for(condition=recording_done). It returns the summary and a motion contact sheet; pass clip={...} to get a specific clip or trace in the same call, or sheet=false for the summary only. " +
            "Use clip to inspect more. One recording at a time. look with detail=full is " +
            "unavailable while recording, and detail=fast still works. For start, guide_text and/or guide_image ask the person to set up the shot first; " +
            "recording starts only after they tap Ready. At high fps, PWM-dimmed LEDs and screens can show bands or flicker; that comes from the camera, not the device.",
        listOf(
            Param("action", PType.STRING, "What to do.", required = true, enum = listOf("start", "mark", "stop", "list")),
            Param("fps", PType.INTEGER, "For start: frames per second. Default 30. describe lists the rates this lens supports (usually 30 and 60)."),
            Param("resolution", PType.STRING, "For start: video size. Default 1080p.", enum = VideoResolution.entries.map { it.wire }),
            Param("duration_ms", PType.INTEGER, "For start: length of the recording. Default ${Recordings.DEFAULT_DURATION_MS}, max ${Recordings.MAX_DURATION_MS}."),
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
            readyTimeoutParam,
        ),
        Kind.JSON,
    ) { a, _ -> recordTool(a) }

    val clip = Capability(
        "clip",
        "Look inside a finished recording without spending context on every frame. Times are t_ms from the first frame. " +
            "The default is a contact sheet of 12 tiles across the whole clip, each labelled with t_ms and the frame index. " +
            "Zoom in with from_ms and to_ms (for example around a motion segment from wait_for), use pick=motion for the frames that changed most, " +
            "or at_ms=[1234] for one large frame. at=[\"2026-11-24T14:03:07.250Z\"] picks frames by phone wall time instead, " +
            "and at_marks=true gives one tile per mark, offset by offset_ms; " +
            "tiles are arrays in the order of tile_columns. Wall time = started_at + t_ms, accurate to time_precision_ms. mode=trace returns no image: it measures the brightness of the roi on every frame and gives " +
            "high and low runs with durations and the frequency, for LED blink codes and flicker. " +
            "The full video is at GET /v1/recordings/{id}/video, for tools such as ffmpeg.",
        listOf(Param("recording_id", PType.STRING, "The recording. Default: the newest.")) + clipOptions,
        Kind.IMAGE,
    ) { a, ctx -> clipTool(a, ctx) }

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

    val point = Capability(
        "point",
        "Draw marks over the live preview on the phone, so the person sees where to act: \"plug the red wire into this pin\". " +
            "Place each mark with at: a grid cell (C3, on the 8x6 grid that look draws), a cell span (C2:E4), a point \"x,y\", " +
            "a rect \"x,y,w,h\" or a saved ROI name. An arrow points at at (from sets its tail); a box outlines at; a label writes text at at. " +
            "New marks replace the old ones. Without buttons the call returns at once and the marks stay for timeout_ms. " +
            "With buttons (e.g. [\"Done\"]) the phone shows text and the buttons beside the preview, the call waits for a tap, " +
            "then clears the marks and returns {answered, choice}. preview=true also returns the current frame with the marks drawn, " +
            "to check that they land on the right spot. action=clear removes the marks.",
        listOf(
            Param("action", PType.STRING, "show (default) or clear.", enum = listOf("show", "clear")),
            Param("marks", PType.OBJECT, "The marks, at most ${Marks.MAX_MARKS}.", schema = buildJsonObject {
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
            }),
            Param("text", PType.STRING, "An instruction for the person, shown with the marks."),
            Param("buttons", PType.STRING_ARRAY, "Wait for the person to tap one of these, e.g. [\"Done\", \"Can't find it\"]."),
            Param("timeout_ms", PType.INTEGER, "How long the marks stay, or how long to wait for a tap. Default 60000, max 600000."),
            Param("preview", PType.BOOLEAN, "Also return the current frame with the marks drawn."),
            gridParam.copy(description = "Grid for cell names in at and from. Default 8x6."),
        ),
        Kind.IMAGE,
    ) { a, ctx -> pointTool(a, ctx) }

    val caption = Capability(
        "caption",
        "Show text over the camera view, e.g. \"sent M106\", so an image shows what you did at the moment the frame was taken. " +
            "The phone preview shows it, and every image of a frame taken while it shows has it at the bottom: look, wait_for, history, diff and clip. " +
            "During a recording it also adds a mark with the text. A new caption replaces the old one. " +
            "Give duration_ms to remove it after that time; without it, the text stays until action=clear. " +
            "The video file itself has no text; the marks give the times.",
        listOf(
            Param("action", PType.STRING, "show (default) or clear.", enum = listOf("show", "clear")),
            Param("text", PType.STRING, "For show: the text, up to ${Captions.MAX_TEXT} characters."),
            Param("duration_ms", PType.INTEGER, "For show: remove the text after this many ms (100..${Captions.MAX_DURATION_MS}). Default: it stays until cleared or replaced."),
        ),
        Kind.JSON,
    ) { a, _ -> captionTool(a) }

    val done = Capability(
        "done",
        "Tell Lookout that you have finished and will not need the camera for a while. It clears your caption and marks, " +
            "then sleeps to save battery: the light goes off, the camera closes and the phone screen can turn off. " +
            "When a wait, a recording or a question is still active, it sleeps when that ends. " +
            "The server keeps listening, so your next call wakes it. The light stays off after a wake.",
        emptyList(), Kind.JSON, wakes = false,
    ) { _, _ -> doneTool() }

    /** The MCP tools, in the order agents see them. status is REST-only; describe covers it. */
    val mcpTools: List<Capability> = listOf(describe, look, waitFor, history, record, clip, diff, roi, baseline, camera, say, ask, point, caption, read, done)

    val all: List<Capability> = mcpTools + status

    // ---- handlers ----

    private suspend fun look(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val roi = resolveRoi(a.raw("roi"))
        val saveAs = a.string("save_as")?.let { checkName(it, "Baseline") }
        val full = a.string("detail") == "full" || saveAs != null
        val force = a.bool("force") == true
        val width = (a.int("width") ?: if (full) Compositor.MAX_EDGE else core.settings.value.defaultWidth).coerceIn(16, Compositor.MAX_EDGE)
        val over = (roi?.defaults?.toSettings() ?: CameraSettings()).merge(
            CameraSettings(lens = a.string("lens"), zoom = a.double("zoom"), focus = a.focus("focus"), torch = a.bool("torch"), exposureComp = a.int("exposure_comp")),
        )
        val fresh = a.bool("fresh") ?: true
        val afterId = a.long("after_frame_id")
        val afterMs = a.string("after")?.let { parseTime(it, clock.now()) }
        val settleMs = a.long("settle_ms")?.coerceIn(0, MAX_WAIT_MS)
        val guided = awaitReady(a, "look")
        val started = clock.now()
        return core.session.use { opened ->
            val timing = Timing(started, opened)
            withSettings(over) {
                if (full) {
                    val got = settleMs?.let { obtainFrame(true, null, null, it) }
                    got?.let { timing.waited(it) }
                    val still = timing.still { store.registerStill(still()) }
                    val dark = isDark(still, force)
                    if (saveAs != null && !dark) {
                        saveBaseline(saveAs, still, roi)
                        core.events.add("baseline", buildJsonObject { put("saved", saveAs) })
                    }
                    return@withSettings imageResult("look", ctx, still, roi, width, a, "still", got?.settled, timing, dark, notSaved = dark && saveAs != null)
                }
                // A frame from before Ready or before the settings changed would not show the shot asked for.
                val got = obtainFrame(fresh || guided || !over.isEmpty, afterId, afterMs, settleMs)
                timing.waited(got)
                var frame = got.frame
                var from = "analysis"
                if (roi != null) {
                    val px = roi.rect.toPixels(frame.image.width, frame.image.height)
                    if (max(px.w, px.h) < width * 0.75 && !core.recordings.isRecording) {
                        frame = timing.still { store.registerStill(still()) }
                        from = "still"
                    }
                }
                imageResult("look", ctx, frame, roi, width, a, from, got.settled, timing, isDark(frame, force))
            }
        }
    }

    /**
     * With guide_text or guide_image, shows the shot setup beside the phone preview and returns true when
     * the person taps Ready. Returns false when there is no guide. Throws when they tap Cancel or do not answer in time.
     */
    private suspend fun awaitReady(a: Args, tool: String): Boolean {
        val text = a.string("guide_text")?.trim()?.takeIf { it.isNotEmpty() }
        val image = a.string("guide_image")?.let(::guideImage)
        if (text == null && image == null) return false
        val timeout = (a.long("ready_timeout_ms") ?: DEFAULT_READY_MS).coerceIn(1_000L, MAX_WAIT_MS)
        val shown = text ?: if (tool == "record") "Set up the shot like the example. Recording starts when you tap Ready." else "Set up the shot like the example. The photo is taken when you tap Ready."
        val choice = core.human.guide(SHOT_HEADING, shown, image, listOf(READY, CANCEL), timeout)
        core.events.add("guide", buildJsonObject {
            put("tool", tool)
            put("text", shown)
            put("image", image != null)
            put("answer", choice ?: "timeout")
        })
        return when (choice) {
            READY -> true
            null -> throw LookoutError("ready_timeout", 408, "The person did not tap Ready within $timeout ms")
            else -> throw LookoutError("declined", 409, "The person tapped Cancel on the phone")
        }
    }

    /** Decodes a base64 JPEG or PNG (or a data: URL) and re-encodes it as a JPEG of a size the phone can show. */
    private fun guideImage(value: String): ByteArray {
        val b64 = value.substringAfter("base64,", value).filterNot { it.isWhitespace() }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
        val img = bytes?.let { runCatching { ops.decode(it) }.getOrNull() }
            ?: throw LookoutError("invalid_param", 400, "'guide_image' must be a base64 JPEG or PNG, or a data: URL")
        val edge = max(img.width, img.height)
        val shown = if (edge > GUIDE_EDGE) {
            ops.scale(img, (img.width * GUIDE_EDGE / edge).coerceAtLeast(1), (img.height * GUIDE_EDGE / edge).coerceAtLeast(1))
        } else img
        return ops.encodeJpeg(shown, 85)
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
        val timeout = (a.long("timeout_ms") ?: 60_000L).coerceIn(1_000L, MAX_WAIT_MS)
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
        val timeout = (a.long("timeout_ms") ?: 120_000L).coerceIn(0, MAX_WAIT_MS)
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
        }, ImageOut(jpeg, id))
    }

    private suspend fun diff(a: Args, ctx: CallContext): CapResult {
        guardImage()
        val aKey = a.string("a")!!
        val bKey = a.string("b")
        val threshold = a.double("threshold") ?: (LookoutCore.CHANGE_THRESHOLD / core.settings.value.sensitivity)
        val wantImage = a.bool("image") ?: true
        val roi = resolveRoi(a.raw("roi")) ?: core.baselines.get(aKey)?.roi?.let { resolveRoi(JsonPrimitive(it)) }
        val left = resolveImage(aKey)
        val right = if (bKey != null) resolveImage(bKey) else core.session.use {
            val f = obtainFrame(fresh = true, afterId = null, afterMs = null, settleMs = null).frame
            ResolvedImage(f.image, "now #${f.id}", f.id, f.capturedAtMs, f.camera.tilt)
        }
        val rect = roi?.rect
        val change = core.detector.compare(core.detector.thumb(left.image, rect ?: NRect.FULL), core.detector.thumb(right.image, rect ?: NRect.FULL))
        val out = if (wantImage) {
            val image = comp.sideBySide(
                captioned(comp.crop(left.image, rect), left.capturedAtMs), "a: ${left.label}",
                captioned(comp.crop(right.image, rect), right.capturedAtMs), "b: ${right.label}",
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
            put("regions", regionsJson(change.regions, rect))
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
                put("image_url", "/v1/frames/$id.jpg")
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
                val still = store.registerStill(still())
                val info = saveBaseline(name, still, roi)
                val preview = ops.encodeJpeg(comp.fit(comp.crop(still.image, roi?.rect), Compositor.DEFAULT_EDGE), core.settings.value.jpegQuality)
                core.recordServed("baseline", ctx, preview.size, roi?.label())
                CapResult(buildJsonObject { put("baseline", LookoutJson.encodeToJsonElement(info)) }, ImageOut(preview))
            }
        }
    }

    // ---- recordings ----

    private suspend fun recordTool(a: Args): CapResult {
        val rec = core.recordings
        return when (a.string("action")!!) {
            "start" -> {
                if (core.paused.value) throw LookoutError("paused", 503, "Lookout is paused on the phone")
                val duration = a.long("duration_ms") ?: Recordings.DEFAULT_DURATION_MS
                if (duration !in 500..Recordings.MAX_DURATION_MS) {
                    throw LookoutError("invalid_param", 400, "duration_ms must be 500..${Recordings.MAX_DURATION_MS}")
                }
                val spec = RecordSpec(a.int("fps") ?: 30, VideoResolution.parse(a.string("resolution") ?: VideoResolution.FHD.wire))
                val roi = resolveRoi(a.raw("roi"))
                awaitReady(a, "record")
                val info = rec.start(spec, duration, roi, a.string("label"))
                CapResult(buildJsonObject {
                    put("recording", recordingJson(info))
                    put("server_time", isoTime(clock.now()))
                    put("next", "Trigger the event now. Call record(action=mark, label=...) as you act, or add marks with at after the end. " +
                        "Then call wait_for(condition=recording_done, recording_id=${info.id}). Add clip={...} to get a clip or trace directly, or sheet=false for the summary only.")
                })
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
    fun recordingJson(r: RecordingInfo): JsonObject = buildJsonObject {
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
        r.roi?.let { put("roi", r.roiName?.let(::JsonPrimitive) ?: LookoutJson.encodeToJsonElement(it)) }
        r.durationMs?.let { put("duration_ms", it) }
        r.frames?.let { put("frames", it) }
        r.actualFps?.let { put("actual_fps", it) }
        r.dropped?.takeIf { it > 0 }?.let { put("dropped", it) }
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
            put("video_url", "/v1/recordings/${r.id}/video")
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
        return ClipSpec(mode, fromMs, toMs, tiles, motion, atMs, at, atMarks, offsetMs, rawRoi != null, roi, width, threshold, raw)
    }

    private suspend fun recordingDone(a: Args, ctx: CallContext, start: Long, timeout: Long, clipSpec: ClipSpec?, wantSheet: Boolean): CapResult {
        val rec = core.recordings
        val id = a.string("recording_id") ?: rec.activeId ?: rec.resolve(null).id
        rec.get(id)
        val info = rec.awaitDone(id, timeout)
        val json = buildJsonObject {
            put("matched", info != null)
            put("condition", "recording_done")
            put("waited_ms", clock.now() - start)
            put("recording", recordingJson(info ?: rec.get(id)))
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
        val roi = if (spec.hasRoi) spec.roi else info.roi?.let { RoiRef(info.roiName, it, null) }
        val last = idx.tMs(idx.ptsUs.size - 1)
        val from = (spec.fromMs ?: 0L).coerceIn(0L, last)
        val to = (spec.toMs ?: last).coerceIn(from, last)
        val window = idx.ptsUs.indices.filter { idx.tMs(it) in from..to }.ifEmpty { listOf(idx.nearest(from)) }
        if (spec.mode == "trace") return@withContext trace(info, idx, window, roi, spec)
        val marks = if (spec.atMarks) markedFrames(info, idx, spec.offsetMs) else emptyMap()
        val atMs = spec.atMs ?: spec.at?.map { info.tMs(parseTime(it, clock.now())) }
        val picks = when {
            spec.atMarks -> marks.keys.sorted()
            atMs != null -> atMs.map { idx.nearest(it) }.distinct().sorted()
            else -> pickFrames(idx, window, spec.tiles, motion = spec.motion)
        }
        val width = spec.width ?: if (picks.size == 1) Compositor.DEFAULT_EDGE else Compositor.MAX_EDGE
        val sheet = sheet(info, idx, picks, roi, width, tool, ctx, marks)
        CapResult(buildJsonObject {
            put("recording_id", info.id)
            put("started_at", isoTime(info.startedAt))
            put("from_ms", from)
            put("to_ms", to)
            put("frames_in_window", window.size)
            put("frame_interval_ms", frameMs(info))
            sheet.json.forEach { (k, v) -> put(k, v) }
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
            val wanted = picks.associateBy { idx.ptsUs[it] }
            val edge = if (picks.size == 1) width else sheetTileEdge(info, roi, picks.size, width)
            val images = HashMap<Int, Img>()
            core.recordings.decoder.decode(
                core.recordings.file(info), idx.ptsUs[picks.first()], idx.ptsUs[picks.last()],
                { pts -> if (pts in wanted) FrameRequest(roi?.rect, edge) else null },
            ) { pts, img -> wanted[pts]?.let { i -> if (img != null) images[i] = img } }
            val shown = picks.filter { it in images }
            if (shown.isEmpty()) throw LookoutError("clip_decode_failed", 500, "Could not decode frames from ${info.id}")
            for (i in shown) images[i] = captioned(images[i]!!, info.startedAt + idx.tMs(i))
            // t_ms first: a label too wide for its tile loses words from the end.
            fun label(i: Int) = listOfNotNull("t=${idx.tMs(i)}ms #$i", marks[i]?.let { if (it.length > MARK_LABEL) it.take(MARK_LABEL - 1) + "…" else it }).joinToString("  ")
            val img = if (shown.size == 1) {
                comp.stamp(comp.fit(images[shown[0]]!!, width), "${info.id}  ${label(shown[0])}  ${roi?.label() ?: ""}".trim())
            } else {
                comp.contactSheet(shown.map { images[it]!! to label(it) }, width)
            }
            val jpeg = ops.encodeJpeg(img, core.settings.value.jpegQuality)
            val id = core.composites.put(jpeg)
            core.recordServed(tool, ctx, jpeg.size, roi?.label())
            Sheet(buildJsonObject {
                put("tile_columns", if (marks.isEmpty()) TILE_COLUMNS else JsonArray(TILE_COLUMNS + JsonPrimitive("label")))
                putJsonArray("tiles") {
                    shown.forEach { i -> add(buildJsonArray { add(idx.tMs(i)); add(i); add(idx.motion[i]); marks[i]?.let { add(it) } }) }
                }
                roi?.let { put("roi", it.json()) }
                put("image_id", id)
                put("image_url", "/v1/frames/$id.jpg")
            }, ImageOut(jpeg, id))
        }

    /** The long edge of each tile when [n] tiles of [roi] go in a sheet of [width]. Throws when the tiles would be too small to read. */
    private fun sheetTileEdge(info: RecordingInfo, roi: RoiRef?, n: Int, width: Int): Int {
        val r = roi?.rect ?: NRect.FULL
        val (w, h) = if (info.width > 0 && info.height > 0) info.width to info.height else VideoResolution.FHD.width to VideoResolution.FHD.height
        val aspect = (r.h * h) / (r.w * w)
        val layout = Compositor.sheetLayout(n, aspect, width)
        if (layout.tileEdge < Compositor.MIN_TILE_EDGE) {
            throw LookoutError("invalid_param", 400,
                "$n tiles do not fit: at most ${Compositor.maxTiles(aspect, width, MAX_CLIP_TILES)} fit for this roi shape at width $width, " +
                    "each at least ${Compositor.MIN_TILE_EDGE} px on the long edge. Ask for fewer times, or a narrower window.")
        }
        return layout.tileEdge
    }

    private fun trace(info: RecordingInfo, idx: ClipIndex, window: List<Int>, roi: RoiRef?, spec: ClipSpec): CapResult {
        val wanted = window.toHashSet().map { idx.ptsUs[it] }.toHashSet()
        val samples = ArrayList<Trace.Sample>(window.size)
        core.recordings.decoder.decode(
            core.recordings.file(info), idx.ptsUs[window.first()], idx.ptsUs[window.last()],
            { pts -> if (pts in wanted) FrameRequest(roi?.rect, TRACE_EDGE) else null },
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
        var stillMs = 0L

        fun waited(got: Obtained) {
            convergeMs = got.convergeMs
            settleMs = got.settleMs
        }

        suspend fun still(take: suspend () -> Frame): Frame {
            val start = clock.now()
            return take().also { stillMs = max(0L, clock.now() - start) }
        }
    }

    /** True for a black view (lens covered, phone face down, no light), which is not worth the tokens of an image. */
    private fun isDark(frame: Frame, force: Boolean) = !force && frame.brightness < DARK_FRAME

    /** The result of a look. A [dark] frame gives the metadata and no image. */
    private fun imageResult(
        tool: String, ctx: CallContext, frame: Frame, roi: RoiRef?, width: Int, a: Args,
        from: String, settled: Boolean?, timing: Timing, dark: Boolean, notSaved: Boolean = false,
    ): CapResult {
        var encodeMs = 0L
        var outImg: Img? = null
        var imageOut: ImageOut? = null
        if (!dark) {
            val encodeStart = clock.now()
            var img = comp.fit(comp.crop(frame.image, roi?.rect), width)
            parseGrid(a.string("grid"))?.let { img = comp.grid(img, it) }
            img = captioned(img, frame.capturedAtMs)
            if (a.bool("stamp") == true) img = comp.stamp(img, "${clockTime(frame.capturedAtMs)}  #${frame.id}  ${roi?.label() ?: ""}".trim())
            val quality = (a.int("quality") ?: core.settings.value.jpegQuality).coerceIn(10, 100)
            val jpeg = ops.encodeJpeg(img, quality)
            encodeMs = max(0L, clock.now() - encodeStart)
            core.recordServed(tool, ctx, jpeg.size, roi?.label())
            outImg = img
            imageOut = ImageOut(jpeg)
        }
        val totalMs = max(0L, clock.now() - timing.started)
        val meta = buildJsonObject {
            frameMeta(frame, outImg, roi, from, ctx, a.bool("verbose") == true).forEach { (k, v) -> put(k, v) }
            if (settled != null) put("settled", settled)
            if (dark) {
                put("dark", true)
                if (notSaved) put("saved", false)
            }
            if (timing.opened.coldStart) put("cold_start", true)
            val hint = if (dark) DARK_HINT else if (timing.opened.coldStart) coldStartHint() else null
            hint?.let { put("hint", it) }
            if (totalMs > SLOW_LOOK_MS) {
                putJsonObject("timing") {
                    put("total_ms", totalMs)
                    if (timing.opened.openMs > 0) put("open_ms", timing.opened.openMs)
                    if (timing.convergeMs > 0) put("converge_ms", timing.convergeMs)
                    if (timing.settleMs > 0) put("settle_ms", timing.settleMs)
                    if (timing.stillMs > 0) put("still_ms", timing.stillMs)
                    if (encodeMs > 0) put("encode_ms", encodeMs)
                }
            }
        }
        return CapResult(meta, imageOut)
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
    private fun frameMeta(frame: Frame, out: Img?, roi: RoiRef?, from: String, ctx: CallContext, verbose: Boolean): JsonObject {
        val c = frame.camera
        return buildJsonObject {
            put("frame_id", frame.id)
            put("captured_at", isoTime(frame.capturedAtMs))
            put("age_ms", clock.now() - frame.capturedAtMs)
            put("source", from)
            sentCamera.select("${ctx.via} ${ctx.remote}", c, verbose).forEach { (k, v) -> put(k, v) }
            c.tilt?.let { put("pitch_deg", it.pitchDeg); put("roll_deg", it.rollDeg) }
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

    private class ResolvedImage(val image: Img, val label: String, val frameId: Long?, val capturedAtMs: Long?, val tilt: Tilt? = null)

    private fun resolveImage(key: String): ResolvedImage {
        key.toLongOrNull()?.let { id ->
            store.latest.value?.takeIf { it.id == id }?.let { return ResolvedImage(it.image, "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs, it.camera.tilt) }
            store.stillById(id)?.let { return ResolvedImage(it.image, "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs, it.camera.tilt) }
            store.bufferedById(id)?.let { return ResolvedImage(ops.decode(it.jpeg), "#$id ${clockTime(it.capturedAtMs)}", id, it.capturedAtMs) }
            throw LookoutError("frame_not_found", 404, "Frame $id is no longer in memory")
        }
        val info = core.baselines.get(key) ?: throw LookoutError("baseline_not_found", 404, "No frame id or baseline named '$key'")
        val jpeg = core.baselines.jpeg(key) ?: throw LookoutError("baseline_not_found", 404, "Baseline '$key' has no image")
        return ResolvedImage(ops.decode(jpeg), "baseline $key", info.frameId, info.createdAt, info.tilt)
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
        val info = BaselineInfo(name, clock.now(), still.id, still.image.width, still.image.height, roi?.name, still.camera.tilt)
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

    fun describeJson(): JsonObject {
        val span = store.bufferSpan()
        val s = core.settings.value
        return buildJsonObject {
            put("name", core.deviceName)
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
                put("max_duration_ms", Recordings.MAX_DURATION_MS)
                core.recordings.activeId?.let { put("active", it) }
                put("count", core.recordings.list().size)
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
        const val DEFAULT_READY_MS = 120_000L
        const val READY = "Ready"
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
        const val READ_EDGE = 1024
        const val MIN_READ_EVERY_MS = 250L
        const val MAX_SAMPLE_MS = 120_000L
        const val SLOW_LOOK_MS = 2_000L

        val SERVER_INSTRUCTIONS = """
            Lookout is a phone camera on the user's desk or in their room. Use it to see physical things.
            - Call describe first to learn the lenses, saved ROIs and baselines.
            - Frame metadata has pitch_deg (-90 looks straight down, 0 level) and roll_deg (true verticals show turned clockwise by roll_deg). Correct for roll before you judge if something is level.
            - A moved event or diff camera_moved=true means the phone tilted, so saved ROIs and baselines may no longer line up.
            - Use look with the default detail=fast (about 1024 px). Use detail=full (a slower full-resolution still) only to read small text, ideally with an roi.
            - An image costs about width × height / 750 tokens. Lower width (or pass diff image=false) to save tokens; JPEG quality changes file size only.
            - look defaults to fresh=true, so a frame taken right after you act shows the result. Add settle_ms if things are still moving.
            - Use grid=8x6 on a full-frame look, then roi action=set with from/to cells to name a region.
            - To wait for something, call wait_for; never poll look in a loop. Keep timeout_ms at or under 120000 and call wait_for again for long jobs such as a 3D print.
            - history shows what happened while you were away as one contact sheet (warm mode only).
            - For fast events (motion under a second, blink codes), use record, then wait_for(condition=recording_done) (pass clip={...} to get a clip or trace in one call, or sheet=false to skip the default sheet). Do not poll look.
            - When a script triggers the event, add its logged times afterwards with record(action=mark, marks=[{label, at}], client_now=<your clock now>).
            - say and ask talk to the person near the phone. Pair ask or say with wait_for(change) to see them act.
            - To read a display or a label, use read with an roi; it returns text and numbers, not an image.
            - To show the person where to act, use point with marks on grid cells; add buttons=["Done"] to wait until they finish.
            - Call caption with what you do just before you act (a command sent, a button pressed). Images and recordings then show that text on the frames of that moment.
            - When you finish a task and will not need the camera for a while, call done. The phone then sleeps to save battery, and your next call wakes it.
        """.trimIndent()
    }
}
