package app.lookout.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** A point in pixels or millimetres. */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/**
 * A projective map of the plane: a 3 × 3 matrix [m], row-major, applied to (x, y, 1).
 * [fit] solves it from point pairs.
 */
class Homography(val m: DoubleArray) {
    init {
        require(m.size == 9) { "A homography has 9 entries" }
    }

    fun map(p: Pt): Pt = map(p.x, p.y)

    fun map(x: Double, y: Double): Pt {
        val w = m[6] * x + m[7] * y + m[8]
        return Pt((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** This map after [first]: x maps to this(first(x)). */
    operator fun times(first: Homography): Homography {
        val a = m
        val b = first.m
        return Homography(DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        })
    }

    fun inverse(): Homography {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-18) { "The homography is singular" }
        return Homography(doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        ))
    }

    /** The root mean square distance between this map of each of [from] and its pair in [to]. */
    fun rms(from: List<Pt>, to: List<Pt>): Double =
        sqrt(from.indices.sumOf { val d = map(from[it]).dist(to[it]); d * d } / from.size)

    companion object {
        fun scaling(sx: Double, sy: Double, tx: Double = 0.0, ty: Double = 0.0) =
            Homography(doubleArrayOf(sx, 0.0, tx, 0.0, sy, ty, 0.0, 0.0, 1.0))

        /**
         * The homography that maps each of [from] to its pair in [to]. Four pairs give the exact map; more give
         * the least-squares fit of the linear equations (the direct linear transform with h33 = 1). Both point
         * sets are first moved to their centroid and scaled to a mean distance of √2, which keeps the 8 × 8
         * normal equations well conditioned.
         */
        fun fit(from: List<Pt>, to: List<Pt>): Homography {
            require(from.size == to.size && from.size >= 4) { "A homography needs at least 4 point pairs" }
            val tf = normalizer(from)
            val tt = normalizer(to)
            val ata = Array(8) { DoubleArray(8) }
            val atb = DoubleArray(8)
            for (k in from.indices) {
                val (x, y) = tf.map(from[k])
                val (u, v) = tt.map(to[k])
                val rows = arrayOf(
                    doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y) to u,
                    doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y) to v,
                )
                for ((row, rhs) in rows) for (i in 0 until 8) {
                    atb[i] += row[i] * rhs
                    for (j in 0 until 8) ata[i][j] += row[i] * row[j]
                }
            }
            val h = solve(ata, atb) ?: throw LookoutError("degenerate_points", 400, "The points do not define a perspective map (three of them lie on a line)")
            return tt.inverse() * Homography(h + 1.0) * tf
        }

        private fun normalizer(pts: List<Pt>): Homography {
            val cx = pts.sumOf { it.x } / pts.size
            val cy = pts.sumOf { it.y } / pts.size
            val mean = pts.sumOf { hypot(it.x - cx, it.y - cy) } / pts.size
            val s = if (mean > 1e-12) sqrt(2.0) / mean else 1.0
            return scaling(s, s, -s * cx, -s * cy)
        }

        /** Solves [a] x = [b] by Gaussian elimination with partial pivoting. Null when [a] is singular. Changes its inputs. */
        fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val scale = a.maxOf { r -> r.maxOf { abs(it) } }.takeIf { it > 0.0 } ?: return null
            for (col in 0 until n) {
                val p = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[p][col]) < 1e-12 * scale) return null
                a[col] = a[p].also { a[p] = a[col] }
                b[col] = b[p].also { b[p] = b[col] }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}
