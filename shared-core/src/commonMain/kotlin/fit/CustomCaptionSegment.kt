package fit

import kotlinx.serialization.Serializable

@Serializable
data class CustomCaptionSegment(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
    val isEnabled: Boolean = true,
    val fontSize: Float = 24f,
    val textColor: String = "#ffffff",
    val backgroundColor: String = "#000000",
    val backgroundAlpha: Float = 0.65f,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.8f,
    val align: String = "center", // "left", "center", "right"
    val isAbsoluteTime: Boolean = false
)
