package app.lookout.app

import android.graphics.Bitmap
import android.media.MediaActionSound
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import app.lookout.core.GridSpec
import app.lookout.core.LookoutSettings
import app.lookout.core.NRect
import app.lookout.core.PowerMode
import app.lookout.core.Roi
import app.lookout.server.Qr
import app.lookout.server.setupPayload
import kotlinx.coroutines.delay
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
private val Green = Color(0xFF22A45D)

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
    var lastServed by remember { mutableLongStateOf(0L) }
    val flash = remember { Animatable(0f) }
    val sound = remember { MediaActionSound() }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    LaunchedEffect(Unit) {
        core.served.collect {
            lastServed = it.ts
            if (core.settings.value.clickSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            flash.snapTo(1f)
            flash.animateTo(0f, tween(700))
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusBar(rt, running, paused, lastServed)
            checklist.filter { !it.ok }.takeIf { it.isNotEmpty() }?.let { missing -> Checklist(missing, actions.fixCheck) }
            PreviewPane(rt, flash.value)
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
            RoiList(rt)
            ActivityFeed(rt)
        }
        prompt?.let { p -> PromptOverlay(p, onAnswer = { core.human.answer(p.id, it) }) }
    }
    if (showSetup) SetupSheet(rt, settings, actions, onDismiss = { showSetup = false })
}

@Composable
private fun StatusBar(rt: Runtime, running: Boolean, paused: Boolean, lastServed: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(2_000) } }
    val d = remember(now) { rt.device.read() }
    val open by rt.core.session.open.collectAsState()
    val agentActive = now - lastServed < 30_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (agentActive) Green else Color.Gray))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !running -> "Stopped"
                        paused -> "Paused"
                        agentActive -> "Agent is looking"
                        else -> "Serving"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(rt.baseUrl.get() + "/mcp", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(
                listOfNotNull(
                    d.batteryPct?.let { "Battery $it%" + if (d.charging == true) " ⚡" else "" },
                    d.temperatureC?.let { "%.0f °C".format(it) },
                    "thermal ${d.thermal.name.lowercase()}",
                    "${rt.core.session.effectiveMode().wire} mode",
                    if (open) "camera open" else "camera closed",
                    if (rt.usingReplay) "REPLAY" else null,
                ).joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

@Composable
private fun PreviewPane(rt: Runtime, flash: Float) {
    val core = rt.core
    val frame by core.store.latest.collectAsState()
    val rois = remember(core.events.latestId.collectAsState().value) { core.rois.list() }
    var showGrid by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var pending by remember { mutableStateOf<NRect?>(null) }

    DisposableEffect(Unit) {
        core.session.previewActive = true
        onDispose { core.session.previewActive = false }
    }

    val bitmap: Bitmap? = (frame?.image as? BitmapImg)?.bitmap
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    val border = Red.copy(alpha = flash)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black)
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
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                rois.forEach { r ->
                    Text(
                        r.name, color = Color.Yellow, fontSize = 12.sp,
                        modifier = Modifier.offset(x = maxWidth * r.rect.x.toFloat() + 4.dp, y = maxHeight * r.rect.y.toFloat() + 2.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showGrid, onClick = { showGrid = !showGrid }, label = { Text("Grid 8x6") })
            FilterChip(selected = editing, onClick = { editing = !editing }, label = { Text(if (editing) "Drag to draw ROI" else "Edit ROIs") })
        }
    }
    pending?.let { rect -> RoiNameDialog(initial = "", onDone = { name -> runCatching { core.rois.put(Roi(name, rect)) }; core.events.add("roi"); pending = null }, onCancel = { pending = null }) }
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
        core.events.recent(120).filter { it.type in setOf("request", "motion", "still", "change", "say", "ask", "camera", "pause", "thermal") }.takeLast(20).reversed()
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
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The agent asks", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupSheet(rt: Runtime, s: LookoutSettings, actions: MainActions, onDismiss: () -> Unit) {
    val core = rt.core
    var token by remember { mutableStateOf(rt.tokens.get()) }
    val base = rt.baseUrl.get()
    val qr = remember(token, base) { qrBitmap(setupPayload(base, token)) }
    val command = app.lookout.server.claudeCommand(base, token)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect Claude Code", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Image(qr.asImageBitmap(), "Setup QR code", Modifier.size(220.dp).align(Alignment.CenterHorizontally))
            Text(command, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.copy(command) }) { Text("Copy command") }
                OutlinedButton(onClick = { token = rt.tokens.regenerate() }) { Text("New token") }
            }
            Text("Give the phone a DHCP reservation on your router, so this address does not change.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun qrBitmap(text: String): Bitmap {
    val m = Qr.matrix(text)
    val n = m.size
    val scale = 8
    val px = IntArray(n * scale * n * scale) { i ->
        val x = i % (n * scale)
        val y = i / (n * scale)
        if (m[y / scale][x / scale]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(px, n * scale, n * scale, Bitmap.Config.ARGB_8888)
}
