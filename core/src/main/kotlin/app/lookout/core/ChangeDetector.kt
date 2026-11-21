package app.lookout.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class ChangeResult(val score: Double, val regions: List<NRect>)

/**
 * Compares 160x120 grayscale thumbnails. The score is the fraction of pixels whose brightness
 * moved by more than [pixelThreshold]. Changed 8x8 cells are joined into region boxes.
 */
class ChangeDetector(private val ops: ImageOps, private val pixelThreshold: Int = 25) {

    /** A 160x120 grayscale thumbnail of [roi] in [img]. It box-averages 2x2 to suppress sensor noise. */
    fun thumb(img: Img, roi: NRect = NRect.FULL): GrayImage {
        val src = if (roi.isFull) img else ops.crop(img, roi.toPixels(img.width, img.height))
        val argb = ops.argb(src, W * 2, H * 2)
        val out = ByteArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                var sum = 0
                for (dy in 0..1) for (dx in 0..1) sum += luma(argb[(y * 2 + dy) * W * 2 + x * 2 + dx])
                out[y * W + x] = (sum / 4).toByte()
            }
        }
        return GrayImage(W, H, out)
    }

    fun compare(a: GrayImage, b: GrayImage): ChangeResult {
        require(a.width == b.width && a.height == b.height) { "Thumbnails differ in size" }
        val cellsX = a.width / CELL
        val cellsY = a.height / CELL
        val cellCounts = IntArray(cellsX * cellsY)
        var changed = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (abs(a[x, y] - b[x, y]) > pixelThreshold) {
                    changed++
                    val cx = (x / CELL).coerceAtMost(cellsX - 1)
                    val cy = (y / CELL).coerceAtMost(cellsY - 1)
                    cellCounts[cy * cellsX + cx]++
                }
            }
        }
        val hot = BooleanArray(cellCounts.size) { cellCounts[it] >= CELL * CELL / 4 }
        return ChangeResult(changed.toDouble() / (a.width * a.height), regions(hot, cellsX, cellsY))
    }

    /** Joins changed cells (8-connected) and returns the bounding box of each group. */
    private fun regions(hot: BooleanArray, cw: Int, ch: Int): List<NRect> {
        val seen = BooleanArray(hot.size)
        val out = mutableListOf<NRect>()
        for (start in hot.indices) {
            if (!hot[start] || seen[start]) continue
            var minX = cw; var minY = ch; var maxX = -1; var maxY = -1
            val stack = ArrayDeque<Int>().apply { add(start) }
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % cw
                val y = i / cw
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until cw && ny in 0 until ch) {
                        val n = ny * cw + nx
                        if (hot[n] && !seen[n]) { seen[n] = true; stack.add(n) }
                    }
                }
            }
            out += NRect(minX.toDouble() / cw, minY.toDouble() / ch, (maxX - minX + 1).toDouble() / cw, (maxY - minY + 1).toDouble() / ch)
        }
        return out.sortedByDescending { it.w * it.h }.take(MAX_REGIONS)
    }

    companion object {
        const val W = 160
        const val H = 120
        const val CELL = 8
        const val MAX_REGIONS = 12

        fun luma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}
