package sk.watchaccuracy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WatchRepository(context: Context) {
    private val prefs = context.getSharedPreferences("watch_accuracy", Context.MODE_PRIVATE)

    fun load(): List<Watch> {
        val source = prefs.getString("watches", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(source)
            List(array.length()) { i ->
                val w = array.getJSONObject(i)
                val values = w.optJSONArray("measurements") ?: JSONArray()
                Watch(
                    id = w.getString("id"), brand = w.getString("brand"), model = w.getString("model"),
                    measurements = List(values.length()) { j ->
                        val m = values.getJSONObject(j)
                        Measurement(
                            id = m.getString("id"), capturedAtMillis = m.getLong("capturedAtMillis"),
                            dialHour = m.getInt("dialHour"), dialMinute = m.getInt("dialMinute"),
                            dialSecond = m.getInt("dialSecond"), photoPath = m.getString("photoPath"),
                            shape = DialShape.valueOf(m.getString("shape")),
                            layout = runCatching { DialLayout.valueOf(m.optString("layout", "CLASSIC")) }.getOrDefault(DialLayout.CLASSIC)
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(watches: List<Watch>) {
        val array = JSONArray()
        watches.forEach { watch ->
            val measurements = JSONArray()
            watch.measurements.forEach { m -> measurements.put(JSONObject().apply {
                put("id", m.id); put("capturedAtMillis", m.capturedAtMillis); put("dialHour", m.dialHour)
                put("dialMinute", m.dialMinute); put("dialSecond", m.dialSecond); put("photoPath", m.photoPath)
                put("shape", m.shape.name); put("layout", m.layout.name)
            }) }
            array.put(JSONObject().apply {
                put("id", watch.id); put("brand", watch.brand); put("model", watch.model); put("measurements", measurements)
            })
        }
        prefs.edit().putString("watches", array.toString()).apply()
    }

    fun palette(): AppPalette = runCatching { AppPalette.valueOf(prefs.getString("palette", "CLASSIC")!!) }.getOrDefault(AppPalette.CLASSIC)
    fun savePalette(value: AppPalette) = prefs.edit().putString("palette", value.name).apply()
    fun language(): String = prefs.getString("language", "sk") ?: "sk"
    fun saveLanguage(value: String) = prefs.edit().putString("language", value).apply()
    fun shutterPosition(): ShutterPosition = runCatching { ShutterPosition.valueOf(prefs.getString("shutter_position", "CENTER")!!) }.getOrDefault(ShutterPosition.CENTER)
    fun saveShutterPosition(value: ShutterPosition) = prefs.edit().putString("shutter_position", value.name).apply()
}
