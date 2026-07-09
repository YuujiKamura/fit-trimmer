import fit.FitParser
import fit.TelemetryPoint

data class RoadCaptionDetectionContext(
    val points: List<TelemetryPoint>,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val videoDurationSeconds: Double
)
