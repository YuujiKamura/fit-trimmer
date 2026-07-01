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
        val targetNorm = if (videoPath.isNullOrEmpty()) "" else try { File(videoPath).canonicalPath.replace('\\', '/').lowercase() } catch (e: Exception) { videoPath.replace('\\', '/').lowercase() }

        return jobs.mapNotNull { jobDir ->
            val parts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) } ?: emptyArray()
            if (parts.isEmpty()) null
            else {
                val hash = jobDir.name.removePrefix("job_")
                val sourceFile = File(jobDir, ".video_source")
                val stateVideo = if (sourceFile.exists()) {
                    try { sourceFile.readText().trim() } catch (e: Exception) { null }
                } else null

                if (!stateVideo.isNullOrEmpty()) {
                    val stateNorm = try { File(stateVideo).canonicalPath.replace('\\', '/').lowercase() } catch (e: Exception) { stateVideo.replace('\\', '/').lowercase() }
                    if (stateNorm != targetNorm) {
                        return@mapNotNull null
                    }
                }

                val hasMask = File(jobDir, "plate_mask.mkv").exists()
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

        val totalBytes = parts.sumOf { it.length() }
        val pb = ProcessBuilder(concatArgs)
        pb.redirectErrorStream(true)
        val p = pb.start()

        val monitoringThread = kotlin.concurrent.thread(start = true) {
            try {
                while (p.isAlive) {
                    val currentBytes = finalDest.length()
                    val progressRatio = if (totalBytes > 0) (currentBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 1f
                    val currentProg = 0.3f + 0.6f * progressRatio
                    
                    val percent = currentProg * 100
                    val currentMB = currentBytes / (1024 * 1024)
                    val totalMB = totalBytes / (1024 * 1024)
                    
                    val status = "Merging video segments (Direct Concat)... %.1f%% (%d MB / %d MB)".format(percent, currentMB, totalMB)
                    onProgress(currentProg, status)
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                // Thread interrupted or finished
            }
        }

        // Read output line-by-line to prevent stream buffer blocking
        try {
            p.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) {
                    // Consume stream
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val exitCode = p.waitFor()
        try {
            monitoringThread.interrupt()
            monitoringThread.join(500)
        } catch (e: Exception) {
            // Ignore join timeouts
        }

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

    fun buildEncodeOutputFileName(
        settings: HudSettings,
        videoPath: String,
        partIndex: Int = -1,
        numParts: Int = 1,
        isSample: Boolean = false
    ): String {
        val videoFile = File(videoPath)
        val baseName = videoFile.name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
        val partSuffix = if (!isSample && partIndex >= 0 && numParts > 1) "_part${partIndex + 1}" else ""
        val resSuffix = when (settings.exportResolution) {
            "1080p" -> "_1080p"
            "2.7k" -> "_2.7k"
            else -> "_orig"
        }
        val suffix = if (isSample) {
            "${partSuffix}_TEST_HUD.mp4"
        } else {
            "${partSuffix}_KMP_HUD${resSuffix}.mp4"
        }
        return baseName + suffix
    }

    fun getSalvageOutputPath(videoPath: String, outputDir: String, settings: HudSettings): File {
        val baseFileName = buildEncodeOutputFileName(
            settings = settings,
            videoPath = videoPath,
            partIndex = -1,
            numParts = 1,
            isSample = false
        )
        val salvagedName = baseFileName.replace(Regex("""\.(mp4|mov)$""", RegexOption.IGNORE_CASE), "_salvaged.mp4")
        return File(File(outputDir), salvagedName)
    }

    fun deleteCacheJob(jobInfo: CacheJobInfo) {
        try {
            if (jobInfo.folder.exists()) {
                jobInfo.folder.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAllCaches(videoPath: String) {
        try {
            val workDir = PathResolver.getTempWorkDir(videoPath)
            if (workDir.exists()) {
                val jobs = scanAvailableJobs(videoPath)
                jobs.forEach { job ->
                    deleteCacheJob(job)
                }
                val remaining = workDir.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    workDir.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
