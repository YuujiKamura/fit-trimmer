package viewmodel

import androidx.compose.runtime.*
import fit.HudSettings
import fit.FitParser
import java.io.File
import TimeAlignmentState

class AppViewModel(
    initialCache: utils.GuiPathCache?
) {
    // Basic States
    var settings by mutableStateOf(initialCache?.settings ?: HudSettings())
    var fitPath by mutableStateOf(initialCache?.fitPath ?: "")

    private var _videoPath by mutableStateOf(initialCache?.videoPath ?: "")
    var videoPath: String
        get() = _videoPath
        set(value) {
            if (_videoPath != value) {
                _videoPath = value
                trimStartSeconds = 0.0
                trimEndSeconds = 0.0
                splitPoints = emptyList()
            }
        }

    var outputDir by mutableStateOf(System.getProperty("user.home") + File.separator + "Downloads")
    var videoStartUtc by mutableStateOf(initialCache?.videoStartUtc ?: "2026-06-21T02:09:49Z")

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

    var isEncoding by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var isCanceled by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var encodingPreviewImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var statusText by mutableStateOf("")
    var hudSettingsExpanded by mutableStateOf(false)
    var isLoaded by mutableStateOf(true)

    // C Drive space monitor states
    var cDriveFreeSpaceGB by mutableStateOf(0.0)
    var cDriveTotalSpaceGB by mutableStateOf(0.0)
    var requiredSpaceGB by mutableStateOf(2.0)
    var hasEnoughSpace by mutableStateOf(true)
    var hasEnoughSpaceForSample by mutableStateOf(true)
    var isSampleEncoding by mutableStateOf(false)
    var appTempSpaceGB by mutableStateOf(0.0)

    var moveOutputToSource by mutableStateOf(initialCache?.moveOutputToSource ?: false)
    var showLivePreview by mutableStateOf(initialCache?.showLivePreview ?: true)

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
}
