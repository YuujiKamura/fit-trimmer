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
data class RiskTimeSpan(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val type: String, // "cut" or "mask"
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
    val customCaptions: List<CustomCaptionSegment> = emptyList(),
    val speedSegments: List<SpeedSegment> = emptyList(),
    val powerTrendSpanSeconds: Int = 60,
    val language: String = "",
    val useImperialUnits: Boolean = false,
    val enableRoadDetection: Boolean = true,
    val blurLicensePlates: Boolean = false,
    val plateMaskMode: String = "plate",
    val plateMaxSpeedKmh: Double = 30.0,
    val plateDetectionFps: Double = 1.0,
    val platePaddingSeconds: Double = 2.0,
    val plateMergeGapSeconds: Double = 5.0,
    val plateMaskExpandRatio: Double = 0.2,
    val plateMaskTimeBufferMs: Long = 300L,
    val elevationGraphScope: String = "video", // "video" or "activity"
    val heartRateAccumulationScope: String = "activity", // "video" or "activity"
    val showSpeed: Boolean = true,
    val showCadence: Boolean = true,
    val showHeartRate: Boolean = true,
    val showPower: Boolean = true,
    val showWkg: Boolean = true,
    val showPowerTrend: Boolean = true,
    val showGrade: Boolean = true,
    val showElevation: Boolean = true,
    val showDistanceTime: Boolean = true,
    val bodyWeightKg: Double = 0.0,
    val mapSizeScale: Float = 1.0f,
    val mapType: String = "auto",
    val mapPosition: String = "top_right",
    val hudBgAlpha: Float = 0.0f,
    val mapZoomScale: Float = 0.55f,
    val mapZoomOffset: Int = 0,
    val fixMapNorthUp: Boolean = false,
    val mapMarkerSizeScale: Float = 1.0f,
    val mapTextSizeScale: Float = 1.0f,
    val mapRangeMode: String = "full",
    val textShadowAlpha: Float = 0.8f,
    val riskTimeSpans: List<RiskTimeSpan> = emptyList(),
    val detectPedestrians: Boolean = false,
    val showCumulativeDistanceTime: Boolean = false
)

