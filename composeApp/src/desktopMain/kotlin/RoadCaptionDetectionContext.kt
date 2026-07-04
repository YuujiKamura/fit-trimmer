import fit.FitParser

data class RoadCaptionDetectionContext(
    val points: List<FitParser.TelemetryPoint>,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val videoDurationSeconds: Double
)
