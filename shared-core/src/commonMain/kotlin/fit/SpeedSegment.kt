package fit

import kotlinx.serialization.Serializable

@Serializable
data class SpeedSegment(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val speedFactor: Double
)
