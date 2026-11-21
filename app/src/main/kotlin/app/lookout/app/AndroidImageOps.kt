package app.lookout.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.lookout.core.ImageOps
import app.lookout.core.Img
import app.lookout.core.PixelRect
import java.io.ByteArrayOutputStream

class BitmapImg(val bitmap: Bitmap) : Img {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/** ImageOps on android.graphics. */
object AndroidImageOps : ImageOps {
    private fun Img.bmp() = (this as BitmapImg).bitmap

    override fun decode(jpeg: ByteArray): Img =
        BitmapImg(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Not an image"))

    override fun encodeJpeg(img: Img, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        img.bmp().compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    override fun crop(img: Img, r: PixelRect): Img = BitmapImg(Bitmap.createBitmap(img.bmp(), r.x, r.y, r.w, r.h))

    override fun scale(img: Img, width: Int, height: Int): Img = BitmapImg(Bitmap.createScaledBitmap(img.bmp(), width, height, true))

    override fun argb(img: Img, width: Int, height: Int): IntArray {
        val b = if (img.width == width && img.height == height) img.bmp() else Bitmap.createScaledBitmap(img.bmp(), width, height, true)
        val px = IntArray(width * height)
        b.getPixels(px, 0, width, 0, 0, width, height)
        return px
    }

    override fun canvas(width: Int, height: Int, background: Int): Img {
        val b = createBitmap(width, height)
        b.eraseColor(background)
        return BitmapImg(b)
    }

    override fun drawImage(dst: Img, src: Img, x: Int, y: Int) {
        Canvas(dst.bmp()).drawBitmap(src.bmp(), x.toFloat(), y.toFloat(), FILTER)
    }

    override fun drawRect(dst: Img, r: PixelRect, color: Int, stroke: Float, fill: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = stroke
        }
        val inset = if (fill) 0f else stroke / 2
        Canvas(dst.bmp()).drawRect(r.x + inset, r.y + inset, r.x + r.w - inset, r.y + r.h - inset, p)
    }

    override fun drawLine(dst: Img, x1: Int, y1: Int, x2: Int, y2: Int, color: Int, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = stroke
        }
        Canvas(dst.bmp()).drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), p)
    }

    override fun drawText(dst: Img, text: String, x: Int, y: Int, sizePx: Float, color: Int) {
        val p = textPaint(sizePx).apply { this.color = color }
        Canvas(dst.bmp()).drawText(text, x.toFloat(), y - p.ascent(), p)
    }

    override fun textWidth(text: String, sizePx: Float): Int = textPaint(sizePx).measureText(text).toInt()

    /** Applies the sensor rotation so the image is upright. */
    fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.SANS_SERIF
    }
}
