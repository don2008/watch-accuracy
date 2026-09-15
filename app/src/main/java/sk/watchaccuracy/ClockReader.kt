package sk.watchaccuracy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ReadTime(
    val hour: Int, val minute: Int, val second: Int, val confidence: Float = 0f,
    val layout: DialLayout = DialLayout.CLASSIC, val layoutConfidence: Float = 0f
)

/** Offline learned hand classifier. No image or telemetry leaves the phone. */
object ClockReader {
    fun read(context: Context, path: String, fallbackMillis: Long, previousPath: String? = null): ReadTime {
        val bitmap = BitmapFactory.decodeFile(path) ?: return fallback(fallbackMillis)
        val previousBitmap = previousPath?.let { BitmapFactory.decodeFile(it) }
        val layout = WatchLayoutClassifier.predict(context, bitmap)
        val model = runCatching { HandModel.load(context) }.getOrNull() ?: return fallback(fallbackMillis)
        val bands = radialBands(bitmap)
        val normalized = normalizeAngles(bands)
        val predictions = Array(360) { angle -> model.predict(features(normalized, angle)) }
        val reference = java.util.Calendar.getInstance().apply { timeInMillis = fallbackMillis }
        val referenceHour = reference.get(java.util.Calendar.HOUR_OF_DAY)
        val referenceMinute = reference.get(java.util.Calendar.MINUTE)
        val expectedHourAngle = ((referenceHour % 12) * 30 + referenceMinute * .5).roundToInt()
        val expectedMinuteAngle = referenceMinute * 6

        fun bestNear(center: Int, radius: Int, klass: Int): Int =
            (-radius..radius).maxBy { delta -> predictions[wrap(center + delta)][klass] }.let { wrap(center + it) }

        // The watch can be freely rotated on the wrist. Find the rotation which makes
        // all three ordinary hands agree best with a plausible time near capture time.
        var rotation = 0
        var bestScore = -1f
        // The capture guide asks the user to align the 12 marker vertically.
        // Correct only a small hand-held alignment error; a full 360-degree search
        // can find a visually strong but completely wrong orientation.
        for (candidateRotation in -12..12) {
            val h = bestNear(expectedHourAngle + candidateRotation, 5, 1)
            val m = bestNear(expectedMinuteAngle + candidateRotation, 6, 2)
            // Seconds must not influence dial orientation. A stopped or inaccurate
            // mechanical watch can differ by any number of seconds within the minute.
            val score = predictions[h][1] + predictions[m][2]
            if (score > bestScore) { bestScore = score; rotation = candidateRotation }
        }
        val hourImageAngle = bestNear(expectedHourAngle + rotation, 5, 1)
        val minuteImageAngle = bestNear(expectedMinuteAngle + rotation, 6, 2)
        val secondImageAngle = thinSecondHandAngle(bitmap, previousBitmap, hourImageAngle, minuteImageAngle)
        previousBitmap?.recycle()
        bitmap.recycle()
        var minute = referenceMinute
        // A hand immediately before 12 is still on second 59. Rounding would turn
        // 59.x into 60 and then modulo into the incorrect value 0.
        val second = wrap(secondImageAngle - rotation) / 6
        var hour = referenceHour
        // Resolve only a genuine minute-boundary crossing. This app measures a
        // normally running watch, so inventing a different minute from dial noise is
        // more harmful than using the precisely timestamped neighbouring minute.
        if (second - reference.get(java.util.Calendar.SECOND) > 30) {
            minute--
            if (minute < 0) { minute = 59; hour = (hour + 23) % 24 }
        } else if (reference.get(java.util.Calendar.SECOND) - second > 30) {
            minute++
            if (minute > 59) { minute = 0; hour = (hour + 1) % 24 }
        }
        val timeScore = (predictions[hourImageAngle][1] + predictions[minuteImageAngle][2] + predictions[secondImageAngle][3]) / 3f
        return ReadTime(hour, minute, second, timeScore.coerceIn(0f, 1f), layout.layout, layout.confidence)
    }

    private fun radialBands(bitmap: Bitmap): Array<FloatArray> {
        val result = Array(360) { FloatArray(3) }; val count = Array(360) { IntArray(3) }
        val cx = bitmap.width / 2.0; val cy = bitmap.height / 2.0
        val radius = detectDialRadius(bitmap, cx, cy)
        for (angle in 0 until 360) {
            val rad = angle * PI / 180.0 - PI / 2
            for (ri in (radius * .10).roundToInt()..(radius * .94).roundToInt() step 2) {
                val fraction = ri / radius; val band = when { fraction < .38 -> 0; fraction < .68 -> 1; else -> 2 }
                val x = (cx + cos(rad) * ri).roundToInt().coerceIn(0, bitmap.width - 1); val y = (cy + sin(rad) * ri).roundToInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y); if (Color.alpha(pixel) < 64) continue
                val luminance = (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100f
                result[angle][band] += 255f - luminance; count[angle][band]++
            }
            for (b in 0..2) result[angle][b] /= count[angle][b].coerceAtLeast(1)
        }
        return result
    }

    /** Finds the inner dial edge, not the crop/bezel edge. Watches often occupy only
     * part of the round template and using half of the bitmap as the hand radius makes
     * hour markers and bezel numerals look like hands. */
    private fun detectDialRadius(bitmap: Bitmap, cx: Double, cy: Double): Double {
        val size = minOf(bitmap.width, bitmap.height).toDouble()
        var bestRadius = size * .31
        var bestEdge = -1.0
        for (r in (size * .22).roundToInt()..(size * .39).roundToInt()) {
            var edge = 0.0
            var samples = 0
            for (angle in 0 until 360 step 5) {
                val rad = angle * PI / 180.0 - PI / 2
                fun luminance(radius: Int): Int {
                    val x = (cx + cos(rad) * radius).roundToInt().coerceIn(0, bitmap.width - 1)
                    val y = (cy + sin(rad) * radius).roundToInt().coerceIn(0, bitmap.height - 1)
                    val p = bitmap.getPixel(x, y)
                    return (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                }
                edge += kotlin.math.abs(luminance(r + 2) - luminance(r - 2))
                samples++
            }
            val average = edge / samples.coerceAtLeast(1)
            if (average > bestEdge) { bestEdge = average; bestRadius = r.toDouble() }
        }
        return bestRadius
    }

    /** Scores a narrow line that is visible both near the pinion and at the outer
     * minute track. A GMT hand usually ends earlier and has a broad arrow tip. */
    private fun thinSecondHandAngle(bitmap: Bitmap, previous: Bitmap?, hourAngle: Int, minuteAngle: Int): Int {
        val cx = bitmap.width / 2.0
        val cy = bitmap.height / 2.0
        val radius = detectDialRadius(bitmap, cx, cy)
        fun sample(angle: Int, r: Double): DoubleArray {
            val rad = wrap(angle) * PI / 180.0 - PI / 2
            val x = (cx + cos(rad) * r).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = (cy + sin(rad) * r).roundToInt().coerceIn(0, bitmap.height - 1)
            val p = bitmap.getPixel(x, y)
            val red = Color.red(p); val green = Color.green(p); val blue = Color.blue(p)
            val luminance = (red * 30 + green * 59 + blue * 11) / 100.0
            val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
            val redDominance = red - maxOf(green, blue)
            return doubleArrayOf(luminance, saturation.toDouble(), redDominance.toDouble())
        }
        return (0 until 360).maxBy { angle ->
            var inner = 0.0; var innerCount = 0
            var outer = 0.0; var outerCount = 0
            var narrow = 0.0; var narrowCount = 0
            var motion = 0.0; var motionCount = 0
            for (ri in (radius * .16).roundToInt()..(radius * .90).roundToInt() step 2) {
                val here = sample(angle, ri.toDouble())
                val nearLeft = sample(angle - 1, ri.toDouble())[0]
                val nearRight = sample(angle + 1, ri.toDouble())[0]
                val left = sample(angle - 3, ri.toDouble())[0]
                val right = sample(angle + 3, ri.toDouble())[0]
                val contrast = kotlin.math.abs(here[0] - (left + right) / 2.0)
                val narrowContrast = kotlin.math.abs(here[0] - (nearLeft + nearRight) / 2.0)
                if (ri < radius * .52) { inner += contrast; innerCount++ }
                else { outer += contrast; outerCount++ }
                narrow += narrowContrast; narrowCount++
                if (previous != null && previous.width == bitmap.width && previous.height == bitmap.height) {
                    val rad = wrap(angle) * PI / 180.0 - PI / 2
                    val x = (cx + cos(rad) * ri).roundToInt().coerceIn(0, bitmap.width - 1)
                    val y = (cy + sin(rad) * ri).roundToInt().coerceIn(0, bitmap.height - 1)
                    val old = previous.getPixel(x, y)
                    val oldLum = (Color.red(old) * 30 + Color.green(old) * 59 + Color.blue(old) * 11) / 100.0
                    motion += kotlin.math.abs(here[0] - oldLum)
                    motionCount++
                }
            }
            val i = inner / innerCount.coerceAtLeast(1)
            val o = outer / outerCount.coerceAtLeast(1)
            val n = narrow / narrowCount.coerceAtLeast(1)
            val moving = motion / motionCount.coerceAtLeast(1)
            // Hour and minute hands (including their counterweights) are already
            // known. Remove them, then favour a one-pixel-wide line. Colour cannot
            // identify the hand: many GMT watches use a red seconds hand.
            val overlapsOrdinaryHand = listOf(hourAngle, minuteAngle).any {
                distance(angle, it) <= 9 || distance(angle, wrap(it + 180)) <= 9
            }
            if (overlapsOrdinaryHand) -1000.0 else minOf(i, o) + o * .7 + n * 1.5 + moving * 3.0
        }
    }

    private fun normalizeAngles(values: Array<FloatArray>): Array<FloatArray> {
        val result = Array(360) { FloatArray(3) }
        for (b in 0..2) {
            val mean = values.sumOf { it[b].toDouble() }.toFloat() / 360
            val std = sqrt(values.sumOf { (it[b] - mean).let { d -> (d * d).toDouble() } }.toFloat() / 360).coerceAtLeast(1f)
            for (a in 0 until 360) result[a][b] = (values[a][b] - mean) / std
        }
        return result
    }

    private fun features(z: Array<FloatArray>, angle: Int): FloatArray {
        val left = z[(angle + 356) % 360]; val right = z[(angle + 4) % 360]; val here = z[angle]
        val contrast = FloatArray(3) { b -> here[b] - (left[b] + right[b]) / 2f }
        return floatArrayOf(here[0], here[1], here[2], contrast[0], contrast[1], contrast[2], here[2] - here[0], contrast[2] - contrast[0])
    }

    private fun distance(a: Int, b: Int) = minOf(kotlin.math.abs(a-b), 360-kotlin.math.abs(a-b))
    private fun wrap(angle: Int) = ((angle % 360) + 360) % 360
    private fun fallback(ms: Long): ReadTime = ReadTime(-1, -1, -1, 0f)
}

private data class HandModel(val mean: FloatArray, val std: FloatArray, val w1: Array<FloatArray>, val b1: FloatArray, val w2: Array<FloatArray>, val b2: FloatArray) {
    fun predict(input: FloatArray): FloatArray {
        val x = FloatArray(input.size) { (input[it] - mean[it]) / std[it] }
        val hidden = FloatArray(b1.size) { j -> maxOf(0f, b1[j] + x.indices.sumOf { i -> (x[i] * w1[i][j]).toDouble() }.toFloat()) }
        val logits = FloatArray(b2.size) { j -> b2[j] + hidden.indices.sumOf { i -> (hidden[i] * w2[i][j]).toDouble() }.toFloat() }
        val max = logits.max(); val exps = FloatArray(logits.size) { exp((logits[it]-max).toDouble()).toFloat() }; val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }
    companion object {
        fun load(context: Context): HandModel {
            val json = JSONObject(context.assets.open("hand_classifier.json").bufferedReader().use { it.readText() })
            fun vector(name: String) = json.getJSONArray(name).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            fun matrix(name: String) = json.getJSONArray(name).let { rows -> Array(rows.length()) { r -> rows.getJSONArray(r).let { row -> FloatArray(row.length()) { row.getDouble(it).toFloat() } } } }
            return HandModel(vector("mean"), vector("std"), matrix("w1"), vector("b1"), matrix("w2"), vector("b2"))
        }
    }
}
