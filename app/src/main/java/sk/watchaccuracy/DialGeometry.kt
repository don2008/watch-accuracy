package sk.watchaccuracy

import kotlin.math.*

/** Coordinates in the original image: x right, y down, both 0..1. */
data class DialPoint(val x: Double, val y: Double) {
    fun valid() = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}

data class DialGeometry(
    val center: DialPoint,
    val hourTip: DialPoint,
    val minuteTip: DialPoint,
    val secondTip: DialPoint,
    /** Minute-track positions at 12, 3, 6, 9, in this order. */
    val markers: List<DialPoint>,
    /** Running-seconds pivot. Null means the main hour/minute pivot. */
    val secondCenter: DialPoint? = null
) {
    fun projection(): DialProjection? = DialProjection.from(markers)

    /** Verifies only that the returned pivots and tips form usable hands on the dial. */
    fun plausible(): Boolean {
        val secondsOrigin = secondCenter ?: center
        if (!(listOf(center, hourTip, minuteTip, secondsOrigin, secondTip) + markers).all { it.valid() }) return false
        val projection = projection() ?: return false
        val origin = projection.map(center) ?: return false
        val projectedSecondsOrigin = projection.map(secondsOrigin) ?: return false
        if (hypot(origin.x, origin.y) > .18) return false
        if (hypot(projectedSecondsOrigin.x, projectedSecondsOrigin.y) > .88) return false

        fun length(from: DialPoint, to: DialPoint): Double {
            val a = projection.map(from) ?: return -1.0
            val b = projection.map(to) ?: return -1.0
            return hypot(b.x - a.x, b.y - a.y)
        }
        if (length(center, hourTip) !in .08..1.20 || length(center, minuteTip) !in .10..1.20) return false
        val secondLength = length(secondsOrigin, secondTip)
        return secondLength in (if (secondCenter == null) .08..1.20 else .025..0.55)
    }

    /**
     * Cross-checks numeric time against the points. This remains useful for tests
     * and diagnostics, but cloud suggestions are not discarded solely because a
     * vision model placed an editable point a few pixels away.
     */
    fun matches(hour: Int, minute: Int, second: Int): Boolean {
        if (hour !in 0..11 || minute !in 0..59 || second !in 0..59 || !plausible()) return false
        val projection = projection() ?: return false
        val secondsOrigin = secondCenter ?: center
        fun difference(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)
        val hourAngle = projection.angle(center, hourTip) ?: return false
        val minuteAngle = projection.angle(center, minuteTip) ?: return false
        val secondAngle = projection.angle(secondsOrigin, secondTip) ?: return false
        return difference(hourAngle, hour * 30.0 + minute * .5 + second / 120.0) <= 8.0 &&
            difference(minuteAngle, minute * 6.0 + second * .1) <= 5.0 &&
            difference(secondAngle, second * 6.0) <= 7.0
    }
}

/** Projective rectification of a planar dial from its four cardinal markers. */
class DialProjection private constructor(private val h: DoubleArray) {
    fun map(p: DialPoint): DialPoint? {
        val w = h[6] * p.x + h[7] * p.y + 1.0
        if (abs(w) < 1e-9) return null
        val x = (h[0] * p.x + h[1] * p.y + h[2]) / w
        val y = (h[3] * p.x + h[4] * p.y + h[5]) / w
        return if (x.isFinite() && y.isFinite()) DialPoint(x, y) else null
    }

    fun angle(origin: DialPoint, tip: DialPoint): Double? {
        val c = map(origin) ?: return null
        val p = map(tip) ?: return null
        if (hypot(p.x - c.x, p.y - c.y) < 1e-6) return null
        return (atan2(p.x - c.x, c.y - p.y) * 180 / PI + 360) % 360
    }

    companion object {
        fun from(points: List<DialPoint>): DialProjection? {
            if (points.size != 4 || points.any { !it.valid() }) return null
            // Reject collapsed, mirrored and crossed quadrilaterals.
            for (i in 0..3) {
                val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
                if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) < .005) return null
            }
            val target = listOf(DialPoint(0.0, -1.0), DialPoint(1.0, 0.0), DialPoint(0.0, 1.0), DialPoint(-1.0, 0.0))
            val rows = Array(8) { DoubleArray(9) }
            points.forEachIndexed { i, p ->
                val q = target[i]
                rows[2 * i] = doubleArrayOf(p.x, p.y, 1.0, 0.0, 0.0, 0.0, -q.x * p.x, -q.x * p.y, q.x)
                rows[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, p.x, p.y, 1.0, -q.y * p.x, -q.y * p.y, q.y)
            }
            for (col in 0..7) {
                val pivot = (col..7).maxBy { abs(rows[it][col]) }
                if (abs(rows[pivot][col]) < 1e-9) return null
                val tmp = rows[col]; rows[col] = rows[pivot]; rows[pivot] = tmp
                val scale = rows[col][col]
                for (j in col..8) rows[col][j] /= scale
                for (i in 0..7) if (i != col) {
                    val factor = rows[i][col]
                    for (j in col..8) rows[i][j] -= factor * rows[col][j]
                }
            }
            return DialProjection(DoubleArray(8) { rows[it][8] })
        }
    }
}

/** Choose AM/PM only; never replace visually read minutes or seconds with phone time. */
fun nearestDialHour(hour12: Int, minute: Int, second: Int, referenceSeconds: Int): Int {
    fun distance(hour: Int): Int {
        val diff = abs(hour * 3600 + minute * 60 + second - referenceSeconds)
        return minOf(diff, 86400 - diff)
    }
    return listOf(hour12 % 12, hour12 % 12 + 12).minBy { distance(it) }
}
