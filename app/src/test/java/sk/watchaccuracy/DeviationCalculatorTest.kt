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

    @Test fun calculatesUnderOneDayFromDisplayedWholeSeconds() {
        // 15 Sep 20:31:36 -> 16 Sep 18:08:38 = 21:37:02.
        // The dial advanced by 21:37:13, so it gained 11 seconds.
        val start = measurement(0, 20, 31, 21)
        val end = measurement(77_822_000, 18, 8, 34)
        assertEquals(12.21248, DeviationCalculator.secondsPerDay(start, end)!!, 0.00001)
    }

    @Test fun ignoresHiddenPhotoMilliseconds() {
        val start = measurement(529, 20, 31, 21)
        val end = measurement(77_822_000, 18, 8, 34)
        assertEquals(12.21248, DeviationCalculator.secondsPerDay(start, end)!!, 0.00001)
    }

    @Test fun alwaysUsesChronologicallyPreviousMeasurement() {
        val first = measurement(0, 10, 0, 0)
        val second = measurement(86_400_000, 10, 0, 3)
        val third = measurement(172_800_000, 10, 0, 11)
        val shuffled = listOf(third, first, second)
        assertEquals(second.id, DeviationCalculator.previousMeasurement(shuffled, third)?.id)
        assertEquals(8.0, DeviationCalculator.latestSecondsPerDay(shuffled)!!, 0.001)
    }
}
