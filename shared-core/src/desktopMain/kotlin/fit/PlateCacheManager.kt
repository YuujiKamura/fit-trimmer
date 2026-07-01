package fit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File



object PlateCacheManager {
    private val json = Json { ignoreUnknownKeys = true }
    
    fun getNormalizedPath(videoPath: String): String {
        if (videoPath.isEmpty()) return ""
        val cleanPath = videoPath.replace('\\', '/').trim()
        
        // Junction path check: if path is inside temp_work/fit_trimmer_video_junction,
        // use its file name as the normalization key to ensure original/junction compatibility.
        if (cleanPath.contains("fit_trimmer_video_junction", ignoreCase = true)) {
            val name = File(cleanPath).name.lowercase()
            return "junction_normalized_key:$name"
        }
        
        // Use simple absolute path normalization instead of canonicalPath,
        // preventing external drive mount resolver variance or path resolves failing.
        return try {
            val file = File(cleanPath)
            file.absolutePath.replace('\\', '/').lowercase()
        } catch (e: Exception) {
            cleanPath.lowercase()
        }
    }

    fun getPlatesFile(videoPath: String): File? {
        if (videoPath.isEmpty()) return null
        val norm = getNormalizedPath(videoPath)
        val safeName = "plates_" + kotlin.math.abs(norm.hashCode()).toString() + ".json"
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

    fun deleteCache(videoPath: String): Boolean {
        val file = getPlatesFile(videoPath) ?: return false
        return !file.exists() || file.delete()
    }

    fun cacheExists(videoPath: String): Boolean {
        return getPlatesFile(videoPath)?.exists() == true
    }
}
