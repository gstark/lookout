package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import java.awt.Color
import java.awt.image.BufferedImage

/** Synthetic scenes: a desk with a board and an LED, and a "hand" that can enter from the left. */
object Fixtures {
    fun scene(width: Int = 640, height: Int = 480, handX: Double? = null, ledOn: Boolean = false, noiseSeed: Int = 0): AwtImg {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(110, 110, 115)
        g.fillRect(0, 0, width, height)
        g.color = Color(20, 90, 40)
        g.fillRect(width / 4, height / 4, width / 2, height / 2)
        g.color = if (ledOn) Color(255, 40, 40) else Color(60, 20, 20)
        g.fillOval(width * 5 / 8, height * 3 / 8, width / 20, width / 20)
        if (handX != null) {
            g.color = Color(225, 180, 150)
            g.fillRect(0, height / 3, (handX * width).toInt(), height / 4)
        }
        g.dispose()
        if (noiseSeed != 0) {
            val r = java.util.Random(noiseSeed.toLong())
            for (i in 0 until width * height / 20) {
                val x = r.nextInt(width)
                val y = r.nextInt(height)
                val c = Color(img.getRGB(x, y))
                val d = r.nextInt(11) - 5
                img.setRGB(x, y, Color((c.red + d).coerceIn(0, 255), (c.green + d).coerceIn(0, 255), (c.blue + d).coerceIn(0, 255)).rgb)
            }
        }
        return AwtImg(img)
    }

    fun core(source: FrameSource, clock: Clock = Clock.SYSTEM, device: DeviceState = DeviceState(charging = true, batteryPct = 80)): LookoutCore =
        LookoutCore(AwtImageOps, source, MemoryKeyValueStore(), MemoryBlobStore(), { device }, { true }, clock)
}

/** A source the test drives frame by frame. */
class PushSource(private val still: Img? = null) : FrameSource {
    @Volatile private var sink: ((RawFrame) -> Unit)? = null
    @Volatile var last: Img? = null
    var applied = CameraSettings()
    var opens = 0

    override fun lenses() = listOf(LensInfo("back", "back"), LensInfo("front", "front"))
    override val isOpen get() = sink != null
    override suspend fun open(onFrame: (RawFrame) -> Unit) { sink = onFrame; opens++ }
    override suspend fun close() { sink = null }
    override suspend fun captureStill(): RawFrame = RawFrame(still ?: last ?: error("no frame yet"), System.currentTimeMillis(), CameraMeta())
    override suspend fun apply(settings: CameraSettings): CameraSettings { applied = applied.merge(settings); return applied }
    override fun setAnalysisFps(fps: Double) {}

    fun push(img: Img, at: Long = System.currentTimeMillis(), meta: CameraMeta = CameraMeta()) {
        last = img
        sink?.invoke(RawFrame(img, at, meta))
    }
}
