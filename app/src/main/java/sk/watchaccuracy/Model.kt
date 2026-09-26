package sk.watchaccuracy

import java.util.UUID

enum class DialShape { ROUND, SQUARE, RECTANGLE }
enum class DialLayout { CLASSIC, GMT, SMALL_SECONDS, CHRONOGRAPH, REGULATOR, JUMP_HOUR }
enum class AppPalette { CLASSIC, BLUE, MONO }
enum class ShutterPosition { LEFT, CENTER, RIGHT }

data class Watch(
    val id: String = UUID.randomUUID().toString(),
    val brand: String,
    val model: String,
    val measurements: List<Measurement> = emptyList(),
    val learning: WatchLearning = WatchLearning()
)

const val WATCH_LEARNING_VERSION = 3

data class WatchLearning(
    val samples: Int = 0,
    val hourOffset: Float = 0f,
    val minuteOffset: Float = 0f,
    val secondOffset: Float = 0f,
    val preferredLayout: DialLayout? = null,
    /** Version of the profile algorithm used for this watch. Older profiles are replayed. */
    val replayVersion: Int = WATCH_LEARNING_VERSION
) {
    fun learn(read: ReadTime, hour: Int, minute: Int, second: Int, layout: DialLayout): WatchLearning {
        if (replayVersion < WATCH_LEARNING_VERSION) return WatchLearning().learn(read, hour, minute, second, layout)
        // A missing photo or an unreadable image must not count as a training sample.
        if (read.hourImageAngle < 0 && read.minuteImageAngle < 0 && read.secondImageAngle < 0) {
            return copy(preferredLayout = layout, replayVersion = WATCH_LEARNING_VERSION)
        }
        val alpha = if (samples == 0) 1f else .35f
        fun blend(old: Float, detected: Int, expected: Float): Float {
            if (detected < 0) return old
            val raw = ((expected - detected + 540f) % 360f) - 180f
            return old + (raw.coerceIn(-30f, 30f) - old) * alpha
        }
        val expectedHour = (hour % 12) * 30f + minute * .5f
        return copy(samples = samples + 1,
            hourOffset = blend(hourOffset, read.hourImageAngle, expectedHour),
            minuteOffset = blend(minuteOffset, read.minuteImageAngle, minute * 6f),
            secondOffset = if (layout == DialLayout.SMALL_SECONDS || layout == DialLayout.CHRONOGRAPH) secondOffset else blend(secondOffset, read.secondImageAngle, second * 6f),
            preferredLayout = layout,
            replayVersion = WATCH_LEARNING_VERSION)
    }
}

data class Measurement(
    val id: String = UUID.randomUUID().toString(),
    val capturedAtMillis: Long,
    val dialHour: Int,
    val dialMinute: Int,
    val dialSecond: Int,
    val photoPath: String,
    val shape: DialShape,
    val layout: DialLayout = DialLayout.CLASSIC
) {
    val dialSecondsOfDay: Int get() = (dialHour % 24) * 3600 + dialMinute * 60 + dialSecond
}

object DeviationCalculator {
    fun previousMeasurement(measurements: List<Measurement>, current: Measurement): Measurement? {
        val ordered = measurements.sortedBy { it.capturedAtMillis }
        val index = ordered.indexOfFirst { it.id == current.id }
        return if (index > 0) ordered[index - 1] else null
    }

    fun latestSecondsPerDay(measurements: List<Measurement>): Double? {
        val ordered = measurements.sortedBy { it.capturedAtMillis }
        if (ordered.size < 2) return null
        return secondsPerDay(ordered[ordered.lastIndex - 1], ordered.last())
    }

    /** Result in seconds gained (+) or lost (-) per 24 hours. */
    fun secondsPerDay(previous: Measurement, current: Measurement): Double? {
        // Dial readings are stored only to whole seconds. Use the same
        // precision for photo timestamps so hidden milliseconds do not distort
        // a daily rate extrapolated from an interval shorter than 24 hours.
        val previousPhotoSecond = previous.capturedAtMillis / 1_000L
        val currentPhotoSecond = current.capturedAtMillis / 1_000L
        val realElapsedSeconds = (currentPhotoSecond - previousPhotoSecond).toDouble()
        if (realElapsedSeconds <= 0.0) return null
        var dialElapsed = (current.dialSecondsOfDay - previous.dialSecondsOfDay).toDouble()
        val expectedDays = realElapsedSeconds / 86_400.0
        val dayTurns = kotlin.math.round(expectedDays).toInt()
        dialElapsed += dayTurns * 86_400.0
        while (dialElapsed - realElapsedSeconds > 43_200) dialElapsed -= 86_400
        while (dialElapsed - realElapsedSeconds < -43_200) dialElapsed += 86_400
        return (dialElapsed - realElapsedSeconds) / expectedDays
    }
}
