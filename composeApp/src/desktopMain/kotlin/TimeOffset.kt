import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

class TimeAlignmentState(initialMillis: Int) {
    companion object {
        const val MAX_OFFSET_MILLIS = 600000 // 10 minutes
        const val MAX_OFFSET_SECONDS = MAX_OFFSET_MILLIS / 1000f
    }

    var millis by mutableStateOf(initialMillis.coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS))
        private set

    val seconds: Float get() = millis / 1000f

    fun update(newMillis: Int) {
        millis = newMillis.coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS)
    }

    fun adjust(videoStartUtc: String): String {
        if (videoStartUtc.isEmpty()) return ""
        return try {
            val baseInstant = java.time.Instant.parse(videoStartUtc)
            baseInstant.plusMillis(millis.toLong()).toString()
        } catch (e: Exception) {
            videoStartUtc
        }
    }
}

@Composable
fun rememberTimeAlignmentState(initialMillis: Int): TimeAlignmentState {
    return remember { TimeAlignmentState(initialMillis) }
}
