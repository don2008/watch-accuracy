package sk.watchaccuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLearningTest {
    @Test fun learnsFromRawDetectorAngles() {
        val raw = ReadTime(
            hour = 10, minute = 20, second = 0,
            hourImageAngle = 303, minuteImageAngle = 117, secondImageAngle = 0
        )
        val learned = WatchLearning().learn(raw, hour = 10, minute = 19, second = 58, layout = DialLayout.CLASSIC)
        // Expected angles are 309.5° and 114°. The profile stores the residual,
        // not a replacement time, and clamps only pathological detector errors.
        assertEquals(6.5f, learned.hourOffset, .001f)
        assertEquals(-3f, learned.minuteOffset, .001f)
        assertEquals(1, learned.samples)
    }

    @Test fun secondAndLaterSamplesBlendInsteadOfResetting() {
        val first = ReadTime(hour = 0, minute = 0, second = 0, hourImageAngle = 0, minuteImageAngle = 0, secondImageAngle = 0)
        val profile = WatchLearning().learn(first, 1, 0, 0, DialLayout.CLASSIC)
        val second = ReadTime(hour = 0, minute = 0, second = 0, hourImageAngle = 20, minuteImageAngle = 0, secondImageAngle = 0)
        val updated = profile.learn(second, 2, 0, 0, DialLayout.CLASSIC)
        assertEquals(2, updated.samples)
        // The profile remains bounded and is blended rather than replaced by
        // an unconstrained detector error.
        assertTrue(updated.hourOffset < 60f)
    }
    @Test fun oldCentreCorrectionsAreNotAppliedToNewDetector() {
        val raw = ReadTime(10, 20, 30, hourImageAngle = 310, minuteImageAngle = 120, secondImageAngle = 180)
        val old = WatchLearning(samples = 25, minuteOffset = 30f, secondOffset = -30f, replayVersion = 2)
        assertEquals(raw, ClockReader.applyProfile(raw, old))
        val rebuilt = old.learn(raw, 10, 20, 30, DialLayout.CLASSIC)
        assertEquals(1, rebuilt.samples)
        assertEquals(0f, rebuilt.minuteOffset, .001f)
        assertEquals(WATCH_LEARNING_VERSION, rebuilt.replayVersion)
    }

}
