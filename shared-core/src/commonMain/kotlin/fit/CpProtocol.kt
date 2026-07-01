package fit

import kotlinx.serialization.Serializable

@Serializable
sealed class CpCommand {
    @Serializable @kotlinx.serialization.SerialName("set_layout")
    data class SetLayout(val settings: HudSettings) : CpCommand()
    
    @Serializable @kotlinx.serialization.SerialName("set_files")
    data class SetFiles(val fit: String, val video: String, val startUtc: String? = null) : CpCommand()
    
    @Serializable @kotlinx.serialization.SerialName("fire")
    object Fire : CpCommand()
    
    @Serializable @kotlinx.serialization.SerialName("get_state")
    object GetState : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("update_progress")
    data class UpdateProgress(val progress: Float, val isEncoding: Boolean) : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("reload_hud")
    object ReloadHud : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("play")
    object Play : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("pause")
    object Pause : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("seek")
    data class Seek(val timeMs: Long) : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("capture")
    object Capture : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("align_telemetry")
    object AlignTelemetry : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("reset_plate_detection")
    object ResetPlateDetection : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("seek_plate_detection")
    data class SeekPlateDetection(val index: Int = 0) : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("run_plate_detection")
    data class RunPlateDetection(
        val maxRecords: Int? = null,
        val maxSpeedKmh: Double? = null,
        val detectionFps: Double? = null,
        val paddingSeconds: Double? = null,
        val mergeGapSeconds: Double? = null
    ) : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("stop_plate_detection")
    object StopPlateDetection : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("set_plate_cache_enabled")
    data class SetPlateCacheEnabled(val enabled: Boolean) : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("restore_plate_cache")
    object RestorePlateCache : CpCommand()

    @Serializable @kotlinx.serialization.SerialName("discard_plate_cache")
    object DiscardPlateCache : CpCommand()
}

@Serializable
data class CpState(
    val settings: HudSettings,
    val fitPath: String,
    val videoPath: String,
    val isEncoding: Boolean,
    val progress: Float,
    val videoStartUtc: String = "",
    val isAligningTelemetry: Boolean = false,
    val isDetectingPlates: Boolean = false,
    val plateDetectionProgress: String = "",
    val plateDetectionError: String? = null,
    val plateRecordCount: Int = 0,
    val plateBoxCount: Int = 0,
    val plateFirstTimeMs: Long? = null,
    val plateLastTimeMs: Long? = null,
    val plateCacheLoaded: Boolean = false,
    val videoCurrentTimeMs: Long = 0,
    val activePlateBoxCount: Int = 0,
    val activePlateBoxes: List<PlateBox> = emptyList(),
    val plateCacheEnabled: Boolean = true,
    val plateCacheFileExists: Boolean = false,
    val plateCacheFilePath: String = "",
    val plateCacheSourceWidth: Int = 0,
    val plateCacheSourceHeight: Int = 0,
    val plateDetectionMaxSpeedKmh: Double = 15.0,
    val plateDetectionFps: Double = 1.0,
    val plateDetectionPaddingSeconds: Double = 2.0,
    val plateDetectionMergeGapSeconds: Double = 5.0
)
