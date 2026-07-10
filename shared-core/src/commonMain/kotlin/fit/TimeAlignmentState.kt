package fit

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

class TimeAlignmentState(initialMillis: Int) {
    companion object {
        const val MAX_OFFSET_MILLIS = 43200000 // 12 hours
        const val MAX_OFFSET_SECONDS = MAX_OFFSET_MILLIS / 1000f
    }

    var millis: Int = initialMillis.coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS)
        private set

    val seconds: Float get() = millis / 1000f

    fun update(newMillis: Int) {
        millis = newMillis.coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS)
    }

    fun adjust(videoStartUtc: String): String {
        if (videoStartUtc.isEmpty()) return ""
        return try {
            val baseInstant = Instant.parse(videoStartUtc)
            val adjusted = baseInstant.plus(millis.milliseconds)
            adjusted.toString()
        } catch (e: Exception) {
            videoStartUtc
        }
    }

    fun updateTimeComponents(hour: Int, minute: Int, second: Int, baseUtcStr: String) {
        if (baseUtcStr.isEmpty()) return
        try {
            val baseInstant = Instant.parse(baseUtcStr)
            val tokyoOffsetMillis = 9L * 60L * 60L * 1000L
            val baseTokyoInstant = Instant.fromEpochMilliseconds(baseInstant.toEpochMilliseconds() + tokyoOffsetMillis)
            val baseDateTime = baseTokyoInstant.toLocalDateTime(TimeZone.UTC)
            val newDateTime = kotlinx.datetime.LocalDateTime(
                year = baseDateTime.year,
                monthNumber = baseDateTime.monthNumber,
                dayOfMonth = baseDateTime.dayOfMonth,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                second = second.coerceIn(0, 59),
                nanosecond = 0
            )
            val newTokyoInstant = newDateTime.toInstant(TimeZone.UTC)
            val diffMs = newTokyoInstant.toEpochMilliseconds() - baseTokyoInstant.toEpochMilliseconds()
            update(diffMs.toInt())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
