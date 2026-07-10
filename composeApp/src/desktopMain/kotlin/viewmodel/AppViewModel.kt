package viewmodel

import fit.TelemetryPoint



import androidx.compose.runtime.*

import fit.HudSettings

import fit.FitParser

import java.io.File

import TimeAlignmentState

import kotlinx.coroutines.launch

import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import fit.PlateCacheManager
import fit.VideoPlatesCache
import utils.PlateDetectionManager
import utils.BatchJobScheduler





enum class EncodePhase {
    Idle,
    Preparing,
    Encoding,
    Merging,
    Completed,
    Failed,
    Canceled;

    val isActive: Boolean
        get() = this == Preparing || this == Encoding || this == Merging
}


class AppViewModel(

    initialCache: utils.GuiPathCache?

) {



    var composeWindow: java.awt.Window? = null



    // Basic States

    var settings by mutableStateOf(initialCache?.settings ?: HudSettings())

    var fitPath by mutableStateOf(initialCache?.fitPath ?: "")



    var isGeneratingProxy by mutableStateOf(false)

    var proxyProgress by mutableStateOf(0f)

    var proxyVideoPath by mutableStateOf<String?>(null)



    // Batch Job Queue
    val batchScheduler = BatchJobScheduler(
        onSaveBatchQueue = { saveBatchQueue() },
        onSaveCurrentHistory = { saveCurrentHistory() },
        onCreateBatchJob = { video, fit, start, tStart, tEnd, splits, duration ->
            createBatchJob(
                jobVideoPath = video,
                jobFitPath = fit,
                jobVideoStartUtc = start,
                jobTrimStartSeconds = tStart,
                jobTrimEndSeconds = tEnd,
                jobSplitPoints = splits,
                jobDurationSeconds = duration
            )
        }
    )
    val batchQueue get() = batchScheduler.batchQueue
    var isBatchRunning by mutableStateOf(false)
    var batchStatusText: String
        get() = batchScheduler.batchStatusText
        set(value) { batchScheduler.batchStatusText = value }
    var showBatchConfirmDialog: Boolean
        get() = batchScheduler.showBatchConfirmDialog
        set(value) { batchScheduler.showBatchConfirmDialog = value }

    // Restorable pending jobs from crash/interruption
    val pendingRestorableJobs = mutableStateListOf<BatchJob>()
    var showBatchRestoreDialog by mutableStateOf(false)

    var isDetectingRoads by mutableStateOf(false)

    var roadDetectionProgressText by mutableStateOf("")



    private var _videoPath by mutableStateOf(initialCache?.videoPath ?: "")

    var videoPath: String

        get() = _videoPath

        set(value) {

            if (_videoPath == value) {
                isTelemetryCut = false
                if (originalTelemetryPoints.isNotEmpty()) {
                    telemetryPoints = originalTelemetryPoints
                }
            }

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
                isTelemetryCut = false
                if (originalTelemetryPoints.isNotEmpty()) {
                    telemetryPoints = originalTelemetryPoints
                }
                refreshAvailableCacheJobs()

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

                videoRotation = utils.getVideoRotation(value)

                println("DEBUG: Loaded videoRotation: $videoRotation")

            }

        }



    // License Plate Detection States

    var videoRotation by mutableStateOf(0)

    var plateCache by mutableStateOf<fit.VideoPlatesCache?>(

        initialCache?.videoPath?.let { fit.PlateCacheManager.loadCache(it) }

    )

    var isDetectingPlates by mutableStateOf(false)

    var plateDetectionProgress by mutableStateOf("")

    var plateDetectionError by mutableStateOf<String?>(null)

    var plateDetectionMaxSpeedKmh by mutableStateOf(fit.HudSettings().plateMaxSpeedKmh)

    var plateDetectionFps by mutableStateOf(fit.HudSettings().plateDetectionFps)

    var plateDetectionPaddingSeconds by mutableStateOf(fit.HudSettings().platePaddingSeconds)

    var plateDetectionMergeGapSeconds by mutableStateOf(fit.HudSettings().plateMergeGapSeconds)



    private var plateDetectionJob: kotlinx.coroutines.Job? = null

    private var plateDetectionStopRequested by mutableStateOf(false)



    val plateRecordCount: Int

        get() = plateCache?.records?.size ?: 0



    val plateBoxCount: Int

        get() = plateCache?.records?.sumOf { it.boxes.size } ?: 0



    val plateFirstTimeMs: Long?

        get() = plateCache?.records?.firstOrNull()?.timeMs



    val plateLastTimeMs: Long?

        get() = plateCache?.records?.lastOrNull()?.timeMs



    val plateCacheFileExists: Boolean

        get() = PlateCacheManager.cacheExists(videoPath)



    val plateCacheFilePath: String

        get() = PlateCacheManager.getPlatesFile(videoPath)?.absolutePath ?: ""



    suspend fun executePlateScanCore(
        videoPath: String,
        telemetryPoints: List<fit.TelemetryPoint>,
        adjustedStartUtc: String,
        trimStart: Double,
        trimEnd: Double,
        settings: fit.HudSettings,
        onProgress: (percent: Float, status: String) -> Unit,
        onCancel: () -> Boolean,
        onPartialResult: (fit.VideoPlatesCache) -> Unit = {}
    ): fit.VideoPlatesCache? {
        // In the unified time-system, trimStart and trimEnd are always relative to the video,
        // so no offset subtraction is necessary here.
        val ranges = if (trimEnd > trimStart) {
            listOf(trimStart to trimEnd)
        } else null

        return plateDetector.detect(
            videoPath = videoPath,
            telemetryPoints = telemetryPoints,
            adjustedStartUtc = adjustedStartUtc,
            onProgress = onProgress,
            onCancel = onCancel,
            onPartialResult = onPartialResult,
            saveCache = true,
            settings = settings,
            scanRanges = ranges
        )
    }

    fun runPlateDetection(

        coroutineScope: kotlinx.coroutines.CoroutineScope,

        maxRecords: Int? = null,

        maxSpeedKmh: Double = plateDetectionMaxSpeedKmh,

        detectionFps: Double = plateDetectionFps,

        paddingSeconds: Double = plateDetectionPaddingSeconds,

        mergeGapSeconds: Double = plateDetectionMergeGapSeconds

    ) {

        val path = videoPath

        if (path.isEmpty()) return

        

        // Synchronously cancel existing job before launching any new coroutines

        if (isDetectingPlates) {

            println("DEBUG: Cancelling active plate detection job to apply updated parameters.")

            plateDetectionJob?.cancel()

        }

        

        isDetectingPlates = true

        plateDetectionStopRequested = false

        plateDetectionMaxSpeedKmh = maxSpeedKmh.coerceAtLeast(0.0)

        plateDetectionFps = detectionFps.coerceIn(0.25, 4.0)

        plateDetectionPaddingSeconds = paddingSeconds.coerceAtLeast(0.0)

        plateDetectionMergeGapSeconds = mergeGapSeconds.coerceAtLeast(0.0)

        plateDetectionProgress = "0.0%"

        plateDetectionError = null

        

        plateDetectionJob = coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {

            try {

                val cache = executePlateScanCore(
                    videoPath = path,
                    telemetryPoints = telemetryPoints,
                    adjustedStartUtc = adjustedStartUtc,
                    trimStart = trimStartSeconds,
                    trimEnd = trimEndSeconds,
                    settings = fit.HudSettings(
                        plateMaxSpeedKmh = plateDetectionMaxSpeedKmh,
                        plateDetectionFps = plateDetectionFps,
                        platePaddingSeconds = plateDetectionPaddingSeconds,
                        plateMergeGapSeconds = plateDetectionMergeGapSeconds
                    ),
                    onProgress = { progress, status ->
                        val suffix = if (telemetryPoints.isNotEmpty() && videoStartUtc.isNotEmpty()) "" else " (No Telemetry)"
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            plateDetectionProgress = String.format(java.util.Locale.US, "%.1f%%", progress) + " ($status)" + suffix
                        }
                    },
                    onCancel = { plateDetectionStopRequested || !isActive },
                    onPartialResult = { partialCache ->
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            plateCache = partialCache
                        }
                    }
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {

                    if (cache != null) {

                        plateCache = cache

                        if (plateDetectionStopRequested) {

                            plateDetectionProgress = "Stopped"

                        }

                    } else {

                        if (isActive) {

                            plateDetectionError = utils.Localizer.get("plate_error_unknown", settings.language)

                            plateDetectionProgress = "Failed"

                        } else {

                            plateDetectionProgress = "Canceled"

                        }

                    }

                }

            } catch (e: Exception) {

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {

                    if (e is kotlinx.coroutines.CancellationException) {

                        plateDetectionProgress = "Canceled"

                    } else {

                        e.printStackTrace()

                        plateDetectionError = e.message ?: "Unknown error"

                        plateDetectionProgress = "Error: ${e.message}"

                    }

                }

            } finally {

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {

                    isDetectingPlates = false

                    plateDetectionJob = null

                }

            }

        }

    }



    fun onBlurLicensePlatesChanged(enabled: Boolean, coroutineScope: kotlinx.coroutines.CoroutineScope) {
        settings = settings.copy(blurLicensePlates = enabled)
        if (enabled) {
            if (plateCache == null && videoPath.isNotEmpty()) {
                if (fit.PlateCacheManager.cacheExists(videoPath)) {
                    plateCache = fit.PlateCacheManager.loadCache(videoPath)
                    plateDetectionProgress = "Restored"
                } else {
                    plateDetectionProgress = "Not Scanned"
                }
            }
        } else {
            println("DEBUG: Blur settings disabled. Cancelling active plate detection job.")
            plateDetectionJob?.cancel()
            isDetectingPlates = false
            plateDetectionProgress = "Canceled"
        }
    }
    
    fun onDetectPedestriansChanged(enabled: Boolean, coroutineScope: kotlinx.coroutines.CoroutineScope) {
        val oldDetectPedestrians = settings.detectPedestrians
        settings = settings.copy(detectPedestrians = enabled)
        if (oldDetectPedestrians != enabled && settings.blurLicensePlates && videoPath.isNotEmpty()) {
            println("DEBUG: detectPedestrians changed from $oldDetectPedestrians to $enabled. Resetting plate detection cache for re-scan.")
            resetPlateDetection()
        }
    }

    fun resetPlateDetection() {

        println("DEBUG: Resetting plate detection cache and state.")

        plateDetectionStopRequested = false

        plateDetectionJob?.cancel()

        isDetectingPlates = false

        plateCache = null

        plateDetectionProgress = ""

        plateDetectionError = null

        PlateCacheManager.deleteCache(videoPath)

    }



    var availableCacheJobs by androidx.compose.runtime.mutableStateOf<List<fit.CacheJob>>(emptyList())
    var lastPromptedJobHash by androidx.compose.runtime.mutableStateOf("")

    var isSalvaging by androidx.compose.runtime.mutableStateOf(false)

    var salvageProgress by androidx.compose.runtime.mutableStateOf(0f)

    var salvageStatusText by androidx.compose.runtime.mutableStateOf("")



    fun refreshAvailableCacheJobs() {

        if (videoPath.isNotEmpty()) {

            availableCacheJobs = fit.CacheJobManager.getInstance().scanJobs(videoPath)

        } else {

            availableCacheJobs = emptyList()

        }

    }



    fun runSalvage(

        jobInfo: fit.CacheJob,

        outputPath: String,

        coroutineScope: kotlinx.coroutines.CoroutineScope,

        onComplete: (String) -> Unit

    ) {

        if (isSalvaging) return

        isSalvaging = true

        salvageProgress = 0.1f

        salvageStatusText = "Initializing Salvage..."

        

        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {

            try {

                jobInfo.salvageAndMerge(
                    outputFile = File(outputPath),
                    onProgress = { prog, status ->
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            salvageProgress = prog
                            salvageStatusText = status
                        }
                    }
                )

                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {

                    refreshAvailableCacheJobs()

                    onComplete("✨ Salvaged video saved to: $outputPath")

                }

            } catch (e: Exception) {

                e.printStackTrace()

                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {

                    salvageStatusText = "❌ Error: ${e.message}"

                }

            } finally {

                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {

                    isSalvaging = false

                }

            }

        }

    }

    fun deleteCacheJob(jobInfo: fit.CacheJob) {
        jobInfo.delete()
        refreshAvailableCacheJobs()
    }

    fun clearAllCaches() {
        if (videoPath.isNotEmpty()) {
            fit.CacheJobManager.getInstance().clearAll(videoPath)
            refreshAvailableCacheJobs()
        }
    }




    fun stopPlateDetection() {

        if (isDetectingPlates) {

            println("DEBUG: Requesting plate detection stop while preserving partial results.")

            plateDetectionStopRequested = true

            plateDetectionProgress = "Stopping..."

        }

    }



    fun setPlateDetectionPreset(maxSpeedKmh: Double, detectionFps: Double, paddingSeconds: Double = 2.0, mergeGapSeconds: Double = 5.0) {

        plateDetectionMaxSpeedKmh = maxSpeedKmh.coerceAtLeast(0.0)

        plateDetectionFps = detectionFps.coerceIn(0.25, 4.0)

        plateDetectionPaddingSeconds = paddingSeconds.coerceAtLeast(0.0)

        plateDetectionMergeGapSeconds = mergeGapSeconds.coerceAtLeast(0.0)

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

    val timeSynchronizer: fit.TimeSynchronizer = fit.TimeSynchronizerImpl(
        videoStartEpochSecProvider = { videoStartInstant?.epochSecond },
        offsetMillisProvider = { timeOffsetState.millis.toLong() }
    )

    var plateDetector: fit.PlateDetector = PlateDetectionManager

    // Derived States

    val adjustedStartUtc by derivedStateOf {

        timeOffsetState.adjust(videoStartUtc)

    }



    var isAligningTelemetry by mutableStateOf(false)

    var syncAnchorSec by mutableStateOf<Double?>(null)

    var syncCorrelation by mutableStateOf<Double?>(null)

    var syncCandidates by mutableStateOf<List<utils.TelemetryAligner.AlignmentCandidate>>(emptyList())

    var imuTimeOffsetMs by mutableStateOf<Long?>(null)

    var encodePhase by mutableStateOf(EncodePhase.Idle)

    var isEncoding: Boolean
        get() = encodePhase.isActive
        set(value) {
            encodePhase = if (value) EncodePhase.Encoding else EncodePhase.Idle
        }

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



    var isHudBurned by mutableStateOf(false)

    var appTempSpaceGB by mutableStateOf(0.0)

    fun beginEncoding() {
        encodingPreviewImage = null
        isCanceled = false
        progress = 0f
        statusText = "Preparing encode..."
        encodePhase = EncodePhase.Preparing
    }

    fun updateEncodingProgress(prog: Float, status: String) {
        progress = prog
        statusText = status
        encodePhase = if (status.contains("Merging", ignoreCase = true)) {
            EncodePhase.Merging
        } else {
            EncodePhase.Encoding
        }
    }

    fun completeEncoding(message: String) {
        progress = 1f
        statusText = message
        isCanceled = false
        encodingSegmentStart = null
        encodingSegmentEnd = null
        encodePhase = EncodePhase.Completed
    }

    fun failEncoding(message: String) {
        statusText = message
        encodingSegmentStart = null
        encodingSegmentEnd = null
        encodePhase = EncodePhase.Failed
    }

    fun cancelEncoding(message: String = "Encoding Canceled") {
        statusText = message
        isCanceled = true
        encodingSegmentStart = null
        encodingSegmentEnd = null
        encodePhase = EncodePhase.Canceled
    }



    var moveOutputToSource by mutableStateOf(initialCache?.moveOutputToSource ?: false)

    var showLivePreview by mutableStateOf(initialCache?.showLivePreview ?: true)

    var previewQualityMode by mutableStateOf(initialCache?.previewQualityMode ?: "original")

    var autoDetectRoadCaptionsOnEncode by mutableStateOf(initialCache?.autoDetectRoadCaptionsOnEncode ?: false)



    // Telemetry and video metadata

    var telemetryPoints by mutableStateOf<List<TelemetryPoint>>(emptyList())

    var isTelemetryCut by mutableStateOf(false)
    var originalTelemetryPoints by mutableStateOf<List<TelemetryPoint>>(emptyList())

    fun updateTelemetry(points: List<TelemetryPoint>) {
        originalTelemetryPoints = points
        telemetryPoints = points
        isTelemetryCut = false
    }

    // Auto Segment Detection States
    val detectedSegments = mutableStateListOf<fit.AutoDetectedSegment>()
    var isDetectingSegments by mutableStateOf(false)
    var segmentDetectionProgressText by mutableStateOf("")
    var minSegmentDistanceMeters by mutableStateOf(1000.0)
    var autoPauseGapSeconds by mutableStateOf(3.0)
    var minSearchGrade by mutableStateOf(0.0)
    var maxSearchGrade by mutableStateOf(15.0)

    fun startSegmentDetection(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        if (telemetryPoints.isEmpty()) {
            segmentDetectionProgressText = "No telemetry loaded."
            return
        }

        isDetectingSegments = true
        segmentDetectionProgressText = "Calculating bounding box..."
        detectedSegments.clear()

        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Calculate BBox from telemetry points
                val lats = telemetryPoints.map { it.lat }
                val lons = telemetryPoints.map { it.lon }
                val south = lats.minOrNull() ?: 0.0
                val west = lons.minOrNull() ?: 0.0
                val north = lats.maxOrNull() ?: 0.0
                val east = lons.maxOrNull() ?: 0.0

                val bbox = fit.BBox(south = south, west = west, north = north, east = east)

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    segmentDetectionProgressText = "Fetching signals from OpenStreetMap..."
                }

                val requester = fit.JavaHttpRequester()
                val cache = fit.FileSignalCache()
                val detector = fit.OsmTrafficSignalDetector(requester, cache)

                val result = detector.detectSegments(
                    bbox = bbox, 
                    telemetryPoints = telemetryPoints, 
                    minDistanceMeters = minSegmentDistanceMeters,
                    videoPath = videoPath,
                    autoPauseGapSeconds = autoPauseGapSeconds,
                    minSearchGrade = minSearchGrade,
                    maxSearchGrade = maxSearchGrade
                )

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    detectedSegments.addAll(result)
                    isDetectingSegments = false
                    segmentDetectionProgressText = if (result.isEmpty()) {
                        "No climb segments found (no continuous ${minSegmentDistanceMeters.toInt()}m+ climbs without traffic signals/stops)."
                    } else {
                        "Successfully detected ${result.size} climb segment(s)."
                    }
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isDetectingSegments = false
                    segmentDetectionProgressText = "Error: ${e.message}"
                }
            }
        }
    }

    fun cutTelemetry(trimStartSec: Double, trimEndSec: Double, videoStartUtcStr: String) {
        if (originalTelemetryPoints.isEmpty()) return
        try {
            val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
            val firstPoint = originalTelemetryPoints.firstOrNull() ?: return
            val fitStartUtcEpoch = firstPoint.timestamp + fitEpoch
            
            val cutStartEpoch = fitStartUtcEpoch + trimStartSec
            val cutEndEpoch = fitStartUtcEpoch + trimEndSec
            
            val filtered = originalTelemetryPoints.filter { pt ->
                val ptEpoch = pt.timestamp + fitEpoch
                ptEpoch in cutStartEpoch..cutEndEpoch
            }
            
            telemetryPoints = filtered
            isTelemetryCut = true
            
            if (videoStartUtcStr.isNotEmpty()) {
                val videoInstant = java.time.Instant.parse(videoStartUtcStr)
                val cutStartInstant = java.time.Instant.ofEpochSecond(cutStartEpoch.toLong())
                val diffMs = cutStartInstant.toEpochMilli() - videoInstant.toEpochMilli()
                timeOffsetState.update(diffMs.toInt())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun confirmTelemetryRangeForVideo(alignedVideoStartUtcStr: String, videoDurationSec: Double) {
        if (originalTelemetryPoints.isEmpty() || alignedVideoStartUtcStr.isEmpty() || videoDurationSec <= 0.0) return
        try {
            val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond.toDouble()
            val alignedVideoStart = java.time.Instant.parse(alignedVideoStartUtcStr)
            val cutStartFit = alignedVideoStart.toEpochMilli() / 1000.0 - fitEpoch
            val cutEndFit = cutStartFit + videoDurationSec

            telemetryPoints = originalTelemetryPoints.filter { pt ->
                pt.timestamp in cutStartFit..cutEndFit
            }
            isTelemetryCut = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resetTelemetryCut() {
        if (originalTelemetryPoints.isEmpty()) return
        telemetryPoints = originalTelemetryPoints
        isTelemetryCut = false
    }

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



    init {
        refreshAvailableCacheJobs()
        try {
            val savedJobs = utils.BatchQueueCache.load()
            val unfinished = savedJobs.filter {
                val statusEnum = try { BatchJobStatus.valueOf(it.status) } catch(_: Exception) { BatchJobStatus.WAITING }
                statusEnum == BatchJobStatus.WAITING || statusEnum == BatchJobStatus.FAILED || statusEnum == BatchJobStatus.RUNNING
            }
            if (unfinished.isNotEmpty()) {
                val restored = unfinished.map {
                    val statusEnum = try { BatchJobStatus.valueOf(it.status) } catch(_: Exception) { BatchJobStatus.WAITING }
                    val finalStatus = if (statusEnum == BatchJobStatus.RUNNING) BatchJobStatus.WAITING else statusEnum
                    
                    val phases = it.phases?.map { p ->
                        val phaseType = try { BatchJobPhaseType.valueOf(p.type) } catch(_: Exception) { BatchJobPhaseType.HUD_ENCODE }
                        val phaseStatus = try { BatchJobPhaseStatus.valueOf(p.status) } catch(_: Exception) { BatchJobPhaseStatus.WAITING }
                        val finalPhaseStatus = if (phaseStatus == BatchJobPhaseStatus.RUNNING) BatchJobPhaseStatus.WAITING else phaseStatus
                        BatchJobPhase(
                            type = phaseType,
                            initialEnabled = p.enabled,
                            initialStatus = finalPhaseStatus,
                            initialProgress = p.progress
                        )
                    } ?: run {
                        if (it.fitPath.isEmpty()) {
                            listOf(BatchJobPhase(BatchJobPhaseType.FAST_TRIM, initialEnabled = true))
                        } else {
                            listOf(
                                BatchJobPhase(BatchJobPhaseType.PLATE_SCAN, initialEnabled = it.settings.blurLicensePlates),
                                BatchJobPhase(BatchJobPhaseType.HUD_ENCODE, initialEnabled = true),
                                BatchJobPhase(BatchJobPhaseType.CONCAT_MERGE, initialEnabled = true)
                            )
                        }
                    }
                    
                    BatchJob(
                        id = it.id,
                        videoPath = it.videoPath,
                        fitPath = it.fitPath,
                        videoStartUtc = it.videoStartUtc,
                        timeOffsetMillis = it.timeOffsetMillis,
                        trimStartSeconds = it.trimStartSeconds,
                        trimEndSeconds = it.trimEndSeconds,
                        splitPoints = it.splitPoints,
                        initialSettings = it.settings,
                        initialAutoDetectRoadCaptionsOnEncode = it.autoDetectRoadCaptionsOnEncode,
                        initialOutputFileNames = it.outputFileNames,
                        initialStatus = finalStatus,
                        initialProgress = it.progress,
                        initialErrorMessage = it.errorMessage,
                        initialPhases = phases
                    )
                }
                pendingRestorableJobs.addAll(restored)
                showBatchRestoreDialog = true
                logBatch("Detected ${restored.size} unfinished batch jobs for restoration")
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun logBatch(message: String) {
        println("BATCH: $message")
    }

    fun logBatchQueueSnapshot(context: String) {
        batchScheduler.logBatchQueueSnapshot(context)
    }

    fun requestBatchConfirmDialog(source: String): Boolean {
        return batchScheduler.requestBatchConfirmDialog(source)
    }

    fun dismissBatchConfirmDialog(source: String) {
        batchScheduler.dismissBatchConfirmDialog(source, isBatchRunning)
    }

    fun prepareBatchQueueForStart() {
        batchScheduler.prepareBatchQueueForStart()
    }

    fun addToBatchQueue() {
        if (videoPath.isNotEmpty()) {
            batchScheduler.addToQueue(
                videoPath = videoPath,
                fitPath = fitPath,
                videoStartUtc = videoStartUtc,
                trimStartSeconds = trimStartSeconds,
                trimEndSeconds = trimEndSeconds,
                splitPoints = splitPoints.toList(),
                videoLengthMs = videoLengthMs
            )
        } else {
            logBatch("add skipped: videoPath is empty")
        }
    }

    private fun createBatchJob(
        jobVideoPath: String,
        jobFitPath: String,
        jobVideoStartUtc: String,
        jobTrimStartSeconds: Double,
        jobTrimEndSeconds: Double,
        jobSplitPoints: List<Double>,
        jobDurationSeconds: Double?,
        jobSettings: HudSettings = settings.copy(),
        jobAutoDetectRoadCaptionsOnEncode: Boolean = autoDetectRoadCaptionsOnEncode
    ): BatchJob {
        val phases = if (jobFitPath.isEmpty()) {
            listOf(
                BatchJobPhase(BatchJobPhaseType.FAST_TRIM, initialEnabled = true)
            )
        } else {
            listOf(
                BatchJobPhase(BatchJobPhaseType.PLATE_SCAN, initialEnabled = jobSettings.blurLicensePlates),
                BatchJobPhase(BatchJobPhaseType.ROAD_SCAN, initialEnabled = jobAutoDetectRoadCaptionsOnEncode),
                BatchJobPhase(BatchJobPhaseType.HUD_ENCODE, initialEnabled = true),
                BatchJobPhase(BatchJobPhaseType.CONCAT_MERGE, initialEnabled = true)
            )
        }
        return BatchJob(
            videoPath = jobVideoPath,
            fitPath = jobFitPath,
            videoStartUtc = jobVideoStartUtc,
            timeOffsetMillis = timeOffsetState.millis.toLong(),
            trimStartSeconds = jobTrimStartSeconds,
            trimEndSeconds = jobTrimEndSeconds,
            splitPoints = jobSplitPoints,
            initialSettings = jobSettings,
            initialAutoDetectRoadCaptionsOnEncode = jobAutoDetectRoadCaptionsOnEncode,
            initialPhases = phases,
            initialOutputFileNames = buildQueuedOutputFileNamesFor(
                jobSettings = jobSettings,
                jobVideoPath = jobVideoPath,
                jobVideoStartUtc = jobVideoStartUtc,
                jobTrimStartSeconds = jobTrimStartSeconds,
                jobTrimEndSeconds = jobTrimEndSeconds,
                jobSplitPoints = jobSplitPoints,
                jobDurationSeconds = jobDurationSeconds
            )
        )
    }

    private fun buildQueuedOutputFileNamesFor(
        jobSettings: HudSettings,
        jobVideoPath: String,
        jobVideoStartUtc: String,
        jobTrimStartSeconds: Double,
        jobTrimEndSeconds: Double,
        jobSplitPoints: List<Double>,
        jobDurationSeconds: Double?
    ): List<String> {
        val includeTrim = hasTrimmedRangeForFileName(jobTrimStartSeconds, jobTrimEndSeconds, jobDurationSeconds)
        val ranges = buildQueuedRangesFor(jobTrimStartSeconds, jobTrimEndSeconds, jobSplitPoints)
        return ranges.mapIndexed { idx, (start, end) ->
            fit.HudFileNameFormatter.buildEncodeOutputFileName(
                settings = jobSettings,
                videoPath = jobVideoPath,
                partIndex = if (ranges.size > 1) idx else -1,
                numParts = ranges.size,
                trimStartSeconds = if (includeTrim) start else null,
                trimEndSeconds = if (includeTrim) end else null,
                dateTag = if (includeTrim) buildDateTagFromUtc(jobVideoStartUtc) else null
            )
        }
    }

    private fun buildQueuedRanges(): List<Pair<Double, Double>> {
        return buildQueuedRangesFor(trimStartSeconds, trimEndSeconds, splitPoints.toList())
    }

    private fun buildQueuedRangesFor(
        startSeconds: Double,
        endSeconds: Double,
        jobSplitPoints: List<Double>
    ): List<Pair<Double, Double>> {
        val activeSplits = jobSplitPoints.filter { it > startSeconds && it < endSeconds }.sorted()
        val ranges = mutableListOf<Pair<Double, Double>>()
        var currentStart = startSeconds
        for (split in activeSplits) {
            ranges.add(Pair(currentStart, split))
            currentStart = split
        }
        ranges.add(Pair(currentStart, endSeconds))
        return ranges
    }

    private fun hasTrimmedRangeForFileName(startSeconds: Double, endSeconds: Double, durationSeconds: Double?): Boolean {
        val epsilon = 0.01
        if (startSeconds > epsilon) return true
        if (durationSeconds == null || durationSeconds <= 0.0) return endSeconds > epsilon
        return endSeconds > epsilon && endSeconds < durationSeconds - epsilon
    }

    fun saveCurrentHistory() {
        val path = videoPath
        if (path.isNotEmpty()) {
            val currentCache = utils.GuiPathCache(
                fitPath = fitPath,
                videoPath = path,
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
            utils.GuiCache.saveHistory(path, currentCache)
        }
    }

    fun saveBatchQueue() {
        val serializedJobs = batchQueue.map {
            utils.SerializedBatchJob(
                id = it.id,
                videoPath = it.videoPath,
                fitPath = it.fitPath,
                videoStartUtc = it.videoStartUtc,
                timeOffsetMillis = it.timeOffsetMillis,
                trimStartSeconds = it.trimStartSeconds,
                trimEndSeconds = it.trimEndSeconds,
                splitPoints = it.splitPoints,
                settings = it.settings.copy(),
                autoDetectRoadCaptionsOnEncode = it.autoDetectRoadCaptionsOnEncode,
                outputFileNames = it.outputFileNames,
                status = it.status.name,
                progress = it.progress,
                errorMessage = it.errorMessage,
                phases = it.phases.map { phase ->
                    utils.SerializedBatchJobPhase(
                        type = phase.type.name,
                        enabled = phase.enabled,
                        status = phase.status.name,
                        progress = phase.progress
                    )
                }
            )
        }
        utils.BatchQueueCache.save(serializedJobs)
    }

    fun restorePendingBatchJobs(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        if (pendingRestorableJobs.isNotEmpty()) {
            val toRestore = pendingRestorableJobs.toList()
            pendingRestorableJobs.clear()
            showBatchRestoreDialog = false

            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                toRestore.forEach { job ->
                    val cacheJobs = fit.CacheJobManager.getInstance().scanJobs(job.videoPath)
                    if (cacheJobs.isNotEmpty()) {
                        val cacheJob = cacheJobs.first()
                        val salvageOutFile = fit.CacheJobManager.getInstance().getSalvageOutputPath(
                            videoPath = job.videoPath,
                            outputDir = outputDir,
                            settings = job.settings
                        )

                        try {
                            logBatch("Auto-salvaging job ${job.id} from cache ${cacheJob.jobHash} to ${salvageOutFile.absolutePath}")
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                isSalvaging = true
                                salvageProgress = 0.1f
                                salvageStatusText = "復元中 (${job.entryName})..."
                            }

                            cacheJob.salvageAndMerge(salvageOutFile) { prog, status ->
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    salvageProgress = prog
                                    salvageStatusText = "${job.entryName} を結合中: ${(prog * 100).toInt()}%"
                                }
                            }

                            logBatch("Auto-salvage completed successfully for job ${job.id}")
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                isSalvaging = false
                                salvageStatusText = "✨ ${job.entryName} を復元しました"
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            logBatch("Auto-salvage failed for job ${job.id}, restoring to queue: ${e.message}")
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                isSalvaging = false
                                salvageStatusText = "❌ 自動復旧失敗: ${e.message}"
                                batchQueue.add(job)
                                saveBatchQueue()
                            }
                        }
                    } else {
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            batchQueue.add(job)
                            saveBatchQueue()
                        }
                    }
                }

                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    refreshAvailableCacheJobs()
                }
            }
        } else {
            showBatchRestoreDialog = false
        }
    }

    fun discardPendingBatchJobs() {
        pendingRestorableJobs.clear()
        utils.BatchQueueCache.save(emptyList()) // Clear disk cache
        showBatchRestoreDialog = false
    }

    private fun buildDateTagFromUtc(utc: String): String? {
        val date = utc.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return null
        return date.replace("-", "")
    }

    var batchFolderPath by mutableStateOf("F:\\Insta360\\20260702")
    var batchFolderStatusText: String
        get() = batchScheduler.batchFolderStatusText
        set(value) { batchScheduler.batchFolderStatusText = value }
    var isBatchFolderLoading: Boolean
        get() = batchScheduler.isBatchFolderLoading
        set(value) { batchScheduler.isBatchFolderLoading = value }

    fun loadBatchFolderAndConfirm(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        if (isBatchFolderLoading) return
        batchScheduler.batchFolderStatusText = "フォルダを読み込み中..."
        batchScheduler.loadBatchFolder(
            folderPath = batchFolderPath,
            settings = settings,
            autoDetectRoadCaptions = autoDetectRoadCaptionsOnEncode,
            timeOffsetMillis = timeOffsetState.millis.toLong(),
            coroutineScope = coroutineScope
        )
    }

    fun setBatchJobRoadCaptionDetection(jobId: String, enabled: Boolean) {
        batchScheduler.setBatchJobRoadCaptionDetection(jobId, enabled)
    }

    fun setBatchJobPlateMasking(jobId: String, enabled: Boolean) {
        batchScheduler.setBatchJobPlateMasking(jobId, enabled)
    }

    fun removeFromBatchQueue(jobId: String) {
        batchScheduler.removeFromBatchQueue(jobId)
    }

    fun moveBatchJobUp(jobId: String) {
        batchScheduler.moveBatchJobUp(jobId)
    }

    fun moveBatchJobDown(jobId: String) {
        batchScheduler.moveBatchJobDown(jobId)
    }

    fun renameBatchJobEntry(jobId: String, newEntryName: String) {
        batchScheduler.renameBatchJobEntry(jobId, newEntryName)
    }

    fun clearBatchQueue() {
        batchScheduler.clearQueue()
    }



    var isSidebarVisible by mutableStateOf(true)

}





