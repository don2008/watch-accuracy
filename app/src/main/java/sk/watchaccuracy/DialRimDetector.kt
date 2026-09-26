package sk.watchaccuracy

import kotlin.math.*

/** Rim estimate in input pixels; it is a starting point, not a verified hand pivot. */
data class DialRim(val x: Double, val y: Double, val rx: Double, val ry: Double, val score: Double)

/** Bounded, low-resolution search. Transparent crop boundaries never count as dial edges. */
object DialRimDetector {
    fun detect(width: Int, height: Int, luminance: IntArray): DialRim? {
        require(width > 0 && height > 0 && luminance.size == width * height)
        val size = minOf(width, height).toDouble()
        val cosines = DoubleArray(36) { cos(it * PI / 18) }
        val sines = DoubleArray(36) { sin(it * PI / 18) }
        fun sample(x: Double, y: Double): Int {
            val ix = x.roundToInt(); val iy = y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) luminance[iy * width + ix] else -1
        }
        fun score(cx: Double, cy: Double, radius: Double, ratio: Double): Double {
            val edges = IntArray(36)
            var visible = 0
            var rising = 0
            var falling = 0
            for (i in edges.indices) {
                var edge = -1
                var signed = 0
                for (delta in -2..2 step 2) {
                    val a = sample(cx + cosines[i] * (radius + delta - 2), cy + sines[i] * (radius + delta - 2) * ratio)
                    val b = sample(cx + cosines[i] * (radius + delta + 2), cy + sines[i] * (radius + delta + 2) * ratio)
                    if (a >= 0 && b >= 0 && abs(a - b) > edge) {
                        edge = abs(a - b); signed = b - a
                    }
                }
                if (edge >= 0) visible++
                if (signed >= 10) rising++
                if (signed <= -10) falling++
                edges[i] = maxOf(0, edge)
            }
            // A dial rim separates the same two surfaces around its circumference.
            // Random logo/hand edges alternate polarity; an artificial alpha mask
            // supplies no valid samples on its outer side.
            if (visible < 28 || maxOf(rising, falling) < 27) return 0.0
            var sum = 0.0
            // Text, hands, glare and the reserve indicator affect isolated angles.
            // Require evidence throughout the rim, trimming outliers in each quadrant.
            for (quadrant in 0..3) {
                val sector = edges.copyOfRange(quadrant * 9, quadrant * 9 + 9).sorted()
                val sectorMean = (2..6).sumOf { sector[it] }.toDouble() / 5
                if (sectorMean < 12) return 0.0
                sum += sectorMean * 5
            }
            return sum / 20.0
        }
        var best = DialRim(width / 2.0, height / 2.0, size * .45, size * .45, 0.0)
        fun consider(cx: Double, cy: Double, radius: Double, ratio: Double) {
            val value = score(cx, cy, radius, ratio)
            if (value > best.score) best = DialRim(cx, cy, radius, radius * ratio, value)
        }
        for (ix in -6..6) for (iy in -6..6) for (ri in 0..16) for (qi in 0..4) {
            consider(width / 2.0 + ix * size * .025, height / 2.0 + iy * size * .025,
                size * (.26 + ri * .015), .8 + qi * .1)
        }
        val coarse = best
        for (ix in -3..3) for (iy in -3..3) for (ri in -2..2) for (qi in -1..1) {
            consider(coarse.x + ix, coarse.y + iy, coarse.rx + ri, coarse.ry / coarse.rx + qi * .025)
        }
        return best.takeIf { it.score >= 8.0 }
    }
}
