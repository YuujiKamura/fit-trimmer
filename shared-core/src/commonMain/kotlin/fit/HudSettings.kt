package fit

import kotlinx.serialization.Serializable

@Serializable
data class RoadCaptionSegment(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
    val isEnabled: Boolean = true
)

@Serializable
data class HudSettings(
    val valSize: Float = 59.27f,
    val tightness: Float = -10.0f,
    val spacing: Float = 33.09f,
    val xOffset: Float = 51.06f,
    val yOffset: Float = 108.35f,
    val graphH: Float = 103.63f,
    val graphW: Float = 207.15f,
    val exportResolution: String = "2.7k",
    val captionPosition: String = "top_center",
    val roadCaptions: List<RoadCaptionSegment> = emptyList(),
    val powerTrendSpanSeconds: Int = 60,
    val language: String = ""
)
