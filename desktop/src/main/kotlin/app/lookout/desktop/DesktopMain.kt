package app.lookout.desktop

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import app.lookout.core.Clock
import app.lookout.core.DeviceState
import app.lookout.core.DirectoryBlobStore
import app.lookout.core.Img
import app.lookout.core.LookoutCore
import app.lookout.core.MemoryKeyValueStore
import app.lookout.core.PowerMode
import app.lookout.core.ReplayFrameSource
import app.lookout.server.LookoutServer
import app.lookout.server.TokenStore
import app.lookout.server.claudeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files

/**
 * Runs Lookout on the Mac with a replay source, for testing the API and MCP without the phone.
 *
 *   ./gradlew :desktop:run --args="--port 8090 --replay fixtures/print --fps 2"
 *
 * Set LOOKOUT_TOKEN to fix the token; otherwise a new one is printed.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = true).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val port = opts["port"]?.toInt() ?: 8090
    val fps = opts["fps"]?.toDouble() ?: 2.0
    val images: List<Img> = opts["replay"]?.let(::loadFolder) ?: syntheticScenes()
    val kv = MemoryKeyValueStore()
    System.getenv("LOOKOUT_TOKEN")?.let { kv.put(TokenStore.KEY, it) }
    val data = Files.createTempDirectory("lookout-desktop").toFile()
    val core = LookoutCore(
        AwtImageOps, ReplayFrameSource(images, fps, ops = AwtImageOps), kv, DirectoryBlobStore(File(data, "baselines")),
        { DeviceState(batteryPct = 100, charging = true, temperatureC = 30.0) }, { text -> println("[say] $text"); true },
        Clock.SYSTEM, "desktop-replay", recordingsDir = File(data, "recordings"),
    )
    core.updateSettings { it.copy(port = port, powerMode = PowerMode.WARM) }
    core.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    val tokens = TokenStore(kv)
    val base = "http://127.0.0.1:$port"
    println("Lookout desktop replay: ${images.size} frames at $fps fps")
    println("URL:   $base/?token=${tokens.get()}")
    println("Token: ${tokens.get()}")
    println(claudeCommand(base, tokens.get()))
    LookoutServer(core, tokens, { base }, port).start(wait = true)
}

private fun loadFolder(path: String): List<Img> {
    val files = File(path).listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name }.orEmpty()
    require(files.isNotEmpty()) { "No .jpg or .png files in $path" }
    return files.map { AwtImageOps.read(it) }
}

/** A board with a blinking LED and a hand that sweeps in now and then. */
private fun syntheticScenes(): List<Img> = List(40) { i ->
    val img = BufferedImage(1280, 960, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color(110, 110, 115); g.fillRect(0, 0, 1280, 960)
    g.color = Color(20, 90, 40); g.fillRect(320, 240, 640, 480)
    g.color = if (i % 8 < 4) Color(255, 40, 40) else Color(60, 20, 20); g.fillOval(800, 360, 64, 64)
    g.color = Color.WHITE; g.font = g.font.deriveFont(48f); g.drawString("LOOKOUT-01", 400, 330)
    if (i in 24..31) { g.color = Color(225, 180, 150); g.fillRect(0, 320, (i - 23) * 80, 240) }
    g.dispose()
    AwtImg(img)
}
