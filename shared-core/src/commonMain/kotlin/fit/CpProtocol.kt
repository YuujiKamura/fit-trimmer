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
}

@Serializable
data class CpState(
    val settings: HudSettings,
    val fitPath: String,
    val videoPath: String,
    val isEncoding: Boolean,
    val progress: Float
)
