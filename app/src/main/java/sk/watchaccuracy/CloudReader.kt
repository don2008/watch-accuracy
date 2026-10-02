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
    val geometry: DialGeometry, val model: String,
    val layout: DialLayout = DialLayout.CLASSIC
) {
    fun asReadTime(referenceMillis: Long): ReadTime {
        val reference = Calendar.getInstance().apply { timeInMillis = referenceMillis }
        val secondsOfDay = reference.get(Calendar.HOUR_OF_DAY) * 3600 + reference.get(Calendar.MINUTE) * 60 + reference.get(Calendar.SECOND)
        return ReadTime(
            nearestDialHour(hour, minute, second, secondsOfDay), minute, second,
            layout = layout, layoutConfidence = 1f,
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
    private const val MODEL_LIST_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"

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
        val discovered = discoverModels(key)
        val models = if (discovered.isNotEmpty()) discovered.take(6) else
            listOf(PRIMARY_MODEL, FALLBACK_MODEL, COMPATIBILITY_MODEL)
        val attempts = models.distinct().flatMap { model ->
            // First use the documented JSON schema mode. Some older models only
            // accept JSON MIME type without a schema, so retain that safe fallback.
            listOf(model to false, model to true)
        }
        var last = CloudReadResult(null, "Gemini: požiadavku sa nepodarilo dokončiť.")
        for ((model, schemaFree) in attempts) {
            val result = request(model, bytes, key, schemaFree)
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

    internal fun requestBody(base64Image: String, schemaFree: Boolean = false): JSONObject {
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
            secondsCenter: [x,y], markers: [[x12,y12],[x3,y3],[x6,y6],[x9,y9]].
            CLASSIC means hour, minute and running seconds share one pivot. GMT also uses
            the main pivot: read the ordinary local hour hand, not the 24-hour GMT hand.
            For SMALL_SECONDS and CHRONOGRAPH, secondsCenter MUST be the pivot of the
            continuously running seconds subdial and secondTip its seconds hand. On a
            chronograph, the long central chronograph hand and the elapsed-minute/hour
            subdials are NOT the current seconds, even when the central hand is stopped at 12.
            For CLASSIC and GMT set secondsCenter equal to center.
            A date window, a power-reserve arc with its own pointer, or a Spring Drive
            movement does not change the layout. In particular, a Grand Seiko with central
            seconds plus a power-reserve gauge is CLASSIC, not SMALL_SECONDS or REGULATOR.
            Cardinal points lie on the main minute track: a date window replacing the numeral
            3 does not hide the 15-minute track position if its ticks remain visible.
            Automatic geometry supports CLASSIC, GMT, SMALL_SECONDS and CHRONOGRAPH.
            For REGULATOR, JUMP_HOUR, hidden or ambiguous hands, unreadable running seconds,
            or missing cardinal landmarks set readable=false, choose the specific reason,
            and use null for every time/coordinate field instead of guessing.
            Include all schema fields. If readable=true use reason=NONE.
            Return only JSON. Do not invent confidence percentages.
        """.trimIndent()
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(
            JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", base64Image))
        )
        val generationConfig = JSONObject()
            .put("temperature", 0)
            .put("responseMimeType", "application/json")
        if (!schemaFree) {
            // This is the documented generateContent JSON-mode dialect.
            generationConfig.put("responseSchema", responseSchema())
        }
        // In schema-free compatibility mode the model is still constrained to
        // JSON MIME type and parse() strictly validates every returned field.
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
            .put("secondsCenter", point(true))
            .put("markers", JSONObject().put("type", JSONArray(listOf("array", "null")))
                .put("minItems", 4).put("maxItems", 4).put("items", point(false)))
        return JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            .put("required", JSONArray(listOf("readable", "reason", "layout", "hour", "minute", "second", "center", "hourTip", "minuteTip", "secondTip", "secondsCenter", "markers")))
    }

    private fun discoverModels(apiKey: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(MODEL_LIST_ENDPOINT).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"; connectTimeout = 12_000; readTimeout = 15_000
                setRequestProperty("x-goog-api-key", apiKey)
            }
            if (connection.responseCode !in 200..299) emptyList()
            else selectModels(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    internal fun selectModels(response: JSONObject): List<String> {
        val models = response.optJSONArray("models") ?: return emptyList()
        val candidates = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            val rawName = item.optString("name")
            val name = rawName.removePrefix("models/")
            if (!name.startsWith("gemini-", ignoreCase = true)) continue
            val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
            val supportsGenerateContent = (0 until methods.length())
                .any { methods.optString(it) == "generateContent" }
            if (!supportsGenerateContent) continue
            val lower = name.lowercase()
            if (listOf("embedding", "-image", "imagen", "veo", "tts", "live", "audio",
                    "robotics", "computer-use", "deep-research").any { it in lower }) continue
            candidates += name
        }
        val preferred = listOf(
            "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3-flash-preview",
            "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash"
        )
        return candidates.distinct().sortedWith(
            compareBy<String> {
                val exact = preferred.indexOf(it)
                when {
                    exact >= 0 -> exact
                    "flash" in it.lowercase() -> 100
                    "pro" in it.lowercase() -> 200
                    else -> 300
                }
            }.thenBy { if ("preview" in it.lowercase()) 1 else 0 }
                .thenBy { it }
        )
    }

    private fun request(model: String, imageBytes: ByteArray, apiKey: String, schemaFree: Boolean): CloudReadResult {
        val body = requestBody(Base64.encodeToString(imageBytes, Base64.NO_WRAP), schemaFree)
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
            val unsupported = layout in setOf("REGULATOR", "JUMP_HOUR")
            val supported = layout in setOf("CLASSIC", "GMT", "SMALL_SECONDS", "CHRONOGRAPH")
            if (unsupported || reason == "UNSUPPORTED_LAYOUT") {
                CloudReadResult(null, "Gemini označilo ciferník ako nepodporovaný typ (${if (unsupported) layout else "neurčený"}). Typ overte ručne.")
            } else if (json.opt("readable") == false) {
                CloudReadResult(null, when (reason) {
                    "SECONDS_NOT_VISIBLE" -> "Gemini nerozpoznalo sekundovú ručičku. Sekundy odčítajte ručne."
                    "LANDMARKS_NOT_VISIBLE" -> "Gemini nerozpoznalo orientačné body 12, 3, 6 a 9. Skontrolujte, či je viditeľný celý ciferník."
                    "AMBIGUOUS_HANDS" -> "Gemini nedokázalo rozlíšiť ručičky a ich protizávažia. Odčítajte čas ručne."
                    else -> "Gemini odmietlo automatické odčítanie bez konkrétneho dôvodu. Odčítajte čas ručne."
                })
            } else {
                require(supported)
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
                val secondsCenter = namedPoint("secondsCenter", "stred sekundového subciferníka")
                field = "orientačné body ciferníka"
                val markers = json.getJSONArray("markers")
                require(markers.length() == 4)
                val parsedLayout = DialLayout.valueOf(layout)
                val geometry = DialGeometry(
                    center, hourTip, minuteTip, secondTip,
                    secondCenter = secondsCenter.takeIf { parsedLayout == DialLayout.SMALL_SECONDS || parsedLayout == DialLayout.CHRONOGRAPH },
                    markers = (0..3).map { point(markers.getJSONArray(it)) }
                )
                if (geometry.matches(h, m, s)) CloudReadResult(CloudReading(h, m, s, geometry, model, parsedLayout))
                else CloudReadResult(null, "Návrh Gemini nesúhlasí s označenými ručičkami. Skontrolujte ciferník ručne.")
            }
        } catch (_: Exception) {
            // 'field' is always our fixed label, never model content or exception text.
            CloudReadResult(null, "Gemini: chýbajúci alebo neplatný údaj – $field. Skontrolujte ciferník ručne.")
        }
    }
}
