package fit

import java.io.File

interface CacheableResource {
    val file: File
    val groupKey: String
    val lastAccessed: Long
    fun invalidate()
}

data class GenericCacheResource(
    override val file: File,
    override val groupKey: String,
    override val lastAccessed: Long = System.currentTimeMillis()
) : CacheableResource {
    override fun invalidate() {
        try {
            if (file.exists()) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

object CacheRegistry {
    private val resources = mutableListOf<CacheableResource>()

    fun register(resource: CacheableResource) {
        synchronized(resources) {
            if (resources.none { it.file.absolutePath == resource.file.absolutePath }) {
                resources.add(resource)
            }
        }
    }

    fun createTempFile(groupKey: String, fileName: String, parentDir: File? = null): File {
        val baseDir = parentDir ?: PathResolver.getTempWorkDir()
        val file = File(baseDir, fileName)
        register(GenericCacheResource(file, groupKey))
        return file
    }

    fun invalidateGroup(groupKey: String) {
        synchronized(resources) {
            val toRemove = resources.filter { it.groupKey == groupKey }
            toRemove.forEach { it.invalidate() }
            resources.removeAll(toRemove)
        }
    }

    fun cleanStaleCache(cutoffMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - cutoffMs
        synchronized(resources) {
            val stale = resources.filter { it.lastAccessed < cutoff }
            stale.forEach { it.invalidate() }
            resources.removeAll(stale)
        }
    }
}
