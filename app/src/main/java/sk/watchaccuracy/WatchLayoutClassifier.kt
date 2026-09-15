package sk.watchaccuracy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.sqrt

data class LayoutPrediction(val layout: DialLayout, val confidence: Float)

/** Tiny fully-offline layout classifier exported by train_layout_classifier.py. */
object WatchLayoutClassifier {
    fun predict(context: Context, source: Bitmap): LayoutPrediction {
        val model = runCatching { LayoutModel.load(context) }.getOrNull()
            ?: return LayoutPrediction(DialLayout.CLASSIC, 0f)
        val scaled = Bitmap.createScaledBitmap(source, model.grid, model.grid, true)
        val pixels = IntArray(model.grid * model.grid)
        scaled.getPixels(pixels, 0, model.grid, 0, 0, model.grid, model.grid)
        if (scaled !== source) scaled.recycle()
        val raw = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * .299f + Color.green(p) * .587f + Color.blue(p) * .114f) / 255f
        }
        val avg = raw.average().toFloat()
        val deviation = sqrt(raw.sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / raw.size).coerceAtLeast(.08f)
        val input = FloatArray(raw.size) { (raw[it] - avg) / deviation }
        val probabilities = model.predict(input)
        val index = probabilities.indices.maxBy { probabilities[it] }
        return LayoutPrediction(model.layouts[index], probabilities[index])
    }
}

private data class LayoutModel(
    val grid: Int, val layouts: List<DialLayout>, val mean: FloatArray, val std: FloatArray,
    val w1: Array<FloatArray>, val b1: FloatArray, val w2: Array<FloatArray>, val b2: FloatArray
) {
    fun predict(input: FloatArray): FloatArray {
        val x = FloatArray(input.size) { (input[it] - mean[it]) / std[it].coerceAtLeast(1e-6f) }
        val hidden = FloatArray(b1.size) { j ->
            maxOf(0f, b1[j] + x.indices.sumOf { i -> (x[i] * w1[i][j]).toDouble() }.toFloat())
        }
        val logits = FloatArray(b2.size) { j ->
            b2[j] + hidden.indices.sumOf { i -> (hidden[i] * w2[i][j]).toDouble() }.toFloat()
        }
        val largest = logits.max()
        val values = FloatArray(logits.size) { exp((logits[it] - largest).toDouble()).toFloat() }
        val total = values.sum().coerceAtLeast(1e-9f)
        return FloatArray(values.size) { values[it] / total }
    }

    companion object {
        fun load(context: Context): LayoutModel {
            val root = JSONObject(GZIPInputStream(context.assets.open("layout_classifier.json.gz")).bufferedReader().use { it.readText() })
            fun vector(name: String) = root.getJSONArray(name).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            fun matrix(name: String) = root.getJSONArray(name).let { rows ->
                Array(rows.length()) { r -> rows.getJSONArray(r).let { row -> FloatArray(row.length()) { row.getDouble(it).toFloat() } } }
            }
            val names = root.getJSONArray("layouts")
            val layouts = List(names.length()) { DialLayout.valueOf(names.getString(it).uppercase()) }
            return LayoutModel(root.getInt("grid"), layouts, vector("mean"), vector("std"),
                matrix("w1"), vector("b1"), matrix("w2"), vector("b2"))
        }
    }
}
