package sk.watchaccuracy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** A separate, unconfirmed suggestion. Geometry must agree before it can be applied. */
data class CloudReading(
    val hour: Int, val minute: Int, val second: Int,
    val geometry: DialGeometry, val model: String
) {
    fun asReadTime(referenceMillis: Long): ReadTime {
        val reference = Calendar.getInstance().apply { timeInMillis = referenceMillis }
        val secondsOfDay = reference.get(Calendar.HOUR_OF_DAY) * 3600 + reference.get(Calendar.MINUTE) * 60 + reference.get(Calendar.SECOND)
        return ReadTime(
            nearestDialHour(hour, minute, second, secondsOfDay), minute, second,
            centerX = geometry.center.x.toFloat(), centerY = geometry.center.y.toFloat(),
            geometry = geometry
        )
    }
}
data class CloudReadResult(val reading: CloudReading?, val error: String? = null, val httpCode: Int? = null)

object CloudReader {
    private const val PRIMARY_MODEL = "gemini-3.8-flash"
    private const val FALLBACK_MODEL = "gemini-3.5-flash-lite"
    private const val COMPATIBILITY_MODEL = "gemini-2.5-flash"
    private const val ENDPOINT_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun readDetailed(path: String, apiKey: String): CloudReadResult {
        if (apiKey.isBlank()) return CloudReadResult(null)
        val bytes = runCatching {
            val source = requireNotNull(BitmapFactory.decodeFile(path))
            try {
                val scale = minOf(1.0, 1600.0 / maxOf(source.width, source.height))
                val jpeg = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    Canvas(jpeg).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(source, null, android.graphics.Rect(0, 0, jpeg.width, jpeg.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    }
                    ByteArrayOutputStream().use { out ->
                        check(jpeg.compress(Bitmap.CompressFormat.JPEG, 94, out))
                        out.toByteArray()
                    }
                } finally { jpeg.recycle() }
            } finally { source.recycle() }
        }.getOrElse { return CloudReadResult(null, "Fotografiu sa nepodarilo pripraviť") }
        val key = apiKey.trim()
        val attempts = listOf(
            PRIMARY_MODEL to false,
            PRIMARY_MODEL to true,
            FALLBACK_MODEL to false,
            COMPATIBILITY_MODEL to true
        )
        var last = CloudReadResult(null, "Gemini: požiadavku sa nepodarilo dokončiť.")
        for ((model, legacySchema) in attempts) {
            val result = request(model, bytes, key, legacySchema)
            last = result
            // A response without an HTTP error was processed by the model; do not
            // replace a meaningful refusal or geometry error with another attempt.
            if (result.httpCode == null) return result
            if (result.httpCode in setOf(401, 403, 429)) return result
            if (result.httpCode == 400 && result.error.orEmpty().contains("API kľúč je neplatný")) return result
            if (result.httpCode !in setOf(400, 404, 503)) return result
            if (result.httpCode == 503) Thread.sleep(900)
        }
        return last
    }

    internal fun requestBody(base64Image: String, legacySchema: Boolean = false): JSONObject {
        val prompt = """
            Inspect this analog watch photograph using only visible evidence. Locate the actual
            pivot where the hands meet, not the image centre or brand logo. Account for dial
            rotation and perspective. Distinguish hour and minute tips by tracing each hand
            from the pivot; do not mistake their counterweights, reflections, indices or a
            power-reserve indicator (common on Grand Seiko Spring Drive) for a time hand.
            A thin seconds hand can extend in both directions: report the reading tip.
            Never use a typical advertising time, filename, phone time or upload time.
            Independently read the time and locate the image points. All coordinates are
            normalized [x,y], x from left to right and y from top to bottom, in [0,1].
            Locate 12,3,6,9 on the SAME minute-track circle, not the bezel or tips of indices.
            Return a JSON object with:
            readable: boolean,
            reason: one of NONE, AMBIGUOUS_HANDS, SECONDS_NOT_VISIBLE, LANDMARKS_NOT_VISIBLE, UNSUPPORTED_LAYOUT,
            layout: one of CLASSIC, GMT, SMALL_SECONDS, CHRONOGRAPH, REGULATOR, JUMP_HOUR,
            hour: integer 0..11 (12 becomes 0; AM/PM cannot be read from a 12-hour dial),
            minute: integer 0..59, second: integer 0..59,
            center: [x,y], hourTip: [x,y], minuteTip: [x,y], secondTip: [x,y],
            markers: [[x12,y12],[x3,y3],[x6,y6],[x9,y9]].
            CLASSIC means hour, minute and seconds share one pivot. A date window,
            a power-reserve arc with its own pointer, or a Spring Drive movement DOES NOT
            change that layout. In particular, a Grand Seiko with central seconds plus
            a power-reserve gauge is CLASSIC, not SMALL_SECONDS or REGULATOR.
            Cardinal points lie on the minute track: a date window replacing the numeral
            3 does not hide the 15-minute track position if its ticks remain visible.
            This automatic geometry check supports CLASSIC central-seconds dials only.
            For any other layout, hidden or ambiguous hands, unreadable seconds, or missing
            cardinal landmarks set readable=false, choose the specific reason, and use
            null for every time/coordinate field instead of guessing. Include all schema fields. If readable=true use reason=NONE.
            Return only JSON. Do not invent confidence percentages.
        """.trimIndent()
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(
            JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", base64Image))
        )
        val generationConfig = JSONObject().put("temperature", 0)
        if (legacySchema) {
            // Compatibility mode deliberately omits a schema. Different Gemini
            // model generations accept different schema dialects/field names.
            // The response is still required to be JSON and is strictly
            // validated by parse() before any value reaches the UI.
            generationConfig.put("responseMimeType", "application/json")
        } else {
            generationConfig.put("responseFormat", JSONObject().put("text", JSONObject()
                .put("mimeType", "application/json").put("schema", responseSchema())))
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", generationConfig)
    }

    internal fun responseSchema(): JSONObject {
        fun enumeration(vararg values: String) = JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))
        fun integer(maximum: Int) = JSONObject().put("type", JSONArray(listOf("integer", "null")))
            .put("minimum", 0).put("maximum", maximum)
        fun point(nullable: Boolean) = JSONObject()
            .put("type", if (nullable) JSONArray(listOf("array", "null")) else "array")
            .put("minItems", 2).put("maxItems", 2)
            .put("items", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1))
        val properties = JSONObject()
            .put("readable", JSONObject().put("type", "boolean"))
            .put("reason", enumeration("NONE", "AMBIGUOUS_HANDS", "SECONDS_NOT_VISIBLE", "LANDMARKS_NOT_VISIBLE", "UNSUPPORTED_LAYOUT"))
            .put("layout", enumeration("CLASSIC", "GMT", "SMALL_SECONDS", "CHRONOGRAPH", "REGULATOR", "JUMP_HOUR", "UNKNOWN"))
            .put("hour", integer(11)).put("minute", integer(59)).put("second", integer(59))
            .put("center", point(true)).put("hourTip", point(true)).put("minuteTip", point(true)).put("secondTip", point(true))
            .put("markers", JSONObject().put("type", JSONArray(listOf("array", "null")))
                .put("minItems", 4).put("maxItems", 4).put("items", point(false)))
        return JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            .put("required", JSONArray(listOf("readable", "reason", "layout", "hour", "minute", "second", "center", "hourTip", "minuteTip", "secondTip", "markers")))
    }

    private fun request(model: String, imageBytes: ByteArray, apiKey: String, legacySchema: Boolean): CloudReadResult {
        val body = requestBody(Base64.encodeToString(imageBytes, Base64.NO_WRAP), legacySchema)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ENDPOINT_PREFIX + model + ":generateContent").openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                httpError(code, errorBody)
            } else {
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                parseResponse(response, model)
            }
        } catch (_: java.net.SocketTimeoutException) {
            CloudReadResult(null, "Gemini: vypršal čas čakania. Použite lokálny návrh alebo ručné odčítanie.")
        } catch (_: java.io.IOException) {
            CloudReadResult(null, "Gemini: pripojenie sa nepodarilo. Použite lokálny návrh alebo ručné odčítanie.")
        } catch (_: Exception) {
            CloudReadResult(null, "Gemini: odpoveď sa nepodarilo spracovať.")
        } finally { connection?.disconnect() }
    }

    private fun httpError(code: Int, body: String): CloudReadResult {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val status = error?.optString("status").orEmpty()
        val message = error?.optString("message").orEmpty().lowercase()
        // Never display the raw server body: it may contain project details.
        val text = when {
            code == 400 && "api key" in message && ("invalid" in message || "not valid" in message) ->
                "Gemini HTTP 400: API kľúč je neplatný. Skontrolujte ho v nastaveniach."
            code == 400 && status == "INVALID_ARGUMENT" ->
                "Gemini HTTP 400: model odmietol formát požiadavky."
            code == 400 ->
                "Gemini HTTP 400: služba odmietla požiadavku."
            code == 401 || code == 403 ->
                "Gemini HTTP $code: skontrolujte API kľúč a oprávnenia projektu."
            code == 404 ->
                "Gemini HTTP 404: model nie je pre tento projekt dostupný."
            code == 429 ->
                "Gemini HTTP 429: prekročený limit požiadaviek alebo kvóta."
            code == 503 ->
                "Gemini HTTP 503: služba je dočasne preťažená."
            else -> "Gemini HTTP $code"
        }
        return CloudReadResult(null, text, code)
    }

    internal fun parseResponse(response: JSONObject, model: String): CloudReadResult {
        if (response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotBlank())
            return CloudReadResult(null, "Gemini zablokovalo spracovanie fotografie.")
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: return CloudReadResult(null, "Gemini neposlalo žiadny návrh odčítania.")
        val finish = candidate.optString("finishReason")
        if (finish == "MAX_TOKENS") return CloudReadResult(null, "Odpoveď Gemini bola prerušená pre limit dĺžky.")
        if (finish.isNotBlank() && finish != "STOP") return CloudReadResult(null, "Gemini nedokončilo návrh odčítania.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text", ""))
            }
        }
        if (text.isBlank()) return CloudReadResult(null, "Gemini poslalo prázdny návrh odčítania.")
        return parse(text, model)
    }

    internal fun parse(text: String, model: String): CloudReadResult {
        var field = "formát JSON"
        return try {
            val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            field = "typ ciferníka"
            val layout = json.optString("layout")
            val reason = json.optString("reason")
            val unsupported = layout in setOf("GMT", "SMALL_SECONDS", "CHRONOGRAPH", "REGULATOR", "JUMP_HOUR")
            if (unsupported || reason == "UNSUPPORTED_LAYOUT") {
                CloudReadResult(null, "Gemini označilo ciferník ako nepodporovaný typ (${if (unsupported) layout else "neurčený"}). Automatická kontrola podporuje tri centrálne ručičky; typ overte ručne.")
            } else if (json.opt("readable") == false) {
                CloudReadResult(null, when (reason) {
                    "SECONDS_NOT_VISIBLE" -> "Gemini nerozpoznalo sekundovú ručičku. Sekundy odčítajte ručne."
                    "LANDMARKS_NOT_VISIBLE" -> "Gemini nerozpoznalo orientačné body 12, 3, 6 a 9. Skontrolujte, či je viditeľný celý ciferník."
                    "AMBIGUOUS_HANDS" -> "Gemini nedokázalo rozlíšiť ručičky a ich protizávažia. Odčítajte čas ručne."
                    else -> "Gemini odmietlo automatické odčítanie bez konkrétneho dôvodu. Odčítajte čas ručne."
                })
            } else {
                require(layout == "CLASSIC")
                field = "príznak čitateľnosti"
                require(json.opt("readable") == true)
                field = "dôvod odčítania"
                require(reason == "NONE")
                fun integer(name: String, range: IntRange): Int {
                    field = when (name) { "hour" -> "hodiny"; "minute" -> "minúty"; else -> "sekundy" }
                    val number = json.get(name) as? Number ?: error("Missing integer")
                    val n = number.toDouble()
                    require(n.isFinite() && n == number.toInt().toDouble() && number.toInt() in range)
                    return number.toInt()
                }
                fun point(a: JSONArray): DialPoint {
                    require(a.length() == 2)
                    val x = a.get(0) as? Number ?: error("Missing coordinate")
                    val y = a.get(1) as? Number ?: error("Missing coordinate")
                    return DialPoint(x.toDouble(), y.toDouble()).also { require(it.valid()) }
                }
                val h = integer("hour", 0..11); val m = integer("minute", 0..59); val s = integer("second", 0..59)
                fun namedPoint(key: String, label: String): DialPoint {
                    field = label
                    return point(json.getJSONArray(key))
                }
                val center = namedPoint("center", "stred osi")
                val hourTip = namedPoint("hourTip", "koniec hodinovej ručičky")
                val minuteTip = namedPoint("minuteTip", "koniec minútovej ručičky")
                val secondTip = namedPoint("secondTip", "koniec sekundovej ručičky")
                field = "orientačné body ciferníka"
                val markers = json.getJSONArray("markers")
                require(markers.length() == 4)
                val geometry = DialGeometry(center, hourTip, minuteTip, secondTip, (0..3).map { point(markers.getJSONArray(it)) })
                if (geometry.matches(h, m, s)) CloudReadResult(CloudReading(h, m, s, geometry, model))
                else CloudReadResult(null, "Návrh Gemini nesúhlasí s označenými ručičkami. Skontrolujte ciferník ručne.")
            }
        } catch (_: Exception) {
            // 'field' is always our fixed label, never model content or exception text.
            CloudReadResult(null, "Gemini: chýbajúci alebo neplatný údaj – $field. Skontrolujte ciferník ručne.")
        }
    }
}
