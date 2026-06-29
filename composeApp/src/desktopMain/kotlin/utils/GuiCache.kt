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
    val trimStartSeconds: Double? = null,
    val trimEndSeconds: Double? = null,
    val splitPoints: List<Double>? = null,
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null
)

object GuiCache {
    private val file = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun shouldDeferSaveUntilVideoStartIsLoaded(videoPath: String, videoStartUtc: String): Boolean {
        return videoPath.isNotEmpty() && videoStartUtc.isEmpty() && File(videoPath).exists()
    }

    private fun getHistoryFile(videoPath: String): File? {
        if (videoPath.isEmpty()) return null
        val safeName = "hist_" + kotlin.math.abs(videoPath.hashCode()).toString() + ".json"
        val historyDir = File(System.getProperty("user.home"), ".fittrimmer_history")
        if (!historyDir.exists()) historyDir.mkdirs()
        return File(historyDir, safeName)
    }

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

    fun saveHistory(videoPath: String, cache: GuiPathCache) {
        try {
            val hFile = getHistoryFile(videoPath) ?: return
            hFile.writeText(json.encodeToString(cache), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadHistory(videoPath: String): GuiPathCache? {
        try {
            val hFile = getHistoryFile(videoPath) ?: return null
            if (hFile.exists()) {
                val content = hFile.readText(Charsets.UTF_8)
                return json.decodeFromString<GuiPathCache>(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
