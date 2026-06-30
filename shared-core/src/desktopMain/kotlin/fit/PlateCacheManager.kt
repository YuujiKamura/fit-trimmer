package fit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File



object PlateCacheManager {
    private val json = Json { ignoreUnknownKeys = true }
    
    fun getPlatesFile(videoPath: String): File? {
        if (videoPath.isEmpty()) return null
        val safeName = "plates_" + kotlin.math.abs(videoPath.hashCode()).toString() + ".json"
        val historyDir = File(System.getProperty("user.home"), ".fittrimmer_history")
        if (!historyDir.exists()) historyDir.mkdirs()
        return File(historyDir, safeName)
    }
    
    fun loadCache(videoPath: String): VideoPlatesCache? {
        val file = getPlatesFile(videoPath) ?: return null
        if (!file.exists()) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            json.decodeFromString<VideoPlatesCache>(content)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun saveCache(videoPath: String, cache: VideoPlatesCache) {
        val file = getPlatesFile(videoPath) ?: return
        try {
            file.writeText(json.encodeToString(VideoPlatesCache.serializer(), cache), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
