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
}

@Serializable
data class CpState(
    val settings: HudSettings,
    val fitPath: String,
    val videoPath: String,
    val isEncoding: Boolean,
    val progress: Float,
    val videoStartUtc: String = "",
    val isAligningTelemetry: Boolean = false
)
