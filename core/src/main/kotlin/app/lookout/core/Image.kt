package app.lookout.core

/** A platform image (a Bitmap on Android, a BufferedImage on the JVM). Treat it as immutable once shared. */
interface Img {
    val width: Int
    val height: Int
}

/** An 8-bit grayscale raster, row-major. */
class GrayImage(val width: Int, val height: Int, val px: ByteArray) {
    operator fun get(x: Int, y: Int): Int = px[y * width + x].toInt() and 0xFF

    val mean: Double
        get() {
            var sum = 0L
            for (b in px) sum += b.toInt() and 0xFF
            return sum.toDouble() / px.size
        }
}

/** Colors are ARGB ints. */
object Colors {
    const val RED = 0xFFFF3B30.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val SHADE = 0xB0000000.toInt()
    const val GRID = 0x90FFFFFF.toInt()
    const val DARK_BG = 0xFF202020.toInt()
}

/** Image operations that each platform supplies. The core composes all images through this interface. */
interface ImageOps {
    fun decode(jpeg: ByteArray): Img
    fun encodeJpeg(img: Img, quality: Int): ByteArray
    fun crop(img: Img, r: PixelRect): Img
    fun scale(img: Img, width: Int, height: Int): Img

    /** Pixels as ARGB, scaled to the given size with smoothing. */
    fun argb(img: Img, width: Int, height: Int): IntArray

    /** A new mutable image filled with [background]. */
    fun canvas(width: Int, height: Int, background: Int): Img

    fun drawImage(dst: Img, src: Img, x: Int, y: Int)
    fun drawRect(dst: Img, r: PixelRect, color: Int, stroke: Float, fill: Boolean = false)
    fun drawLine(dst: Img, x1: Int, y1: Int, x2: Int, y2: Int, color: Int, stroke: Float)

    /** Draws text with its top-left corner at (x, y). */
    fun drawText(dst: Img, text: String, x: Int, y: Int, sizePx: Float, color: Int)
    fun textWidth(text: String, sizePx: Float): Int
}
