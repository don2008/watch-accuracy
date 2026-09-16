package sk.watchaccuracy

import java.util.UUID

enum class DialShape { ROUND, SQUARE, RECTANGLE }
enum class DialLayout { CLASSIC, GMT, SMALL_SECONDS, REGULATOR, JUMP_HOUR }
enum class AppPalette { CLASSIC, BLUE, MONO }
enum class ShutterPosition { LEFT, CENTER, RIGHT }

data class Watch(
    val id: String = UUID.randomUUID().toString(),
    val brand: String,
    val model: String,
    val measurements: List<Measurement> = emptyList()
)

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
