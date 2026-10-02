package sk.watchaccuracy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CloudReaderTest {
    private fun point(angle: Double, radius: Double) = JSONArray(listOf(.5 + sin(angle * PI / 180) * radius, .5 - cos(angle * PI / 180) * radius))
    private fun reply(): JSONObject = JSONObject().put("readable", true).put("reason", "NONE").put("layout", "CLASSIC")
        .put("hour", 5).put("minute", 18).put("second", 21).put("center", JSONArray(listOf(.5, .5)))
        .put("hourTip", point(159.175, .22)).put("minuteTip", point(110.1, .35)).put("secondTip", point(126.0, .38))
        .put("markers", JSONArray().put(point(0.0, .43)).put(point(90.0, .43)).put(point(180.0, .43)).put(point(270.0, .43)))

    @Test fun acceptsConsistentGeometryAndRetainsActualModel() {
        val result = CloudReader.parse(reply().toString(), "test-fallback")
        assertNotNull(result.reading)
        assertEquals("test-fallback", result.reading!!.model)
        assertEquals(18, result.reading!!.minute)
    }
    @Test fun rejectsOldHighConfidenceResponseWithoutGeometry() {
        val result = CloudReader.parse("""{"hour":10,"minute":8,"second":32,"confidence":0.99}""", "test")
        assertNull(result.reading)
        assertNotNull(result.error)
    }
    @Test fun rejectsOutOfRangeAndFractionalTimesInsteadOfClamping() {
        for (value in listOf(60, -1, 12.5, "18")) {
            assertNull(CloudReader.parse(reply().put("minute", value).toString(), "test").reading)
        }
    }
    @Test fun rejectsDisagreementUnsupportedLayoutAndExplicitAbstention() {
        assertNull(CloudReader.parse(reply().put("hour", 10).toString(), "test").reading)
        assertNull(CloudReader.parse(reply().put("layout", "REGULATOR").toString(), "test").reading)
        assertNull(CloudReader.parse(reply().put("readable", false).toString(), "test").reading)
        assertNull(CloudReader.parse("{}", "test").reading)
        assertNull(CloudReader.parse("not JSON", "test").reading)
    }
    @Test fun reportsRefusalReasonsWithoutCallingEveryFailureUnrecognisedHands() {
        fun error(reason: String) = CloudReader.parse(
            JSONObject().put("readable", false).put("layout", "CLASSIC").put("reason", reason).toString(), "test"
        ).error.orEmpty()
        assertTrue(error("SECONDS_NOT_VISIBLE").contains("sekundovú"))
        assertTrue(error("LANDMARKS_NOT_VISIBLE").contains("orientačné body"))
        assertTrue(error("AMBIGUOUS_HANDS").contains("protizávažia"))
        assertTrue(error("unknown").contains("bez konkrétneho dôvodu"))
        val layoutError = CloudReader.parse(reply().put("layout", "SMALL_SECONDS").toString(), "test")
        assertTrue(layoutError.error.orEmpty().contains("nepodporovaný typ"))
    }

    private fun envelope(text: String, finish: String = "STOP") = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("finishReason", finish).put("content", JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("thought", true).put("text", "private reasoning, not JSON"))
            .put(JSONObject().put("text", text))))
    ))
    @Test fun requestEnforcesTypesAndBoundsThroughApiSchema() {
        val config = CloudReader.requestBody("test-image").getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertFalse(config.has("responseFormat"))
        val schema = config.getJSONObject("responseSchema")
        val properties = schema.getJSONObject("properties")
        assertEquals(11, properties.getJSONObject("hour").getInt("maximum"))
        assertEquals(59, properties.getJSONObject("second").getInt("maximum"))
        assertEquals(2, properties.getJSONObject("center").getInt("minItems"))
        assertEquals(4, properties.getJSONObject("markers").getInt("minItems"))
        val required = schema.getJSONArray("required")
        assertTrue((0 until required.length()).any { required.getString(it) == "center" })
        assertFalse(schema.getBoolean("additionalProperties"))
        val schemaFreeConfig = CloudReader.requestBody("test-image", true).getJSONObject("generationConfig")
        assertEquals("application/json", schemaFreeConfig.getString("responseMimeType"))
        assertFalse(schemaFreeConfig.has("responseJsonSchema"))
        assertFalse(schemaFreeConfig.has("responseSchema"))
        assertFalse(schemaFreeConfig.has("responseFormat"))
    }

    @Test fun selectsOnlyAvailableGenerateContentModelsAndPrefersFlash() {
        fun model(name: String, vararg methods: String) = JSONObject()
            .put("name", name)
            .put("supportedGenerationMethods", JSONArray(methods.toList()))
        val response = JSONObject().put("models", JSONArray()
            .put(model("models/text-embedding-004", "embedContent"))
            .put(model("models/gemini-custom-pro", "generateContent"))
            .put(model("models/gemini-custom-flash", "generateContent"))
            .put(model("models/gemini-image-test", "generateContent"))
            .put(model("models/gemini-no-generate", "countTokens")))
        val selected = CloudReader.selectModels(response)
        assertEquals(listOf("gemini-custom-flash", "gemini-custom-pro"), selected)
    }
    @Test fun handlesFullApiEnvelopeAndDoesNotReadThinkingAsOutput() {
        val result = CloudReader.parseResponse(envelope(reply().toString()), "test")
        assertNotNull(result.reading)
        assertEquals(18, result.reading!!.minute)
    }
    @Test fun distinguishesTruncationEmptyResponsesAndMissingFields() {
        assertTrue(CloudReader.parseResponse(envelope(reply().toString(), "MAX_TOKENS"), "test").error.orEmpty().contains("limit dĺžky"))
        assertNull(CloudReader.parseResponse(envelope(reply().toString(), "MAX_TOKENS"), "test").reading)
        assertTrue(CloudReader.parseResponse(JSONObject(), "test").error.orEmpty().contains("žiadny návrh"))
        assertTrue(CloudReader.parseResponse(envelope(""), "test").error.orEmpty().contains("prázdny"))
        val incomplete = reply().apply { remove("center") }
        assertTrue(CloudReader.parse(incomplete.toString(), "test").error.orEmpty().contains("stred osi"))
        assertTrue(CloudReader.parse(reply().put("hour", 17).toString(), "test").error.orEmpty().contains("hodiny"))
    }
    @Test fun acceptsExplicitAbstentionWithNullCoordinates() {
        val refusal = reply().put("readable", false).put("reason", "SECONDS_NOT_VISIBLE")
        for (key in listOf("hour", "minute", "second", "center", "hourTip", "minuteTip", "secondTip", "markers")) refusal.put(key, JSONObject.NULL)
        val result = CloudReader.parseResponse(envelope(refusal.toString()), "test")
        assertNull(result.reading)
        assertTrue(result.error.orEmpty().contains("sekundovú"))
    }

}
