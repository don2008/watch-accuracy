package sk.watchaccuracy

import android.graphics.BitmapFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class ReadTime(val hour: Int, val minute: Int, val second: Int)

/**
 * Lightweight on-device first estimate. It scores dark radial lines from the
 * centre of the photographed dial. Unusual hands, reflections and subdials can
 * confuse it, therefore the confirmation screen always remains authoritative.
 */
object ClockReader {
    fun read(path: String, fallbackMillis: Long): ReadTime {
        val bitmap = BitmapFactory.decodeFile(path) ?: return fallback(fallbackMillis)
        val size = minOf(bitmap.width, bitmap.height)
        val cx = bitmap.width / 2.0
        val cy = bitmap.height / 2.0
        val scored = (0 until 360).map { degree ->
            var darkness = 0.0
            var count = 0
            val rad = degree * PI / 180.0 - PI / 2
            for (r in (size * .10).roundToInt()..(size * .43).roundToInt() step 2) {
                val x = (cx + cos(rad) * r).roundToInt().coerceIn(0, bitmap.width - 1)
                val y = (cy + sin(rad) * r).roundToInt().coerceIn(0, bitmap.height - 1)
                val p = bitmap.getPixel(x, y)
                darkness += 255 - ((android.graphics.Color.red(p) * 30 + android.graphics.Color.green(p) * 59 + android.graphics.Color.blue(p) * 11) / 100)
                count++
            }
            degree to darkness / count.coerceAtLeast(1)
        }.sortedByDescending { it.second }
        val hands = mutableListOf<Int>()
        for ((angle, _) in scored) {
            if (hands.all { circularDistance(it, angle) > 12 }) hands += angle
            if (hands.size == 3) break
        }
        if (hands.size < 2) return fallback(fallbackMillis)
        val minuteAngle = hands.minByOrNull { angle -> angle % 6 } ?: hands[0]
        val hourAngle = hands.filter { it != minuteAngle }.minByOrNull { angle -> kotlin.math.abs((angle / 30.0) - kotlin.math.round(angle / 30.0)) } ?: hands[1]
        val secondAngle = hands.firstOrNull { it != minuteAngle && it != hourAngle } ?: 0
        return ReadTime((hourAngle / 30) % 12, (minuteAngle / 6) % 60, (secondAngle / 6) % 60)
    }

    private fun circularDistance(a: Int, b: Int): Int = minOf(kotlin.math.abs(a - b), 360 - kotlin.math.abs(a - b))
    private fun fallback(ms: Long): ReadTime {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return ReadTime(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
    }
}
