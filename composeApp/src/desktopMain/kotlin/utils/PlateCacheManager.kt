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
) {
    fun findClosestRecord(targetTimeMs: Long): PlateRecord? {
        if (records.isEmpty()) return null
        var low = 0
        var high = records.size - 1
        
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = records[mid].timeMs
            
            when {
                midVal < targetTimeMs -> low = mid + 1
                midVal > targetTimeMs -> high = mid - 1
                else -> return records[mid]
            }
        }
        
        val candidate1 = if (low in records.indices) records[low] else null
        val candidate2 = if (high in records.indices) records[high] else null
        
        return when {
            candidate1 == null -> candidate2
            candidate2 == null -> candidate1
            else -> {
                if (kotlin.math.abs(candidate1.timeMs - targetTimeMs) < kotlin.math.abs(candidate2.timeMs - targetTimeMs)) {
                    candidate1
                } else {
                    candidate2
                }
            }
        }
    }
}

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
