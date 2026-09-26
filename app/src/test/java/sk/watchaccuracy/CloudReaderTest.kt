package sk.watchaccuracy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CloudReaderTest {
    private fun point(angle: Double, radius: Double) = JSONArray(listOf(.5 + sin(angle * PI / 180) * radius, .5 - cos(angle * PI / 180) * radius))
    private fun reply(): JSONObject = JSONObject().put("readable", true).put("layout", "CLASSIC")
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
}
