package viewmodel

import androidx.compose.runtime.*
import fit.HudSettings
import fit.FitParser
import java.io.File
import TimeAlignmentState
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import fit.PlateCacheManager
import fit.VideoPlatesCache


class AppViewModel(
    initialCache: utils.GuiPathCache?
) {
    // Basic States
    var settings by mutableStateOf(initialCache?.settings ?: HudSettings())
    var fitPath by mutableStateOf(initialCache?.fitPath ?: "")

    var isGeneratingProxy by mutableStateOf(false)
    var proxyProgress by mutableStateOf(0f)
    var proxyVideoPath by mutableStateOf<String?>(null)

    var isDetectingRoads by mutableStateOf(false)
    var roadDetectionProgressText by mutableStateOf("")

    private var _videoPath by mutableStateOf(initialCache?.videoPath ?: "")
    var videoPath: String
        get() = _videoPath
        set(value) {
            if (_videoPath != value) {
                val oldPath = _videoPath
                if (oldPath.isNotEmpty()) {
                    val currentCache = utils.GuiPathCache(
                        fitPath = fitPath,
                        videoPath = oldPath,
                        videoStartUtc = videoStartUtc,
                        timeOffsetMillis = timeOffsetState.millis,
                        settings = settings.copy(),
                        moveOutputToSource = moveOutputToSource,
                        showLivePreview = showLivePreview,
                        previewQualityMode = previewQualityMode,
                        autoDetectRoadCaptionsOnEncode = autoDetectRoadCaptionsOnEncode,
                        trimStartSeconds = trimStartSeconds,
                        trimEndSeconds = trimEndSeconds,
                        splitPoints = splitPoints
                    )
                    utils.GuiCache.saveHistory(oldPath, currentCache)
                }

                _videoPath = value
                val history = utils.GuiCache.loadHistory(value)
                if (history != null) {
                    trimStartSeconds = history.trimStartSeconds ?: 0.0
                    trimEndSeconds = history.trimEndSeconds ?: 0.0
                    splitPoints = history.splitPoints ?: emptyList()
                    settings = history.settings
                    videoStartUtc = history.videoStartUtc ?: ""
                    history.timeOffsetMillis?.let { timeOffsetState.update(it) }
                } else {
                    trimStartSeconds = 0.0
                    trimEndSeconds = 0.0
                    splitPoints = emptyList()
                    videoStartUtc = ""
                    settings = settings.copy(roadCaptions = emptyList())
                }
                isGeneratingProxy = false
                proxyProgress = 0f
                proxyVideoPath = null
                
                plateCache = fit.PlateCacheManager.loadCache(value)
            }
        }

    // License Plate Detection States
    var plateCache by mutableStateOf<fit.VideoPlatesCache?>(
        initialCache?.videoPath?.let { fit.PlateCacheManager.loadCache(it) }
    )
    var isDetectingPlates by mutableStateOf(false)
    var plateDetectionProgress by mutableStateOf("")
    var plateDetectionError by mutableStateOf<String?>(null)

    private var plateDetectionJob: kotlinx.coroutines.Job? = null

    fun runPlateDetection(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        // Omitted
    }

    fun onBlurLicensePlatesChanged(enabled: Boolean, coroutineScope: kotlinx.coroutines.CoroutineScope) {
        settings = settings.copy(blurLicensePlates = false)
    }


    var outputDir by mutableStateOf(System.getProperty("user.home") + File.separator + "Downloads")
    var videoStartUtc by mutableStateOf(initialCache?.videoStartUtc ?: "")

    // Trim States
    var trimStartSeconds by mutableStateOf(initialCache?.trimStartSeconds ?: 0.0)
    var trimEndSeconds by mutableStateOf(initialCache?.trimEndSeconds ?: 0.0)

    var splitPoints by mutableStateOf<List<Double>>(initialCache?.splitPoints ?: emptyList())

    fun addSplitPoint(seconds: Double) {
        if (seconds > trimStartSeconds && seconds < trimEndSeconds && seconds !in splitPoints) {
            splitPoints = (splitPoints + seconds).sorted()
        }
    }

    fun removeSplitPoint(seconds: Double) {
        splitPoints = splitPoints.filter { it != seconds }
    }

    fun clearSplitPoints() {
        splitPoints = emptyList()
    }

    var isPreviewFullscreen by mutableStateOf(false)

    fun getSplitRanges(): List<Pair<Double, Double>> {
        val totalSec = videoLengthMs / 1000.0
        val end = if (trimEndSeconds <= 0.0 || trimEndSeconds > totalSec) totalSec else trimEndSeconds
        val start = trimStartSeconds.coerceIn(0.0, totalSec)
        val activeSplits = splitPoints.filter { it > start && it < end }.sorted()
        val ranges = mutableListOf<Pair<Double, Double>>()
        var currentStart = start
        for (split in activeSplits) {
            ranges.add(Pair(currentStart, split))
            currentStart = split
        }
        ranges.add(Pair(currentStart, end))
        return ranges
    }
    
    // Time Alignment State
    val timeOffsetState = TimeAlignmentState(
        if (initialCache != null) {
            if (initialCache.timeOffsetMillis != null) {
                initialCache.timeOffsetMillis.coerceIn(-TimeAlignmentState.MAX_OFFSET_MILLIS, TimeAlignmentState.MAX_OFFSET_MILLIS)
            } else if (initialCache.timeOffsetSeconds != null) {
                (initialCache.timeOffsetSeconds.coerceIn(-TimeAlignmentState.MAX_OFFSET_SECONDS, TimeAlignmentState.MAX_OFFSET_SECONDS) * 1000).toInt()
            } else {
                0
            }
        } else {
            0
        }
    )

    // Derived States
    val adjustedStartUtc by derivedStateOf {
        timeOffsetState.adjust(videoStartUtc)
    }

    var isAligningTelemetry by mutableStateOf(false)
    var isEncoding by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var isCanceled by mutableStateOf(false)
    var encodingSegmentStart by mutableStateOf<Double?>(null)
    var encodingSegmentEnd by mutableStateOf<Double?>(null)
    var progress by mutableStateOf(0f)
    var encodingPreviewImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var statusText by mutableStateOf("")
    var hudSettingsExpanded by mutableStateOf(false)
    var isLoaded by mutableStateOf(true)

    // Drive space monitor states
    var monitoredDriveName by mutableStateOf("C:")
    var cDriveFreeSpaceGB by mutableStateOf(0.0)
    var cDriveTotalSpaceGB by mutableStateOf(0.0)
    var requiredSpaceGB by mutableStateOf(2.0)
    var hasEnoughSpace by mutableStateOf(true)
    var hasEnoughSpaceForSample by mutableStateOf(true)
    var isSampleEncoding by mutableStateOf(false)
    var isHudBurned by mutableStateOf(false)
    var appTempSpaceGB by mutableStateOf(0.0)

    var moveOutputToSource by mutableStateOf(initialCache?.moveOutputToSource ?: false)
    var showLivePreview by mutableStateOf(initialCache?.showLivePreview ?: true)
    var previewQualityMode by mutableStateOf(initialCache?.previewQualityMode ?: "original")
    var autoDetectRoadCaptionsOnEncode by mutableStateOf(initialCache?.autoDetectRoadCaptionsOnEncode ?: false)

    // Telemetry and video metadata
    var telemetryPoints by mutableStateOf<List<FitParser.TelemetryPoint>>(emptyList())
    
    private var _videoLengthMs by mutableStateOf(0L)
    var videoLengthMs: Long
        get() = _videoLengthMs
        set(value) {
            val oldVal = _videoLengthMs
            _videoLengthMs = value
            if (oldVal == 0L && value > 0L) {
                if (trimStartSeconds == 0.0 && trimEndSeconds == 0.0) {
                    trimStartSeconds = 0.0
                    trimEndSeconds = value / 1000.0
                }
            } else if (value > 0L) {
                trimStartSeconds = 0.0
                trimEndSeconds = value / 1000.0
            }
        }

    var lastPreviewRequestId by mutableStateOf(0L)

    val fitStartInstant by derivedStateOf {
        if (telemetryPoints.isNotEmpty()) {
            try {
                java.time.Instant.ofEpochSecond(telemetryPoints.first().timestamp.toLong() + 631065600L)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val fitEndInstant by derivedStateOf {
        if (telemetryPoints.isNotEmpty()) {
            try {
                java.time.Instant.ofEpochSecond(telemetryPoints.last().timestamp.toLong() + 631065600L)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val videoStartInstant by derivedStateOf {
        try {
            if (adjustedStartUtc.isNotEmpty()) java.time.Instant.parse(adjustedStartUtc) else null
        } catch (e: Exception) {
            null
        }
    }

    val videoEndInstant by derivedStateOf {
        val start = videoStartInstant
        if (start != null && videoLengthMs > 0) {
            try {
                start.plusMillis(videoLengthMs)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val isVideoInFitRange by derivedStateOf {
        val fitStart = fitStartInstant
        val fitEnd = fitEndInstant
        val videoStart = videoStartInstant
        val videoEnd = videoEndInstant
        if (fitStart != null && fitEnd != null && videoStart != null && videoEnd != null) {
            (!videoStart.isBefore(fitStart)) && (!videoEnd.isAfter(fitEnd))
        } else {
            true
        }
    }

    val trimmedTelemetryPoints by derivedStateOf {
        val videoStart = videoStartInstant
        if (telemetryPoints.isNotEmpty() && videoStart != null) {
            try {
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val videoStartFit = videoStart.epochSecond - fitEpoch
                val trimStartFit = videoStartFit + trimStartSeconds
                val trimEndFit = videoStartFit + trimEndSeconds
                val filtered = telemetryPoints.filter { it.timestamp in trimStartFit..trimEndFit }
                if (filtered.isNotEmpty()) filtered else telemetryPoints
            } catch (e: Exception) {
                telemetryPoints
            }
        } else {
            telemetryPoints
        }
    }

    fun updateRoadCaptionStart(index: Int, startSeconds: Double) {
        val captions = settings.roadCaptions
        if (index in captions.indices) {
            val updated = captions.mapIndexed { idx, item ->
                if (idx == index) {
                    item.copy(startSeconds = startSeconds.coerceIn(0.0, item.endSeconds))
                } else item
            }
            settings = settings.copy(roadCaptions = updated)
        }
    }

    fun updateRoadCaptionEnd(index: Int, endSeconds: Double) {
        val captions = settings.roadCaptions
        if (index in captions.indices) {
            val updated = captions.mapIndexed { idx, item ->
                if (idx == index) {
                    val duration = videoLengthMs / 1000.0
                    val maxSec = if (duration <= 0.0) item.endSeconds else duration
                    item.copy(endSeconds = endSeconds.coerceIn(item.startSeconds, maxSec))
                } else item
            }
            settings = settings.copy(roadCaptions = updated)
        }
    }

    var editingCaptionIndex by mutableStateOf<Int?>(null)

    // Batch Job Queue
    val batchQueue = mutableStateListOf<BatchJob>()
    var isBatchRunning by mutableStateOf(false)
    var batchStatusText by mutableStateOf("")

    fun addToBatchQueue() {
        if (videoPath.isNotEmpty()) {
            val job = BatchJob(
                videoPath = videoPath,
                fitPath = fitPath,
                videoStartUtc = videoStartUtc,
                timeOffsetMillis = timeOffsetState.millis.toLong(),
                trimStartSeconds = trimStartSeconds,
                trimEndSeconds = trimEndSeconds,
                splitPoints = splitPoints.toList(),
                settings = settings.copy(),
                autoDetectRoadCaptionsOnEncode = autoDetectRoadCaptionsOnEncode
            )
            batchQueue.add(job)
        }
    }

    fun removeFromBatchQueue(jobId: String) {
        batchQueue.removeAll { it.id == jobId }
    }

    fun clearBatchQueue() {
        batchQueue.clear()
    }

    var isSidebarVisible by mutableStateOf(true)
}

enum class BatchJobStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED
}

data class BatchJob(
    val id: String = java.util.UUID.randomUUID().toString(),
    val videoPath: String,
    val fitPath: String,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val trimStartSeconds: Double,
    val trimEndSeconds: Double,
    val splitPoints: List<Double>,
    val settings: HudSettings,
    val autoDetectRoadCaptionsOnEncode: Boolean = false,
    var status: BatchJobStatus = BatchJobStatus.WAITING,
    var progress: Float = 0.0f,
    var errorMessage: String? = null
)
