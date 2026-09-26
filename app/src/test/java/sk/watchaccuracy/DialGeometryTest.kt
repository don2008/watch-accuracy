package sk.watchaccuracy

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DialGeometryTest {
    private fun tip(angle: Double, length: Double) = DialPoint(sin(angle * PI / 180) * length, -cos(angle * PI / 180) * length)
    private fun image(p: DialPoint): DialPoint {
        // A tilted and translated dial with projective foreshortening.
        val w = 1 + .12 * p.x + .08 * p.y
        return DialPoint((.32 * p.x - .09 * p.y + .52) / w, (.13 * p.x + .24 * p.y + .48) / w)
    }
    private fun geometry(h: Int, m: Int, s: Int): DialGeometry = DialGeometry(
        image(DialPoint(0.0, 0.0)), image(tip(h * 30.0 + m * .5 + s / 120.0, .5)),
        image(tip(m * 6.0 + s * .1, .85)), image(tip(s * 6.0, .9)),
        listOf(0.0, 90.0, 180.0, 270.0).map { image(tip(it, 1.0)) }
    )

    @Test fun verifiesHandsDespiteTranslationRotationAndPerspective() {
        val g = geometry(5, 18, 21)
        assertTrue(g.matches(5, 18, 21))
        assertEquals(126.0, g.projection()!!.angle(g.center, g.secondTip)!!, .00001)
    }
    @Test fun rejectsPlausibleButUnrelatedCloudTime() {
        assertFalse(geometry(5, 18, 21).matches(10, 8, 32))
    }
    @Test fun rejectsLogoAsPivotAndCounterweightAsTip() {
        val g = geometry(5, 18, 21)
        assertFalse(g.copy(center = image(DialPoint(0.0, -.35))).matches(5, 18, 21))
        assertFalse(g.copy(secondTip = image(tip(306.0, .4))).matches(5, 18, 21))
    }
    @Test fun rejectsMissingInvalidAndDegenerateCoordinates() {
        val g = geometry(5, 18, 21)
        assertFalse(g.copy(markers = emptyList()).matches(5, 18, 21))
        assertFalse(g.copy(markers = List(4) { g.center }).matches(5, 18, 21))
        assertFalse(g.copy(hourTip = DialPoint(Double.NaN, .3)).matches(5, 18, 21))
        assertFalse(g.copy(secondTip = g.center).matches(5, 18, 21))
        assertFalse(g.copy(markers = g.markers.reversed()).matches(5, 18, 21))
    }
    @Test fun handlesTwelveOclockBoundaryAndInvalidTimes() {
        val g = geometry(11, 59, 59)
        assertTrue(g.matches(11, 59, 59))
        assertFalse(g.matches(24, 59, 59))
        assertFalse(g.matches(11, 60, 59))
        assertFalse(g.matches(11, 59, -1))
    }
    @Test fun selectsHalfDayAcrossMidnightAndNoonWithoutChangingMinutes() {
        assertEquals(23, nearestDialHour(11, 59, 50, 10))
        assertEquals(0, nearestDialHour(0, 0, 10, 86390))
        assertEquals(12, nearestDialHour(0, 0, 10, 43190))
        assertEquals(17, nearestDialHour(5, 18, 21, 17 * 3600 + 18 * 60))
    }
}
