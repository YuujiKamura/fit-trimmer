package utils

import fit.PlateDetector
import fit.TelemetryPoint
import fit.VideoPlatesCache
import fit.HudSettings
import kotlinx.coroutines.delay

class FakePlateDetector(
    var dummyResult: VideoPlatesCache? = null,
    var delayMillis: Long = 0L,
    var onDetectCalled: (() -> Unit)? = null
) : PlateDetector {

    override suspend fun detect(
        videoPath: String,
        telemetryPoints: List<TelemetryPoint>,
        adjustedStartUtc: String,
        onProgress: (Float, String) -> Unit,
        onCancel: () -> Boolean,
        onPartialResult: (VideoPlatesCache) -> Unit,
        maxRecords: Int?,
        saveCache: Boolean,
        settings: HudSettings,
        scanRanges: List<Pair<Double, Double>>?
    ): VideoPlatesCache? {
        onDetectCalled?.invoke()
        
        onProgress(0.0f, "Starting fake scan")
        if (delayMillis > 0) {
            delay(delayMillis / 2)
        }
        
        if (onCancel()) return null
        
        onProgress(0.5f, "Scanning half way")
        if (delayMillis > 0) {
            delay(delayMillis / 2)
        }
        
        if (onCancel()) return null
        
        onProgress(1.0f, "Completed scan")
        
        dummyResult?.let { onPartialResult(it) }
        
        return dummyResult
    }
}
