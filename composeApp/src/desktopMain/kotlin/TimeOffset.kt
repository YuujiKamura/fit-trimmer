import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class TimeAlignmentState(initialMillis: Int) {
    val coreState = fit.TimeAlignmentState(initialMillis)

    var millis by mutableStateOf(coreState.millis)
        private set

    val seconds: Float get() = coreState.seconds

    fun update(newMillis: Int) {
        coreState.update(newMillis)
        millis = coreState.millis
    }

    fun adjust(videoStartUtc: String): String {
        val currentMillis = millis
        if (videoStartUtc.isEmpty()) return ""
        return try {
            val baseInstant = java.time.Instant.parse(videoStartUtc)
            baseInstant.plusMillis(currentMillis.toLong()).toString()
        } catch (e: Exception) {
            videoStartUtc
        }
    }

    fun updateTimeComponents(hour: Int, minute: Int, second: Int, baseUtcStr: String) {
        coreState.updateTimeComponents(hour, minute, second, baseUtcStr)
        millis = coreState.millis
    }

    companion object {
        const val MAX_OFFSET_MILLIS = fit.TimeAlignmentState.MAX_OFFSET_MILLIS
        const val MAX_OFFSET_SECONDS = fit.TimeAlignmentState.MAX_OFFSET_SECONDS
    }
}

@Composable
fun rememberTimeAlignmentState(initialMillis: Int): TimeAlignmentState {
    return remember { TimeAlignmentState(initialMillis) }
}
