package sk.watchaccuracy

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviationCalculatorTest {
    private fun measurement(at: Long, h: Int, m: Int, s: Int) = Measurement(capturedAtMillis = at, dialHour = h, dialMinute = m, dialSecond = s, photoPath = "", shape = DialShape.ROUND)

    @Test fun gainsFiveSecondsPerDay() {
        val start = measurement(0, 10, 0, 0)
        val end = measurement(86_400_000, 10, 0, 5)
        assertEquals(5.0, DeviationCalculator.secondsPerDay(start, end)!!, 0.001)
    }

    @Test fun handlesMidnight() {
        val start = measurement(0, 23, 0, 0)
        val end = measurement(7_200_000, 1, 0, 1)
        assertEquals(12.0, DeviationCalculator.secondsPerDay(start, end)!!, 0.001)
    }
}
