package viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fit.HudSettings


enum class BatchJobStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class BatchJobPhaseType {
    PLATE_SCAN,
    ROAD_SCAN,
    HUD_ENCODE,
    CONCAT_MERGE,
    FAST_TRIM
}

enum class BatchJobPhaseStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

class BatchJobPhase(
    val type: BatchJobPhaseType,
    initialEnabled: Boolean,
    initialStatus: BatchJobPhaseStatus = BatchJobPhaseStatus.WAITING,
    initialProgress: Float = 0f
) {
    var enabled by mutableStateOf(initialEnabled)
    var status by mutableStateOf(initialStatus)
    var progress by mutableStateOf(initialProgress)
}

data class BatchJob(
    val id: String = java.util.UUID.randomUUID().toString(),
    val videoPath: String,
    val fitPath: String,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val trimStartSeconds: Double,
    val trimEndSeconds: Double,
    val splitPoints: List<Double>,
    private val initialSettings: HudSettings,
    private val initialAutoDetectRoadCaptionsOnEncode: Boolean = false,
    private val initialOutputFileNames: List<String> = listOf(videoPath.substringAfterLast('\\').substringAfterLast('/')),
    private val initialStatus: BatchJobStatus = BatchJobStatus.WAITING,
    private val initialProgress: Float = 0.0f,
    private val initialErrorMessage: String? = null,
    private val initialPhases: List<BatchJobPhase>? = null
) {
    var status by mutableStateOf(initialStatus)
    var progress by mutableStateOf(initialProgress)
    var errorMessage by mutableStateOf(initialErrorMessage)
    var outputFileNames by mutableStateOf(initialOutputFileNames)
    var settings by mutableStateOf(initialSettings)
    var autoDetectRoadCaptionsOnEncode by mutableStateOf(initialAutoDetectRoadCaptionsOnEncode)
    var phases by mutableStateOf<List<BatchJobPhase>>(initialPhases ?: emptyList())

    val isRunnable: Boolean
        get() = status == BatchJobStatus.WAITING || status == BatchJobStatus.FAILED

    val entryName: String
        get() = if (outputFileNames.size <= 1) {
            outputFileNames.firstOrNull() ?: videoPath.substringAfterLast('\\').substringAfterLast('/')
        } else {
            "${outputFileNames.first()} (+${outputFileNames.size - 1})"
        }

    val adjustedStartUtc: String
        get() = try {
            if (videoStartUtc.isNotEmpty()) {
                java.time.Instant.parse(videoStartUtc).plusMillis(timeOffsetMillis).toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            videoStartUtc
        }
}
