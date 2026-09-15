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
    fun read(context: Context, path: String, fallbackMillis: Long): ReadTime {
        val bitmap = BitmapFactory.decodeFile(path) ?: return fallback(fallbackMillis)
        val layout = WatchLayoutClassifier.predict(context, bitmap)
        val model = runCatching { HandModel.load(context) }.getOrNull() ?: return fallback(fallbackMillis)
        val bands = radialBands(bitmap)
        bitmap.recycle()
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
        for (candidateRotation in 0 until 360) {
            val h = bestNear(expectedHourAngle + candidateRotation, 5, 1)
            val m = bestNear(expectedMinuteAngle + candidateRotation, 15, 2)
            // Seconds must not influence dial orientation. A stopped or inaccurate
            // mechanical watch can differ by any number of seconds within the minute.
            val score = predictions[h][1] + predictions[m][2]
            if (score > bestScore) { bestScore = score; rotation = candidateRotation }
        }
        val hourImageAngle = bestNear(expectedHourAngle + rotation, 5, 1)
        val minuteImageAngle = bestNear(expectedMinuteAngle + rotation, 15, 2)
        val secondImageAngle = (0 until 360).maxBy { predictions[it][3] }
        val minute = ((wrap(minuteImageAngle - rotation) + 3) / 6) % 60
        val second = ((wrap(secondImageAngle - rotation) + 3) / 6) % 60
        val correctedHourAngle = wrap(hourImageAngle - rotation - (minute * .5).roundToInt())
        var hour = (correctedHourAngle / 30f).roundToInt() % 12
        if (referenceHour >= 12) hour += 12
        val timeScore = (predictions[hourImageAngle][1] + predictions[minuteImageAngle][2] + predictions[secondImageAngle][3]) / 3f
        return ReadTime(hour, minute, second, timeScore.coerceIn(0f, 1f), layout.layout, layout.confidence)
    }

    private fun radialBands(bitmap: Bitmap): Array<FloatArray> {
        val result = Array(360) { FloatArray(3) }; val count = Array(360) { IntArray(3) }
        val cx = bitmap.width / 2.0; val cy = bitmap.height / 2.0; val radius = minOf(bitmap.width, bitmap.height) * .5
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
