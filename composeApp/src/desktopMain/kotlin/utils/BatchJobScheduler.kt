package utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fit.HudSettings
import viewmodel.BatchJob
import viewmodel.BatchJobStatus
import kotlinx.coroutines.launch


class BatchJobScheduler(
    private val onSaveBatchQueue: () -> Unit,
    private val onSaveCurrentHistory: () -> Unit,
    private val onCreateBatchJob: (
        videoPath: String,
        fitPath: String,
        videoStartUtc: String,
        trimStart: Double,
        trimEnd: Double,
        splits: List<Double>,
        durationSeconds: Double?
    ) -> BatchJob
) {

    val batchQueue = mutableStateListOf<BatchJob>()
    var showBatchConfirmDialog by mutableStateOf(false)
    var batchStatusText by mutableStateOf("")

    var isBatchFolderLoading by mutableStateOf(false)
    var batchFolderStatusText by mutableStateOf("")

    private fun logBatch(message: String) {
        println("BATCH: $message")
    }

    fun logBatchQueueSnapshot(context: String) {
        logBatch("$context queueSize=${batchQueue.size}, runnable=${batchQueue.count { it.isRunnable }}")
        batchQueue.forEachIndexed { index, job ->
            logBatch(
                "  [$index] id=${job.id.take(8)} status=${job.status} progress=${"%.3f".format(job.progress)} " +
                    "entry='${job.entryName}' video='${job.videoPath.substringAfterLast('\\').substringAfterLast('/')}' trim=${job.trimStartSeconds}-${job.trimEndSeconds} " +
                    "splits=${job.splitPoints.size} fit='${job.fitPath.substringAfterLast('\\').substringAfterLast('/')}'"
            )
        }
    }

    fun requestBatchConfirmDialog(source: String): Boolean {
        logBatchQueueSnapshot("confirm requested from $source")
        if (batchQueue.none { it.isRunnable }) {
            batchStatusText = "処理待ちのジョブがありません。"
            showBatchConfirmDialog = false
            logBatch("confirm suppressed: no waiting or failed jobs")
            return false
        }
        showBatchConfirmDialog = true
        logBatch("confirm dialog opened")
        return true
    }

    fun dismissBatchConfirmDialog(source: String, isBatchRunning: Boolean) {
        showBatchConfirmDialog = false
        if (!isBatchRunning) {
            batchQueue.clear()
            onSaveBatchQueue()
            logBatch("confirm dialog dismissed from $source, queue cleared")
        } else {
            logBatch("confirm dialog dismissed from $source")
        }
    }

    fun prepareBatchQueueForStart() {
        batchQueue.forEach {
            if (it.status == BatchJobStatus.FAILED || it.status == BatchJobStatus.COMPLETED) {
                it.status = BatchJobStatus.WAITING
                it.progress = 0f
                it.errorMessage = null
            }
        }
        onSaveCurrentHistory()
        onSaveBatchQueue()
        logBatchQueueSnapshot("starting confirmed batch")
    }

    fun addToQueue(
        videoPath: String,
        fitPath: String,
        videoStartUtc: String,
        trimStartSeconds: Double,
        trimEndSeconds: Double,
        splitPoints: List<Double>,
        videoLengthMs: Long
    ) {
        if (videoPath.isNotEmpty()) {
            val job = onCreateBatchJob(
                videoPath,
                fitPath,
                videoStartUtc,
                trimStartSeconds,
                trimEndSeconds,
                splitPoints,
                videoLengthMs.takeIf { it > 0L }?.toDouble()?.div(1000.0)
            )

            batchQueue.add(job)
            onSaveCurrentHistory()
            onSaveBatchQueue()
            logBatchQueueSnapshot("added job")
        }
    }

    fun setBatchJobRoadCaptionDetection(jobId: String, enabled: Boolean) {
        val job = batchQueue.firstOrNull { it.id == jobId } ?: return
        job.autoDetectRoadCaptionsOnEncode = enabled
        onSaveBatchQueue()
        logBatchQueueSnapshot("road caption toggle id=${jobId.take(8)} enabled=$enabled")
    }

    fun setBatchJobPlateMasking(jobId: String, enabled: Boolean) {
        val job = batchQueue.firstOrNull { it.id == jobId } ?: return
        job.settings = job.settings.copy(blurLicensePlates = enabled)
        onSaveBatchQueue()
        logBatchQueueSnapshot("plate masking toggle id=${jobId.take(8)} enabled=$enabled")
    }

    fun removeFromBatchQueue(jobId: String) {
        logBatch("remove requested id=${jobId.take(8)}")
        batchQueue.removeAll { it.id == jobId }
        if (showBatchConfirmDialog && batchQueue.none { it.isRunnable }) {
            showBatchConfirmDialog = false
            logBatch("confirm dialog closed: no runnable jobs after remove")
        }
        logBatchQueueSnapshot("removed job")
        onSaveBatchQueue()
    }

    fun moveBatchJobUp(jobId: String) {
        val index = batchQueue.indexOfFirst { it.id == jobId }
        if (index <= 0) {
            logBatch("move up skipped id=${jobId.take(8)} index=$index")
            return
        }
        val job = batchQueue.removeAt(index)
        batchQueue.add(index - 1, job)
        onSaveBatchQueue()
        logBatchQueueSnapshot("moved job up")
    }

    fun moveBatchJobDown(jobId: String) {
        val index = batchQueue.indexOfFirst { it.id == jobId }
        if (index < 0 || index >= batchQueue.lastIndex) {
            logBatch("move down skipped id=${jobId.take(8)} index=$index")
            return
        }
        val job = batchQueue.removeAt(index)
        batchQueue.add(index + 1, job)
        onSaveBatchQueue()
        logBatchQueueSnapshot("moved job down")
    }

    fun renameBatchJobEntry(jobId: String, newEntryName: String) {
        val job = batchQueue.firstOrNull { it.id == jobId } ?: run {
            logBatch("rename skipped id=${jobId.take(8)}: not found")
            return
        }
        val normalized = normalizeOutputFileName(newEntryName) ?: run {
            logBatch("rename skipped id=${jobId.take(8)}: blank name")
            return
        }
        val updated = job.outputFileNames.toMutableList()
        if (updated.isEmpty()) {
            updated.add(normalized)
        } else {
            updated[0] = normalized
        }
        job.outputFileNames = updated
        onSaveBatchQueue()
        logBatchQueueSnapshot("renamed job")
    }

    private fun normalizeOutputFileName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.endsWith(".mp4", ignoreCase = true) || trimmed.endsWith(".mov", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed.mp4"
        }
    }

    fun clearQueue() {
        logBatchQueueSnapshot("clear requested")
        batchQueue.clear()
        showBatchConfirmDialog = false
        onSaveBatchQueue()
        logBatch("queue cleared")
    }

    fun loadBatchFolder(
        folderPath: String,
        settings: HudSettings,
        autoDetectRoadCaptions: Boolean,
        timeOffsetMillis: Long,
        coroutineScope: kotlinx.coroutines.CoroutineScope
    ) {
        if (folderPath.isEmpty()) return
        isBatchFolderLoading = true
        coroutineScope.launch {
            try {
                val (jobs, status) = utils.BatchFolderLoader.loadJobs(
                    folderPath = folderPath,
                    currentSettings = settings,
                    autoDetectRoadCaptions = autoDetectRoadCaptions,
                    timeOffsetMillis = timeOffsetMillis,
                    existingVideoPaths = batchQueue.map { it.videoPath }
                )
                if (jobs.isNotEmpty()) {
                    batchQueue.addAll(jobs)
                    onSaveBatchQueue()
                    requestBatchConfirmDialog("folder-loader")
                }
                batchFolderStatusText = status
            } catch (e: Exception) {
                batchFolderStatusText = "フォルダ読み込みに失敗しました: ${e.message ?: e::class.simpleName}"
                logBatch("folder load failed: ${e.message}")
            } finally {
                isBatchFolderLoading = false
            }
        }
    }
}
