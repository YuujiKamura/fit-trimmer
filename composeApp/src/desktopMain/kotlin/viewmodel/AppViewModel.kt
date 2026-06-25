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
    var videoPath by mutableStateOf(initialCache?.videoPath ?: "")
    var outputDir by mutableStateOf(System.getProperty("user.home") + File.separator + "Downloads")
    var videoStartUtc by mutableStateOf(initialCache?.videoStartUtc ?: "2026-06-21T02:09:49Z")
    
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
    var videoLengthMs by mutableStateOf(0L)
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
        val videoEnd = videoEndInstant
        if (telemetryPoints.isNotEmpty() && videoStart != null && videoEnd != null) {
            try {
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val videoStartFit = videoStart.epochSecond - fitEpoch
                val videoEndFit = videoEnd.epochSecond - fitEpoch
                val filtered = telemetryPoints.filter { it.timestamp in videoStartFit.toDouble()..videoEndFit.toDouble() }
                if (filtered.isNotEmpty()) filtered else telemetryPoints
            } catch (e: Exception) {
                telemetryPoints
            }
        } else {
            telemetryPoints
        }
    }
}
