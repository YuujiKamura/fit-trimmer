package fit

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

class FileSignalCache : SignalCache {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(videoPath: String): List<TrafficSignalNode>? {
        try {
            val workDir = PathResolver.getTempWorkDir(videoPath)
            val cacheFile = File(workDir, "osm_signals_cache.json")
            if (cacheFile.exists()) {
                val content = cacheFile.readText()
                return json.decodeFromString(ListSerializer(TrafficSignalNode.serializer()), content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun save(videoPath: String, nodes: List<TrafficSignalNode>) {
        try {
            val workDir = PathResolver.getTempWorkDir(videoPath)
            if (!workDir.exists()) {
                workDir.mkdirs()
            }
            val cacheFile = File(workDir, "osm_signals_cache.json")
            val content = json.encodeToString(ListSerializer(TrafficSignalNode.serializer()), nodes)
            cacheFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
