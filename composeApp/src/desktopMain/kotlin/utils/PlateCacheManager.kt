package utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

@Serializable
data class PlateRecord(val timeMs: Long, val boxes: List<PlateBox>)

@Serializable
data class VideoPlatesCache(
    val videoPath: String,
    val records: List<PlateRecord>
)

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
