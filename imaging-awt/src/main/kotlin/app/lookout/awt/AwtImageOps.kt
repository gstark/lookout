package app.lookout.awt

import app.lookout.core.ImageOps
import app.lookout.core.Img
import app.lookout.core.PixelRect
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class AwtImg(val image: BufferedImage) : Img {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
}

/** ImageOps on java.awt, for JVM tests and the desktop replay runner. */
object AwtImageOps : ImageOps {
    private fun Img.bi() = (this as AwtImg).image

    override fun decode(jpeg: ByteArray): Img =
        AwtImg(rgb(ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("Not an image")))

    override fun encodeJpeg(img: Img, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(rgb(img.bi()), null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    override fun crop(img: Img, r: PixelRect): Img = AwtImg(copy(img.bi().getSubimage(r.x, r.y, r.w, r.h)))

    override fun scale(img: Img, width: Int, height: Int): Img {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img.bi(), 0, 0, width, height, null)
        g.dispose()
        return AwtImg(out)
    }

    override fun argb(img: Img, width: Int, height: Int): IntArray {
        val s = (if (img.width == width && img.height == height) img else scale(img, width, height)).bi()
        return s.getRGB(0, 0, width, height, null, 0, width)
    }

    override fun canvas(width: Int, height: Int, background: Int): Img {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(background, true)
        g.fillRect(0, 0, width, height)
        g.dispose()
        return AwtImg(out)
    }

    override fun drawImage(dst: Img, src: Img, x: Int, y: Int) = draw(dst) { drawImage(src.bi(), x, y, null) }

    override fun drawRect(dst: Img, r: PixelRect, color: Int, stroke: Float, fill: Boolean) = draw(dst) {
        this.color = Color(color, true)
        if (fill) fillRect(r.x, r.y, r.w, r.h) else {
            this.stroke = BasicStroke(stroke)
            drawRect(r.x, r.y, r.w - 1, r.h - 1)
        }
    }

    override fun drawLine(dst: Img, x1: Int, y1: Int, x2: Int, y2: Int, color: Int, stroke: Float) = draw(dst) {
        this.color = Color(color, true)
        this.stroke = BasicStroke(stroke)
        drawLine(x1, y1, x2, y2)
    }

    override fun drawText(dst: Img, text: String, x: Int, y: Int, sizePx: Float, color: Int) = draw(dst) {
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(sizePx)
        this.color = Color(color, true)
        drawString(text, x, y + fontMetrics.ascent)
    }

    override fun textWidth(text: String, sizePx: Float): Int {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(sizePx)
        return g.fontMetrics.stringWidth(text).also { g.dispose() }
    }

    fun read(file: File): Img = AwtImg(rgb(ImageIO.read(file) ?: error("Not an image: $file")))

    fun solid(width: Int, height: Int, color: Int): AwtImg = canvas(width, height, color) as AwtImg

    private inline fun draw(dst: Img, block: java.awt.Graphics2D.() -> Unit) {
        val g = dst.bi().createGraphics()
        try { g.block() } finally { g.dispose() }
    }

    private fun copy(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    private fun rgb(src: BufferedImage): BufferedImage = if (src.type == BufferedImage.TYPE_INT_RGB) src else copy(src)
}
