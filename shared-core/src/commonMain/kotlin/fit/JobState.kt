package fit

import kotlinx.serialization.Serializable

@Serializable
data class JobState(
    val jobHash: String,
    val isPlateMaskStreamReady: Boolean = false,
    val isRoadTelemetryReady: Boolean = false
)
