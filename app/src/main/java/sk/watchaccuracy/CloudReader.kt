package sk.watchaccuracy

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CloudReading(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val confidence: Float
)

object CloudReader {
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun read(path: String, apiKey: String): CloudReading? = runCatching {
        if (apiKey.isBlank()) return null
        val file = java.io.File(path)
        if (!file.isFile || file.length() > 8L * 1024L * 1024L) return null
        val mime = if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
        val prompt = "You read a mechanical analog watch dial from the attached image. " +
            "Return ONLY JSON: {\"hour\":0,\"minute\":0,\"second\":0,\"confidence\":0.0}. " +
            "Use hour 0..23 and minute/second 0..59. Read the hands from the dial; " +
            "never infer the time from filename, upload time or prompt. If unclear, " +
            "give your best estimate with lower confidence. Ignore subdials unless " +
            "they are clearly the main seconds hand."
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", mime)
                        .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))))
                )
            }))
            put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
        }.toString()
        val endpoint = ENDPOINT_PREFIX + MODEL + ":generateContent?key=" + java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) return null
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val text = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val json = JSONObject(text.trim()
            .removePrefix("\u0060\u0060\u0060json")
            .removePrefix("\u0060\u0060\u0060")
            .removeSuffix("\u0060\u0060\u0060").trim())
        CloudReading(
            json.getInt("hour").coerceIn(0, 23),
            json.getInt("minute").coerceIn(0, 59),
            json.getInt("second").coerceIn(0, 59),
            json.optDouble("confidence", .5).toFloat().coerceIn(0f, 1f)
        )
    }.getOrNull()
}
