package app.lookout.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scores the sharpness of an image or region as the variance of the 4-neighbor Laplacian of luma,
 * divided by [SCALE] (100).
 *
 * The region is scaled down in steps of at most 2x until its long edge is at most [MAX_EDGE]
 * (512 px). Scaling to a bounded working size keeps the cost fixed and makes the score comparable
 * between a 1280 px preview frame and a 12 MP still of the same region, while halving steps avoid
 * bilinear aliasing at large downscale ratios.
 *
 * Scale (unitless, higher is sharper, scene dependent):
 * - 0: flat image
 * - below 1: very soft, blurred, or mostly featureless
 * - 1..20: typical in-focus scene
 * - 20+: crisp high-contrast detail such as text or board markings
 */
object Sharpness {
    const val MAX_EDGE = 512
    const val SCALE = 100.0

    fun score(ops: ImageOps, img: Img, roi: NRect? = null): Double {
        val cropped = if (roi == null || roi.isFull) img else ops.crop(img, roi.toPixels(img.width, img.height))
        var cur = cropped
        val long = max(cur.width, cur.height)
        val targetW: Int
        val targetH: Int
        if (long > MAX_EDGE) {
            val s = MAX_EDGE.toDouble() / long
            targetW = max(1, (cur.width * s).roundToInt())
            targetH = max(1, (cur.height * s).roundToInt())
            while (max(cur.width, cur.height) > MAX_EDGE * 2) {
                cur = ops.scale(cur, max(1, cur.width / 2), max(1, cur.height / 2))
            }
        } else {
            targetW = cur.width
            targetH = cur.height
        }
        if (targetW < 3 || targetH < 3) return 0.0
        val argb = ops.argb(cur, targetW, targetH)
        return laplacianVariance(argb, targetW, targetH) / SCALE
    }

    internal fun laplacianVariance(argb: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        val luma = IntArray(w * h) { ChangeDetector.luma(argb[it]) }
        var sum = 0L
        var sumSq = 0L
        val count = (w - 2).toLong() * (h - 2)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = luma[i - 1] + luma[i + 1] + luma[i - w] + luma[i + w] - 4 * luma[i]
                sum += lap
                sumSq += lap.toLong() * lap
            }
        }
        val mean = sum.toDouble() / count
        return (sumSq.toDouble() / count - mean * mean).coerceAtLeast(0.0)
    }
}
