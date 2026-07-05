package utils

import viewmodel.BatchJob
import viewmodel.BatchJobStatus
import fit.HudSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BatchFolderCandidates(
    val fitFile: File?,
    val videoFiles: List<File>,
    val errorMessage: String? = null
)

object BatchFolderLoader {
    fun discoverCandidates(folderPath: String): BatchFolderCandidates {
        val dir = File(folderPath.trim())
        if (!dir.exists()) return BatchFolderCandidates(null, emptyList(), "フォルダが存在しません: ${dir.path}")
        if (!dir.isDirectory) return BatchFolderCandidates(null, emptyList(), "フォルダではありません: ${dir.path}")
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val fitFile = files
            .filter { it.extension.equals("fit", ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
        val videos = files
            .filter { file ->
                val ext = file.extension.lowercase()
                val name = file.name
                (ext == "mp4" || ext == "mov") &&
                        !name.startsWith("LRV_", ignoreCase = true) &&
                        !name.contains("_lrv", ignoreCase = true) &&
                        !name.contains("_KMP_HUD", ignoreCase = true)
            }
            .sortedWith(compareBy<File> { it.name }.thenBy { it.lastModified() })
        return BatchFolderCandidates(fitFile, videos)
    }

    suspend fun loadJobs(
        folderPath: String,
        currentSettings: HudSettings,
        autoDetectRoadCaptions: Boolean,
        timeOffsetMillis: Long,
        existingVideoPaths: List<String>,
        durationProvider: suspend (String) -> Long? = { getVideoDuration(it) },
        startUtcProvider: suspend (String) -> String? = { getVideoStartUtc(it) }
    ): Pair<List<BatchJob>, String> {
        val candidates = withContext(Dispatchers.IO) { discoverCandidates(folderPath) }
        if (candidates.errorMessage != null) {
            return Pair(emptyList(), candidates.errorMessage)
        }
        val fitFile = candidates.fitFile ?: run {
            return Pair(emptyList(), "FITファイルが見つかりません。")
        }
        if (candidates.videoFiles.isEmpty()) {
            return Pair(emptyList(), "投入対象の動画が見つかりません。")
        }

        val jobs = mutableListOf<BatchJob>()
        val skipped = mutableListOf<String>()
        val existingNormalized = existingVideoPaths.map { File(it).absolutePath.lowercase() }

        for (videoFile in candidates.videoFiles) {
            val videoAbs = videoFile.absolutePath
            if (existingNormalized.contains(videoAbs.lowercase())) {
                skipped.add(videoFile.name)
                continue
            }
            val durationMs = durationProvider(videoAbs)
            if (durationMs == null || durationMs <= 0L) {
                skipped.add("${videoFile.name}(duration)")
                continue
            }
            val startUtc = startUtcProvider(videoAbs).orEmpty()
            val durationSeconds = durationMs.toDouble() / 1000.0

            val jobSettings = currentSettings.copy()
            val outputFileName = fit.HudFileNameFormatter.buildEncodeOutputFileName(
                settings = jobSettings,
                videoPath = videoAbs,
                partIndex = -1,
                numParts = 1,
                isSample = false,
                trimStartSeconds = null,
                trimEndSeconds = null,
                dateTag = null
            )

            val job = BatchJob(
                videoPath = videoAbs,
                fitPath = fitFile.absolutePath,
                videoStartUtc = startUtc,
                timeOffsetMillis = timeOffsetMillis,
                trimStartSeconds = 0.0,
                trimEndSeconds = durationSeconds,
                splitPoints = emptyList(),
                initialSettings = jobSettings,
                initialAutoDetectRoadCaptionsOnEncode = autoDetectRoadCaptions,
                initialOutputFileNames = listOf(outputFileName)
            )
            jobs.add(job)
        }

        val added = jobs.size
        val statusText = if (added > 0) {
            "${fitFile.name} と動画 ${added} 件をキューに追加しました。"
        } else {
            "新しく追加できる動画はありません。"
        }
        if (skipped.isNotEmpty()) {
            println("BATCH: folder load skipped videos=${skipped.joinToString(",")}")
        }
        return Pair(jobs, statusText)
    }
}
