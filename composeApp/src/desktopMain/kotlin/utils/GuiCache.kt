package utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import fit.HudSettings

@Serializable
data class GuiPathCache(
    val fitPath: String,
    val videoPath: String,
    val videoStartUtc: String,
    val timeOffsetSeconds: Float? = null,
    val timeOffsetMillis: Int? = null,
    val settings: HudSettings = HudSettings(),
    val moveOutputToSource: Boolean = false,
    val showLivePreview: Boolean = true,
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null
)

object GuiCache {
    private val file = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): GuiPathCache? {
        return try {
            if (file.exists()) {
                val content = file.readText(Charsets.UTF_8)
                json.decodeFromString<GuiPathCache>(content)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun save(cache: GuiPathCache) {
        try {
            file.writeText(json.encodeToString(cache), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
