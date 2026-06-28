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
    val valSize: Float = 23f,
    val tightness: Float = 1f,
    val spacing: Float = 16f,
    val xOffset: Float = 25f,
    val yOffset: Float = 60f,
    val graphH: Float = 60f,
    val graphW: Float = 139f,
    val exportResolution: String = "2.7k",
    val roadCaptions: List<RoadCaptionSegment> = emptyList(),
    val useProxyPreview: Boolean = false
)
