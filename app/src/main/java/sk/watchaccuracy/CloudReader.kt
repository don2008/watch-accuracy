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
        val first = request(PRIMARY_MODEL, bytes, apiKey.trim())
        if (first.httpCode != 503) return first
        Thread.sleep(900)
        val retry = request(PRIMARY_MODEL, bytes, apiKey.trim())
        if (retry.httpCode != 503) return retry
        // Preserve the actual fallback error (e.g. a quota error), not the first 503.
        return request(FALLBACK_MODEL, bytes, apiKey.trim())
    }

    private fun request(model: String, imageBytes: ByteArray, apiKey: String): CloudReadResult {
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
            cardinal landmarks set readable=false, choose the specific reason, and omit
            coordinates/time instead of guessing. If readable=true use reason=NONE.
            Return only JSON. Do not invent confidence percentages.
        """.trimIndent()
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(
            JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP)))
        )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
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
                // Do not echo arbitrary server messages or keys into the UI.
                CloudReadResult(null, when (code) {
                    400, 401, 403 -> "Gemini HTTP $code: skontrolujte API kľúč a oprávnenia projektu."
                    404 -> "Gemini HTTP 404: model nie je pre tento projekt dostupný."
                    429 -> "Gemini HTTP 429: prekročený limit požiadaviek alebo kvóta."
                    503 -> "Gemini HTTP 503: služba je dočasne preťažená."
                    else -> "Gemini HTTP $code"
                }, code)
            } else {
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val responseParts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString {
                    if (responseParts != null) for (i in 0 until responseParts.length()) {
                        val part = responseParts.optJSONObject(i) ?: continue
                        if (!part.optBoolean("thought", false)) append(part.optString("text", ""))
                    }
                }
                parse(text, model)
            }
        } catch (_: java.net.SocketTimeoutException) {
            CloudReadResult(null, "Gemini: vypršal čas čakania. Použite lokálny návrh alebo ručné odčítanie.")
        } catch (_: java.io.IOException) {
            CloudReadResult(null, "Gemini: pripojenie sa nepodarilo. Použite lokálny návrh alebo ručné odčítanie.")
        } catch (_: Exception) {
            CloudReadResult(null, "Gemini: odpoveď sa nepodarilo spracovať.")
        } finally { connection?.disconnect() }
    }

    internal fun parse(text: String, model: String): CloudReadResult = try {
        val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
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
            require(json.opt("readable") == true && layout == "CLASSIC")
            fun integer(name: String, range: IntRange): Int {
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
            val markers = json.getJSONArray("markers")
            require(markers.length() == 4)
            val geometry = DialGeometry(point(json.getJSONArray("center")), point(json.getJSONArray("hourTip")),
                point(json.getJSONArray("minuteTip")), point(json.getJSONArray("secondTip")),
                (0..3).map { point(markers.getJSONArray(it)) })
            if (geometry.matches(h, m, s)) CloudReadResult(CloudReading(h, m, s, geometry, model))
            else CloudReadResult(null, "Návrh Gemini nesúhlasí s označenými ručičkami. Skontrolujte ciferník ručne.")
        }
    } catch (_: Exception) {
        CloudReadResult(null, "Gemini vrátilo neúplný alebo neplatný návrh. Skontrolujte ciferník ručne.")
    }
}
