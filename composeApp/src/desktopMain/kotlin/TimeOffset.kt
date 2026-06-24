import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

class TimeAlignmentState(initialMillis: Int) {
    var millis by mutableStateOf(initialMillis.coerceIn(-120000, 120000))
        private set

    val seconds: Float get() = millis / 1000f

    fun update(newMillis: Int) {
        millis = newMillis.coerceIn(-120000, 120000)
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
