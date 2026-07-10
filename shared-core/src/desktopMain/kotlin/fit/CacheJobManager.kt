package fit

import java.io.File

/**
 * 分割エンコード中の一時ディスクキャッシュ（ジョブフォルダ）のライフサイクルを管理するモジュール。
 * 外部の呼び出し元は、このインターフェースのみを通じて操作を行い、内部の ffmpeg 呼び出しや
 * パーツファイルの構成、.video_source パス等の詳細を知る必要はない。
 */
interface CacheJobManager {
    fun scanJobs(videoPath: String): List<CacheJob>
    fun clearAll(videoPath: String)
    fun getSalvageOutputPath(videoPath: String, outputDir: String, settings: HudSettings): File

    companion object {
        fun getInstance(): CacheJobManager = DefaultCacheJobManager
    }
}

/**
 * 個々のエンコードキャッシュジョブを表すインターフェース。
 * 呼び出し元がキャッシュジョブについて知るべき最低限の属性と、それに対する操作を提供する。
 */
interface CacheJob {
    val jobHash: String
    val folder: File
    val partsCount: Int
    val lastModified: Long
    val hasMaskVideo: Boolean

    /**
     * このジョブの一時パーツを結合して1つのファイルにする。
     * マージが完了すると、このジョブの一時フォルダは自動的にクリーンアップされる。
     */
    fun salvageAndMerge(outputFile: File, onProgress: (progress: Float, status: String) -> Unit)

    /**
     * このジョブのキャッシュファイルを物理的に削除する。
     */
    fun delete()
}

/**
 * 実装詳細がカプセル化された具象マネージャー。
 */
private object DefaultCacheJobManager : CacheJobManager {
    override fun scanJobs(videoPath: String): List<CacheJob> {
        val workDir = PathResolver.getTempWorkDir(videoPath)
        if (!workDir.exists() || !workDir.isDirectory) return emptyList()

        val jobs = workDir.listFiles { _, name -> name.startsWith("job_") } ?: emptyArray()
        val targetNorm = PlateCacheManager.getNormalizedPath(videoPath)

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
                    val stateNorm = PlateCacheManager.getNormalizedPath(stateVideo)
                    if (stateNorm != targetNorm) {
                        return@mapNotNull null
                    }
                }

                val hasMask = File(jobDir, "plate_mask.mkv").exists()
                DefaultCacheJob(
                    jobHash = hash,
                    folder = jobDir,
                    partsCount = parts.size,
                    lastModified = jobDir.lastModified(),
                    hasMaskVideo = hasMask,
                    parts = parts.sortedBy { it.name }
                )
            }
        }.sortedByDescending { it.lastModified }
    }

    override fun clearAll(videoPath: String) {
        try {
            val workDir = PathResolver.getTempWorkDir(videoPath)
            if (workDir.exists()) {
                val jobs = scanJobs(videoPath)
                jobs.forEach { job ->
                    job.delete()
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

    override fun getSalvageOutputPath(videoPath: String, outputDir: String, settings: HudSettings): File {
        val baseFileName = HudFileNameFormatter.buildEncodeOutputFileName(
            settings = settings,
            videoPath = videoPath,
            partIndex = -1,
            numParts = 1,
        )
        val salvagedName = baseFileName.replace(Regex("""\.(mp4|mov)$""", RegexOption.IGNORE_CASE), "_salvaged.mp4")
        return File(File(outputDir), salvagedName)
    }
}

/**
 * 具象ジョブクラス。FFmpeg 呼び出しやプロセス監視はここにカプセル化される。
 */
internal class DefaultCacheJob(
    override val jobHash: String,
    override val folder: File,
    override val partsCount: Int,
    override val lastModified: Long,
    override val hasMaskVideo: Boolean,
    internal val parts: List<File>
) : CacheJob {

    override fun salvageAndMerge(outputFile: File, onProgress: (progress: Float, status: String) -> Unit) {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        if (parts.isEmpty()) {
            throw Exception("No valid TS parts found in ${folder.absolutePath}")
        }

        onProgress(0.1f, "Preparing merge list...")
        val partsListFile = File(folder, "parts.txt")
        val listContent = parts.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }
        partsListFile.writeText(listContent)

        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (outputFile.exists()) outputFile.delete()

        onProgress(0.3f, "Merging video segments (Direct Concat)...")
        val jobState = JobStateManager.loadState(folder, jobHash)
        val metadataArgs = if (jobState.alignedVideoStartUtc != null || jobState.timeOffsetMillis != null) {
            EncodeGroundTruthMetadata(
                sourceVideoPath = jobState.videoPath ?: parts.firstOrNull()?.absolutePath.orEmpty(),
                sourceVideoStartUtc = jobState.sourceVideoStartUtc.orEmpty(),
                alignedVideoStartUtc = jobState.alignedVideoStartUtc.orEmpty(),
                timeOffsetMillis = jobState.timeOffsetMillis ?: 0L
            ).toFfmpegMetadataArgs()
        } else {
            listOf("-metadata", "comment=fit-trimmer-hud-burned-salvaged")
        }

        val concatArgs = listOf(
            ffmpegPath, "-y",
            "-nostdin",
            "-fflags", "+genpts",
            "-f", "concat",
            "-safe", "0",
            "-i", partsListFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart"
        ) + metadataArgs + outputFile.absolutePath

        val totalBytes = parts.sumOf { it.length() }
        val pb = ProcessBuilder(concatArgs)
        pb.redirectErrorStream(true)
        val p = pb.start()

        val monitoringThread = kotlin.concurrent.thread(start = true) {
            try {
                while (p.isAlive) {
                    val currentBytes = outputFile.length()
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
            folder.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onProgress(1.0f, "✨ Salvage & Merge Completed Successfully!")
    }

    override fun delete() {
        try {
            if (folder.exists()) {
                folder.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
