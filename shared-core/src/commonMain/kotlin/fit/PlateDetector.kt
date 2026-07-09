package fit

interface PlateDetector {
    suspend fun detect(
        videoPath: String,
        telemetryPoints: List<TelemetryPoint> = emptyList(),
        adjustedStartUtc: String = "",
        onProgress: (Float, String) -> Unit,
        onCancel: () -> Boolean,
        onPartialResult: (VideoPlatesCache) -> Unit = {},
        maxRecords: Int? = null,
        saveCache: Boolean = true,
        settings: HudSettings = HudSettings(),
        scanRanges: List<Pair<Double, Double>>? = null
    ): VideoPlatesCache?
}
