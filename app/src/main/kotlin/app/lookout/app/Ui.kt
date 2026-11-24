package app.lookout.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.LifecycleStartEffect
import app.lookout.core.CameraSettings
import app.lookout.core.FrameOrientation
import app.lookout.core.GridSpec
import app.lookout.core.LookoutSettings
import app.lookout.core.Marks
import app.lookout.core.NRect
import app.lookout.core.PointMark
import app.lookout.core.PowerMode
import app.lookout.core.RecordingInfo
import app.lookout.core.Roi
import app.lookout.core.Tools
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Check(val id: String, val label: String, val ok: Boolean)

class MainActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val fixCheck: (String) -> Unit,
    val copy: (String) -> Unit,
)

private val Blue = Color(0xFF2563EB)
private val Red = Color(0xFFFF3B30)
private val ReadyGreen = Color(0xFF16A34A)

@Composable
fun LookoutTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8AB4FF)) else lightColorScheme(primary = Blue)
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}

@Composable
fun MainScreen(rt: Runtime, checklist: List<Check>, actions: MainActions) {
    val core = rt.core
    val running by rt.running.collectAsState()
    val paused by core.paused.collectAsState()
    val settings by core.settings.collectAsState()
    val prompt by core.human.current.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val sound = remember { MediaActionSound() }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    LaunchedEffect(Unit) {
        core.served.collect {
            if (core.settings.value.clickSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            flash.snapTo(1f)
            flash.animateTo(0f, tween(700))
        }
    }

    // The activity stops when the screen turns off, but the composition stays. Tie the preview to
    // the started state so the camera can close when nobody can see the screen. This sits here, not in
    // PreviewPane, so a rotation that moves the preview does not close and reopen the camera.
    LifecycleStartEffect(Unit) {
        core.session.previewStarted()
        onStopOrDispose { core.session.previewStopped() }
    }
    // Held here so the toggles survive a rotation and a guide that moves the preview.
    val toggles = remember { PreviewToggles() }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        val guide = prompt?.takeIf { it.guide }
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val missing = checklist.filter { !it.ok }
        val guidePanel: @Composable () -> Unit = { guide?.let { g -> GuidePanel(g, Modifier.fillMaxWidth(), onAnswer = { core.human.answer(g.id, it) }) } }
        val controls: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val label = when {
                    !running -> "Start serving"
                    paused -> "Resume"
                    else -> "Pause"
                }
                Button(
                    onClick = {
                        when {
                            !running -> actions.start()
                            else -> core.setPaused(!paused)
                        }
                    },
                    modifier = Modifier.weight(1f).height(64.dp),
                    colors = if (running && !paused) ButtonDefaults.buttonColors(containerColor = Red) else ButtonDefaults.buttonColors(),
                ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                if (running) OutlinedButton(onClick = actions.stop, modifier = Modifier.height(64.dp)) { Text("Stop") }
                OutlinedButton(onClick = { showSetup = true }, modifier = Modifier.height(64.dp)) { Text("Setup") }
            }
        }
        if (landscape) {
            // Landscape: the preview fills the height on the left, and everything else scrolls on the right.
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PreviewPane(rt, flash.value, toggles, fitHeight = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (missing.isNotEmpty()) Checklist(missing, actions.fixCheck)
                    guidePanel()
                    PreviewChips(rt, toggles)
                    controls()
                    RoiList(rt)
                    ActivityFeed(rt)
                }
            }
        } else {
            // Portrait: a full-width preview, with the guide below it.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (missing.isNotEmpty()) Checklist(missing, actions.fixCheck)
                PreviewPane(rt, flash.value, toggles, fitHeight = false)
                PreviewChips(rt, toggles)
                guidePanel()
                controls()
                RoiList(rt)
                ActivityFeed(rt)
            }
        }
        prompt?.takeIf { !it.guide }?.let { p -> PromptOverlay(p, onAnswer = { core.human.answer(p.id, it) }) }
    }
    // The sheet is a window above the full-screen card, so it steps aside while a card waits for an answer.
    if (showSetup && prompt?.guide != false) SetupSheet(rt, settings, actions, onDismiss = { showSetup = false })
}

@Composable
private fun Checklist(missing: List<Check>, fix: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Setup still needed", fontWeight = FontWeight.SemiBold)
            missing.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("• ${c.label}", Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { fix(c.id) }) { Text("Fix") }
                }
            }
        }
    }
}

/** The preview overlays that the person switches on with the chips. */
private class PreviewToggles {
    var showGrid by mutableStateOf(false)
    var showLevel by mutableStateOf(false)
    var editing by mutableStateOf(false)
}

/**
 * The live preview. [fitHeight] sizes it from the available height (landscape) instead of the width
 * (portrait), so the whole frame stays on screen.
 */
@Composable
private fun PreviewPane(rt: Runtime, flash: Float, toggles: PreviewToggles, fitHeight: Boolean) {
    val core = rt.core
    val frame by core.store.latest.collectAsState()
    val latestEvent by core.events.latestId.collectAsState()
    val rois = remember(latestEvent) { core.rois.list() }
    val showGrid = toggles.showGrid
    val showLevel = toggles.showLevel
    val editing = toggles.editing
    val recording by core.recordings.current.collectAsState()
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var pending by remember { mutableStateOf<NRect?>(null) }
    val marks by core.pointer.current.collectAsState()
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "point").animateFloat(
        initialValue = 1f, targetValue = 0.45f, label = "marks",
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    // The marks go away at their deadline. The agent can also clear them sooner.
    LaunchedEffect(marks?.id) {
        val set = marks ?: return@LaunchedEffect
        delay((set.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0))
        core.pointer.clear(set.id)
    }

    val bitmap: Bitmap? = (frame?.image as? BitmapImg)?.bitmap
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    val border = Red.copy(alpha = flash)

    val sizing = if (fitHeight) Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(aspect)
    Box(
        sizing.clip(RoundedCornerShape(10.dp)).background(Color.Black)
            .border(BorderStroke(6.dp, border), RoundedCornerShape(10.dp)),
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "Camera preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            Text(
                "Waiting for the camera…",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp), textAlign = TextAlign.Center,
            )
        }
        recording?.let {
            if (it.state == "recording") RecordingBorder(it)
            RecordingPanel(it, Modifier.align(Alignment.BottomCenter))
        }
        if (showLevel) LevelOverlay()
        Canvas(
            Modifier.fillMaxSize().pointerInput(editing) {
                if (!editing) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, _ -> dragEnd = change.position },
                    onDragEnd = {
                        val s = dragStart
                        val e = dragEnd
                        if (s != null && e != null && abs(e.x - s.x) > 12 && abs(e.y - s.y) > 12) {
                            pending = NRect.clamped(
                                (min(s.x, e.x) / size.width).toDouble(), (min(s.y, e.y) / size.height).toDouble(),
                                (abs(e.x - s.x) / size.width).toDouble(), (abs(e.y - s.y) / size.height).toDouble(),
                            )
                        }
                        dragStart = null
                        dragEnd = null
                    },
                )
            },
        ) {
            if (showGrid) {
                val g = GridSpec.DEFAULT
                for (c in 1 until g.cols) drawLine(Color.White.copy(alpha = 0.5f), Offset(size.width * c / g.cols, 0f), Offset(size.width * c / g.cols, size.height))
                for (r in 1 until g.rows) drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, size.height * r / g.rows), Offset(size.width, size.height * r / g.rows))
            }
            rois.forEach { r ->
                drawRect(Color.Yellow, Offset((r.rect.x * size.width).toFloat(), (r.rect.y * size.height).toFloat()),
                    Size((r.rect.w * size.width).toFloat(), (r.rect.h * size.height).toFloat()), style = Stroke(3f))
            }
            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                drawRect(Blue, Offset(min(s.x, e.x), min(s.y, e.y)), Size(abs(e.x - s.x), abs(e.y - s.y)), style = Stroke(4f))
            }
            marks?.let { drawPointMarks(it.marks, measurer, pulse) }
        }
        marks?.text?.let { caption ->
            Text(
                caption, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (showGrid) {
                val g = GridSpec.DEFAULT
                for (c in 0 until g.cols) for (r in 0 until g.rows) {
                    Box(
                        Modifier.offset(x = maxWidth * c / g.cols, y = maxHeight * r / g.rows).size(maxWidth / g.cols, maxHeight / g.rows),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            g.cellLabel(c, r), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                        )
                    }
                }
            }
            rois.forEach { r ->
                Text(
                    r.name, color = Color.Yellow, fontSize = 12.sp,
                    modifier = Modifier.offset(x = maxWidth * r.rect.x.toFloat() + 4.dp, y = maxHeight * r.rect.y.toFloat() + 2.dp),
                )
            }
        }
    }
    pending?.let { rect -> RoiNameDialog(initial = "", onDone = { name -> runCatching { core.rois.put(Roi(name, rect)) }; core.events.add("roi"); pending = null }, onCancel = { pending = null }) }
}

/** Shows what is being recorded and counts down to the end, then shows the analysis. */
/** The chips that switch the preview overlays and the torch. */
@Composable
private fun PreviewChips(rt: Runtime, toggles: PreviewToggles) {
    val core = rt.core
    val latestEvent by core.events.latestId.collectAsState()
    // An agent can also switch the torch, and that adds an event, so read the state again on each event.
    val torchOn = remember(latestEvent) { core.cameraSettings.torch == true }
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = toggles.showGrid, onClick = { toggles.showGrid = !toggles.showGrid }, label = { Text("Grid 8x6") })
        FilterChip(selected = toggles.showLevel, onClick = { toggles.showLevel = !toggles.showLevel }, label = { Text("Level") })
        FilterChip(selected = torchOn, onClick = { scope.launch { runCatching { core.applyCamera(CameraSettings(torch = !torchOn)) } } }, label = { Text("Light") })
        FilterChip(selected = toggles.editing, onClick = { toggles.editing = !toggles.editing }, label = { Text(if (toggles.editing) "Drag to draw ROI" else "Edit ROIs") })
    }
}

/** The milliseconds left in [r]. The value updates while [r] records. */
@Composable
private fun recordingLeftMs(r: RecordingInfo): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.id, r.state) {
        while (r.state == "recording") {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    return (r.startedAt + r.plannedMs - now).coerceIn(0L, r.plannedMs)
}

/**
 * A red line around the preview that gets shorter as the recording runs out, in step with the bar in
 * [RecordingPanel]. It has the same width and corners as the flash border, so the flash covers it.
 */
@Composable
private fun RecordingBorder(r: RecordingInfo) {
    val fraction = if (r.plannedMs > 0) recordingLeftMs(r).toFloat() / r.plannedMs else 0f
    Canvas(Modifier.fillMaxSize()) {
        val w = 6.dp.toPx()
        val l = w / 2
        val t = w / 2
        val rt = size.width - w / 2
        val b = size.height - w / 2
        val d = 2 * (10.dp.toPx() - w / 2)
        // Clockwise from the top-left corner, so the line goes back toward its start.
        val outline = Path().apply {
            moveTo(l + d / 2, t)
            lineTo(rt - d / 2, t)
            arcTo(Rect(rt - d, t, rt, t + d), -90f, 90f, false)
            lineTo(rt, b - d / 2)
            arcTo(Rect(rt - d, b - d, rt, b), 0f, 90f, false)
            lineTo(l + d / 2, b)
            arcTo(Rect(l, b - d, l + d, b), 90f, 90f, false)
            lineTo(l, t + d / 2)
            arcTo(Rect(l, t, l + d, t + d), 180f, 90f, false)
        }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * fraction, part, true)
        drawPath(part, Red, style = Stroke(w))
    }
}

@Composable
private fun RecordingPanel(r: RecordingInfo, modifier: Modifier) {
    val left = recordingLeftMs(r)
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.2f, label = "dot",
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    )
    val recording = r.state == "recording"
    Column(
        modifier.fillMaxWidth().padding(10.dp).background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (recording) Red.copy(alpha = blink) else Color.Gray))
            Text(if (recording) "REC" else "Analyzing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(listOfNotNull(r.id, r.label).joinToString("  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (recording) {
                Text(
                    "%d:%02d.%d".format(left / 60_000, left / 1000 % 60, left / 100 % 10),
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            buildString {
                append("${r.resolution} · ${r.fps} fps · ${"%.1f".format(r.plannedMs / 1000.0)} s")
                if (r.width > 0) append(" · ${r.width}×${r.height}")
            },
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        )
        if (recording) {
            LinearProgressIndicator(
                progress = { left.toFloat() / r.plannedMs },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Red, trackColor = Color.White.copy(alpha = 0.2f),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun RoiNameDialog(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = Regex("[a-z0-9][a-z0-9_-]{0,39}").matches(name)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name this region") },
        text = { OutlinedTextField(name, { name = it.lowercase().trim() }, label = { Text("e.g. printer-bed") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun RoiList(rt: Runtime) {
    val core = rt.core
    val version by core.events.latestId.collectAsState()
    val rois = remember(version) { core.rois.list() }
    var renaming by remember { mutableStateOf<String?>(null) }
    if (rois.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Regions of interest", fontWeight = FontWeight.SemiBold)
            rois.forEach { r ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("${r.name}  ", fontWeight = FontWeight.Medium)
                Text("%.2f, %.2f, %.2f × %.2f".format(r.rect.x, r.rect.y, r.rect.w, r.rect.h), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("←" to (-0.01 to 0.0), "→" to (0.01 to 0.0), "↑" to (0.0 to -0.01), "↓" to (0.0 to 0.01)).forEach { (label, d) ->
                        TextButton(onClick = {
                            runCatching { core.rois.put(r.copy(rect = NRect.clamped(r.rect.x + d.first, r.rect.y + d.second, r.rect.w, r.rect.h))) }
                            core.events.add("roi")
                        }) { Text(label, fontSize = 18.sp) }
                    }
                    TextButton(onClick = { renaming = r.name }) { Text("Rename") }
                    TextButton(onClick = { core.rois.delete(r.name); core.events.add("roi") }) { Text("Delete", color = Red) }
                }
            }
        }
    }
    renaming?.let { old ->
        RoiNameDialog(old, onDone = { runCatching { core.rois.rename(old, it) }; core.events.add("roi"); renaming = null }, onCancel = { renaming = null })
    }
}

@Composable
private fun ActivityFeed(rt: Runtime) {
    val core = rt.core
    val latest by core.events.latestId.collectAsState()
    val events = remember(latest) {
        core.events.recent(120).filter { it.type in setOf("request", "motion", "still", "change", "say", "ask", "point", "camera", "pause", "thermal") }.takeLast(20).reversed()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Activity", fontWeight = FontWeight.SemiBold)
            if (events.isEmpty()) Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            events.forEach { e ->
                val d = e.data
                val detail = when (e.type) {
                    "request" -> listOfNotNull(d["tool"], d["roi"], d["bytes"]?.let { "$it B" }, d["remote"]).joinToString(" · ") { it.toString().trim('"') }
                    else -> d.entries.joinToString(" ") { "${it.key}=${it.value.toString().trim('"')}" }
                }
                Text("${app.lookout.core.clockTime(e.ts)}  ${e.type}  $detail", fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PromptOverlay(p: app.lookout.core.Prompt, onAnswer: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.id) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
      // Centered when it fits, and it scrolls when the screen is short, as in landscape.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(p.heading ?: "The agent asks", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(p.text, fontSize = 30.sp, lineHeight = 36.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(32.dp))
            p.buttons.forEach { b ->
                Button(onClick = { onAnswer(b) }, modifier = Modifier.fillMaxWidth().height(72.dp).padding(vertical = 4.dp)) { Text(b, fontSize = 22.sp) }
            }
            Spacer(Modifier.height(16.dp))
            Text("${max(0L, (p.deadlineMs - now) / 1000)} s left", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
}

/**
 * The shot the agent wants (it takes it when the person taps Ready), or a point request beside its marks.
 * The example sits beside the text and the buttons share a row, so Ready stays on screen in both orientations.
 */
@Composable
private fun GuidePanel(p: app.lookout.core.Prompt, modifier: Modifier, onAnswer: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.id) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val example = remember(p.id) { p.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Column(
        modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(p.heading ?: Tools.SHOT_HEADING, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            example?.let { b ->
                Image(
                    b.asImageBitmap(), contentDescription = "Example of the shot",
                    modifier = Modifier.weight(1f).aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(p.text, Modifier.weight(1f), fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
        }
        // The first button is the main answer (Ready, Done). The others are outlined.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val main = p.buttons.first()
            Button(
                onClick = { onAnswer(main) }, modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReadyGreen, contentColor = Color.White),
            ) { Text(main, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            p.buttons.drop(1).forEach { b -> OutlinedButton(onClick = { onAnswer(b) }, modifier = Modifier.height(64.dp)) { Text(b) } }
        }
        Text("${max(0L, (p.deadlineMs - now) / 1000)} s left", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Draws the agent's marks the same way the compositor draws them for the agent's preview. */
private fun DrawScope.drawPointMarks(marks: List<PointMark>, measurer: TextMeasurer, alpha: Float) {
    val stroke = max(6f, size.width / 160f)
    val style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    fun line(a: Offset, b: Offset, color: Color) {
        drawLine(Color.Black.copy(alpha = 0.7f * alpha), a, b, stroke * 2f, StrokeCap.Round)
        drawLine(color.copy(alpha = alpha), a, b, stroke, StrokeCap.Round)
    }
    fun label(text: String, x: Float, y: Float, center: Boolean, above: Boolean) {
        val layout = measurer.measure(text, style)
        val w = layout.size.width + 12f
        val h = layout.size.height + 6f
        val left = (if (center) x - w / 2 else x).coerceIn(0f, max(0f, size.width - w))
        val top = (if (above) y - h - 4f else y + 4f).coerceIn(0f, max(0f, size.height - h))
        drawRect(Color.Black.copy(alpha = 0.72f), Offset(left, top), Size(w, h))
        drawText(layout, topLeft = Offset(left + 6f, top + 3f))
    }
    for (m in marks) {
        val color = Color(Marks.COLORS[m.color] ?: Marks.COLORS.getValue("red"))
        val r = m.rect
        val from = m.from
        val to = m.to
        when {
            r != null -> {
                val tl = Offset((r.x * size.width).toFloat(), (r.y * size.height).toFloat())
                val br = Offset(((r.x + r.w) * size.width).toFloat(), ((r.y + r.h) * size.height).toFloat())
                val tr = Offset(br.x, tl.y)
                val bl = Offset(tl.x, br.y)
                line(tl, tr, color); line(tr, br, color); line(br, bl, color); line(bl, tl, color)
                m.label?.let { val roomAbove = tl.y > 40f; label(it, tl.x, if (roomAbove) tl.y else br.y, center = false, above = roomAbove) }
            }
            from != null && to != null -> {
                val tail = Offset((from.x * size.width).toFloat(), (from.y * size.height).toFloat())
                val head = Offset((to.x * size.width).toFloat(), (to.y * size.height).toFloat())
                line(tail, head, color)
                Marks.arrowWings(tail.x.toDouble(), tail.y.toDouble(), head.x.toDouble(), head.y.toDouble(), stroke * 5.0)
                    .forEach { (x, y) -> line(head, Offset(x.toFloat(), y.toFloat()), color) }
                m.label?.let { label(it, tail.x, tail.y, center = true, above = tail.y < head.y) }
            }
            to != null -> m.label?.let { label(it, (to.x * size.width).toFloat(), (to.y * size.height).toFloat() + 12f, center = true, above = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupSheet(rt: Runtime, s: LookoutSettings, actions: MainActions, onDismiss: () -> Unit) {
    val core = rt.core
    var token by remember { mutableStateOf(rt.tokens.get()) }
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect an agent", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("Install the Lookout skill where the agent runs. The agent finds this phone and asks to pair, and you approve the request here.", fontSize = 13.sp, color = note)
            Text("npx skills add gstark/lookout", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("Wi-Fi address", fontWeight = FontWeight.SemiBold)
            Text(rt.baseUrl(), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("Give the phone a DHCP reservation on your router, so this address does not change.", fontSize = 13.sp, color = note)
            HorizontalDivider()
            Toggle("Allow Tailscale", s.allowTailscale) { v -> core.updateSettings { it.copy(allowTailscale = v) } }
            val tailnet = rt.tailnetUrl()
            if (tailnet != null) Text(tailnet, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(
                when {
                    !s.allowTailscale -> "Agents on your tailnet can connect when this is on. The Tailscale app must run on this phone."
                    tailnet == null -> "Tailscale is not connected on this phone. Open the Tailscale app and connect, then open Setup again."
                    else -> "This address stays the same on every network."
                },
                fontSize = 13.sp, color = note,
            )
            HorizontalDivider()
            Text("Paired agents", fontWeight = FontWeight.SemiBold)
            Toggle("Accept pairing requests", s.pairing) { v -> core.updateSettings { it.copy(pairing = v) } }
            val clients by rt.tokens.clients.collectAsState()
            if (clients.isEmpty()) {
                Text("An agent can ask to pair. You approve each request here, and the agent gets its own token.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            clients.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name)
                        Text(
                            "${c.address?.let { "Only from $it" } ?: "From any LAN address"} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(c.createdAt))}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { rt.tokens.revoke(c.id) }) { Text("Revoke") }
                }
            }
            HorizontalDivider()
            Text("Owner token", fontWeight = FontWeight.SemiBold)
            Text("For the web page and for scripts. An agent gets its own token when it pairs.", fontSize = 13.sp, color = note)
            Text(token, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.copy(token) }) { Text("Copy token") }
                OutlinedButton(onClick = { token = rt.tokens.regenerate() }) { Text("New token") }
            }
            HorizontalDivider()
            Text("Camera orientation", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FrameOrientation.entries.forEach { o ->
                    FilterChip(selected = s.orientation == o, onClick = { core.updateSettings { it.copy(orientation = o) } }, label = { Text(o.wire.replaceFirstChar { it.uppercase() }) })
                }
            }
            Text("Pick the way the phone is mounted. The screen and the agent's frames both face this way. Saved regions and baselines belong to one orientation, so set them again after a change.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Power mode", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<PowerMode?>(null, PowerMode.WARM, PowerMode.COLD, PowerMode.AUTO).forEach { m ->
                    FilterChip(selected = s.powerMode == m, onClick = { core.updateSettings { it.copy(powerMode = m) } }, label = { Text(m?.wire ?: "default") })
                }
            }
            NumberField("Port (restart the service to apply)", s.port) { v -> core.updateSettings { it.copy(port = v.coerceIn(1024, 65535)) } }
            NumberField("Default width px", s.defaultWidth) { v -> core.updateSettings { it.copy(defaultWidth = v.coerceIn(64, 1568)) } }
            NumberField("JPEG quality", s.jpegQuality) { v -> core.updateSettings { it.copy(jpegQuality = v.coerceIn(10, 100)) } }
            NumberField("History interval ms", s.bufferIntervalMs.toInt()) { v -> core.updateSettings { it.copy(bufferIntervalMs = v.toLong().coerceIn(250, 60_000)) } }
            NumberField("History length minutes", s.bufferMinutes) { v -> core.updateSettings { it.copy(bufferMinutes = v.coerceIn(1, 240)) } }
            Text("Change sensitivity: ${"%.1f".format(s.sensitivity)}×")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5, 1.0, 1.5, 2.0).forEach { v ->
                    FilterChip(selected = s.sensitivity == v, onClick = { core.updateSettings { it.copy(sensitivity = v) } }, label = { Text("${v}×") })
                }
            }
            Toggle("Keep screen on", s.keepScreenOn) { v -> core.updateSettings { it.copy(keepScreenOn = v) } }
            Toggle("Start serving when the app opens", s.startOnLaunch) { v -> core.updateSettings { it.copy(startOnLaunch = v) } }
            Toggle("Start serving after a restart or an update", s.startOnBoot) { v -> core.updateSettings { it.copy(startOnBoot = v) } }
            Toggle("Click sound on each frame served", s.clickSound) { v -> core.updateSettings { it.copy(clickSound = v) } }
            var replay by remember { mutableStateOf(rt.replayRequested) }
            Toggle("Replay fixture images (debug, applies after restart)", replay) { v -> replay = v; rt.setReplay(v) }
            Text("Replay folder: ${rt.replayFolder.path}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { core.wipeSavedMedia() }) { Text("Delete saved baselines") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter(Char::isDigit).take(6); text.toIntOrNull()?.let(onCommit) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
