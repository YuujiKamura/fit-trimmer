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

    data class CacheJobInfo(
        val jobHash: String,
        val folder: File,
        val partsCount: Int,
        val lastModified: Long,
        val hasMaskVideo: Boolean
    )

    fun scanAvailableJobs(videoPath: String): List<CacheJobInfo> {
        val workDir = PathResolver.getTempWorkDir(videoPath)
        if (!workDir.exists() || !workDir.isDirectory) return emptyList()

        val jobs = workDir.listFiles { _, name -> name.startsWith("job_") } ?: emptyArray()
        return jobs.mapNotNull { jobDir ->
            val parts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) } ?: emptyArray()
            if (parts.isEmpty()) null
            else {
                val hasMask = File(jobDir, "plate_mask.mkv").exists()
                val hash = jobDir.name.removePrefix("job_")
                CacheJobInfo(
                    jobHash = hash,
                    folder = jobDir,
                    partsCount = parts.size,
                    lastModified = jobDir.lastModified(),
                    hasMaskVideo = hasMask
                )
            }
        }.sortedByDescending { it.lastModified }
    }

    fun salvageAndMerge(
        jobDir: File,
        output: String,
        onProgress: (progress: Float, status: String) -> Unit
    ) {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val parts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) }?.sortedBy { it.name } ?: emptyList()
        if (parts.isEmpty()) {
            throw Exception("No valid TS parts found in ${jobDir.absolutePath}")
        }

        onProgress(0.1f, "Preparing merge list...")
        val partsListFile = File(jobDir, "parts.txt")
        val listContent = parts.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }
        partsListFile.writeText(listContent)

        val finalDest = File(output)
        finalDest.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (finalDest.exists()) finalDest.delete()

        onProgress(0.3f, "Merging video segments (Direct Concat)...")
        val concatArgs = listOf(
            ffmpegPath, "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", partsListFile.absolutePath,
            "-c", "copy",
            "-metadata", "comment=fit-trimmer-hud-burned-salvaged",
            finalDest.absolutePath
        )

        val pb = ProcessBuilder(concatArgs)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val exitCode = p.waitFor()

        if (exitCode != 0) {
            val errorMsg = p.inputStream.bufferedReader().readText()
            throw Exception("Failed to merge. ffmpeg exit code: $exitCode\n$errorMsg")
        }

        onProgress(0.9f, "Cleaning up salvaged temporary parts...")
        try {
            parts.forEach { it.delete() }
            partsListFile.delete()
            jobDir.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onProgress(1.0f, "✨ Salvage & Merge Completed Successfully!")
    }
}
