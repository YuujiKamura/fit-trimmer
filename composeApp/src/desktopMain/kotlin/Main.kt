import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.vinceglb.filekit.PlatformFile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import fit.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
// VLC dependency removed

import utils.*
import components.*
import viewmodel.*

private const val PLAYBACK_PREVIEW_INTERVAL_MS = 250L

@OptIn(ExperimentalTextApi::class)
fun main(args: Array<String>) {
    System.setProperty("compose.interop.blending", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    if (args.contains("--auto-sample")) {
        startGui(args)
        return
    }
    if (args.isNotEmpty()) {
        runCli(args)
        return
    }
    startGui(emptyArray())
}

fun showSystemNotification(title: String, message: String) {
    if (!java.awt.SystemTray.isSupported()) return
    try {
        val tray = java.awt.SystemTray.getSystemTray()
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val trayIcon = java.awt.TrayIcon(image, "HUD エンコーダー")
        trayIcon.isImageAutoSize = true
        tray.add(trayIcon)
        trayIcon.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO)
        
        val timer = java.util.Timer()
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                try {
                    tray.remove(trayIcon)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, 10000)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@OptIn(ExperimentalTextApi::class)
fun startGui(args: Array<String>) = application {
    val initialCache = remember { GuiCache.load() }

    val windowState = rememberWindowState(
        position = if (initialCache?.windowX != null && initialCache.windowY != null) {
            androidx.compose.ui.window.WindowPosition(initialCache.windowX.dp, initialCache.windowY.dp)
        } else {
            androidx.compose.ui.window.WindowPosition.PlatformDefault
        },
        size = androidx.compose.ui.unit.DpSize(
            width = (initialCache?.windowWidth ?: 1300f).coerceIn(800f, 1400f).dp,
            height = (initialCache?.windowHeight ?: 750f).coerceIn(500f, 780f).dp
        )
    )


    val viewModel = remember { AppViewModel(initialCache) }
    
    var settings by viewModel::settings
    var fitPath by viewModel::fitPath
    var videoPath by viewModel::videoPath
    var outputDir by viewModel::outputDir
    var videoStartUtc by viewModel::videoStartUtc
    val timeOffsetState = viewModel.timeOffsetState
    val adjustedStartUtc by viewModel::adjustedStartUtc
    var isEncoding by viewModel::isEncoding
    var isPaused by viewModel::isPaused
    var isCanceled by viewModel::isCanceled
    var progress by viewModel::progress
    var encodingPreviewImage by viewModel::encodingPreviewImage
    var statusText by viewModel::statusText
    var hudSettingsExpanded by viewModel::hudSettingsExpanded
    var isLoaded by viewModel::isLoaded

    var monitoredDriveName by viewModel::monitoredDriveName
    var isHudBurned by viewModel::isHudBurned
    var cDriveFreeSpaceGB by viewModel::cDriveFreeSpaceGB
    var cDriveTotalSpaceGB by viewModel::cDriveTotalSpaceGB
    var requiredSpaceGB by viewModel::requiredSpaceGB
    var hasEnoughSpace by viewModel::hasEnoughSpace
    var hasEnoughSpaceForSample by viewModel::hasEnoughSpaceForSample
    var isSampleEncoding by viewModel::isSampleEncoding
    var appTempSpaceGB by viewModel::appTempSpaceGB
    var isDetectingRoads by remember { mutableStateOf(false) }
    var roadDetectionStatus by remember { mutableStateOf("") }
    var roadDetectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val playerState = rememberVideoPlayerState()
    var composeWindow: java.awt.Window? by remember { mutableStateOf(null) }
    var ignoreNextStartUtcClear by remember { mutableStateOf(false) }

    // Dynamic Hud Proxy & Hot Reload State
    val hudConfig = remember(settings) {
        fit.HudConfig(
            valSize = settings.valSize,
            tightness = settings.tightness,
            spacing = settings.spacing,
            xOffset = settings.xOffset,
            yOffset = settings.yOffset,
            graphH = settings.graphH,
            graphW = settings.graphW,
            roadCaptions = settings.roadCaptions
        )
    }
    
    var reloadTrigger by remember { mutableStateOf(0) }
    val rendererProxy = remember(reloadTrigger) { fit.DynamicRendererProxy(hudConfig) }
    LaunchedEffect(hudConfig) {
        rendererProxy.updateConfig(hudConfig)
    }
    var isCompiling by remember { mutableStateOf(false) }
    
    var lastClassModified by remember { mutableStateOf(0L) }
    LaunchedEffect(rendererProxy) {
        withContext(Dispatchers.IO) {
            var projectDir = File(System.getProperty("user.dir"))
            if (!File(projectDir, "shared-core").exists() && File(projectDir.parentFile, "shared-core").exists()) {
                projectDir = projectDir.parentFile
            }
            val classFile = File(projectDir, "shared-core/build/classes/kotlin/desktop/main/fit/HudRenderer.class")
            while (true) {
                if (!isCompiling && classFile.exists()) {
                    val m = classFile.lastModified()
                    if (m != lastClassModified) {
                        if (lastClassModified != 0L) {
                            println("🔄 Class file change detected, reloading HudRenderer...")
                            val ok = rendererProxy.reload()
                            if (ok) {
                                javax.swing.SwingUtilities.invokeLater {
                                    reloadTrigger++
                                }
                            }
                        }
                        lastClassModified = m
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    // Output Move State
    var moveOutputToSource by viewModel::moveOutputToSource
    var showLivePreview by viewModel::showLivePreview

    var telemetryPoints by viewModel::telemetryPoints
    var videoLengthMs by viewModel::videoLengthMs
    var lastPreviewRequestId by viewModel::lastPreviewRequestId

    val fitStartInstant by viewModel::fitStartInstant
    val fitEndInstant by viewModel::fitEndInstant
    val videoStartInstant by viewModel::videoStartInstant
    val videoEndInstant by viewModel::videoEndInstant
    val isVideoInFitRange by viewModel::isVideoInFitRange
    val trimmedTelemetryPoints by viewModel::trimmedTelemetryPoints
    
    var trimStartSeconds by viewModel::trimStartSeconds
    var trimEndSeconds by viewModel::trimEndSeconds
    var videoCurrentTimeMs by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Auto-cleanup temporary workspaces on startup
            try {
                val hudDir = fit.PathResolver.getTmpHudDir()
                if (hudDir.exists()) hudDir.deleteRecursively()
                
                // Clean up only orphaned tmp files in temp_work, keeping job directories intact for crash recovery
                val workDir = fit.PathResolver.getTempWorkDir()
                if (workDir.exists()) {
                    workDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".tmp")) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Estimates the required disk space in GiB for re-encoding the video.
     *
     * Safety multiplier factor is set to 3.0:
     * 1. CRF 18 high-quality encoding rarely expands compressed high-bitrate videos, but we assume a small margin.
     * 2. During the final segment concat merge, the temporary .ts chunks and the final output video
     *    co-exist on the disk temporarily, adding another 2.0x size footprint.
     * 3. A safety multiplier of 3.0 (plus an extra safety buffer of 2.0 GiB) is fully sufficient.
     */
    fun estimateRequiredSpaceGiB(sizeBytes: Long): Double {
        val safetyMultiplier = 3.0
        val bufferGiB = 2.0
        val gibBytes = 1024.0 * 1024.0 * 1024.0
        return (sizeBytes * safetyMultiplier) / gibBytes + bufferGiB
    }

    val sampleRequiredSpaceGB = remember(videoPath, videoLengthMs) {
        val f = File(videoPath)
        if (f.exists() && videoLengthMs > 0) {
            val ratio = minOf(1.0, 5.0 / (videoLengthMs / 1000.0))
            estimateRequiredSpaceGiB((f.length() * ratio).toLong())
        } else {
            2.0
        }
    }

    LaunchedEffect(videoPath, videoLengthMs, trimStartSeconds, trimEndSeconds, viewModel.splitPoints, viewModel.encodingSegmentStart, viewModel.encodingSegmentEnd) {
        if (videoPath.isNotEmpty()) {
            val f = File(videoPath)
            if (f.exists()) {
                val totalLengthSec = videoLengthMs / 1000.0
                val currentDuration = if (viewModel.isEncoding && viewModel.encodingSegmentStart != null && viewModel.encodingSegmentEnd != null) {
                    (viewModel.encodingSegmentEnd!! - viewModel.encodingSegmentStart!!).coerceAtLeast(0.0)
                } else {
                    val ranges = viewModel.getSplitRanges()
                    if (ranges.isNotEmpty()) {
                        ranges.maxOf { it.second - it.first }
                    } else {
                        val trimEnd = if (trimEndSeconds <= 0.0 || trimEndSeconds > totalLengthSec) totalLengthSec else trimEndSeconds
                        (trimEnd - trimStartSeconds).coerceAtLeast(0.0)
                    }
                }
                
                val ratio = if (totalLengthSec > 0.0) {
                    (currentDuration / totalLengthSec).coerceIn(0.0, 1.0)
                } else {
                    1.0
                }
                requiredSpaceGB = estimateRequiredSpaceGiB((f.length() * ratio).toLong())
            } else {
                requiredSpaceGB = 2.0
            }
        } else {
            requiredSpaceGB = 2.0
        }
    }

    LaunchedEffect(requiredSpaceGB, sampleRequiredSpaceGB, isEncoding, isPaused, isSampleEncoding, videoPath) {
        launch(Dispatchers.IO) {
            while (true) {
                try {
                    val workDir = fit.PathResolver.getTempWorkDir(videoPath)
                    val file = if (workDir.exists() || workDir.mkdirs()) {
                        workDir
                    } else {
                        val isWindows = System.getProperty("os.name").lowercase().contains("win")
                        if (isWindows) File("C:\\") else File("/")
                    }
                    val isWindows = System.getProperty("os.name").lowercase().contains("win")
                    val driveName = if (isWindows) {
                        try {
                            val canonical = file.canonicalFile
                            val root = canonical.path.split(File.separator).firstOrNull() ?: ""
                            if (root.isNotEmpty()) root.uppercase() else "C:"
                        } catch (e: Exception) {
                            "C:"
                        }
                    } else {
                        "SYSTEM"
                    }
                    monitoredDriveName = driveName
                    cDriveFreeSpaceGB = file.freeSpace / (1024.0 * 1024.0 * 1024.0)
                    cDriveTotalSpaceGB = file.totalSpace / (1024.0 * 1024.0 * 1024.0)
                    
                    // Measure size of only the current active job directory directly from the encoding layer
                    var totalBytes = 0L
                    if (isEncoding) {
                        val jobDir = fit.globalActiveJobDir
                        if (jobDir != null && jobDir.exists()) {
                            fun getDirSize(dir: File): Long {
                                var size = 0L
                                dir.listFiles()?.forEach { f ->
                                    size += if (f.isDirectory) getDirSize(f) else f.length()
                                }
                                return size
                            }
                            totalBytes += getDirSize(jobDir)
                        }
                    }
                    appTempSpaceGB = totalBytes / (1024.0 * 1024.0 * 1024.0)

                    // The temp files are already stored in temp_work and thus already consuming C: drive space.
                    // The remaining required space is (total estimated required space) - (already written temp space).
                    // We only subtract appTempSpaceGB for the currently active encoding mode to prevent underestimating the non-active mode.
                    val remainingNative = if (isEncoding && !isSampleEncoding) maxOf(2.0, requiredSpaceGB - appTempSpaceGB) else requiredSpaceGB
                    val remainingSample = if (isEncoding && isSampleEncoding) maxOf(2.0, sampleRequiredSpaceGB - appTempSpaceGB) else sampleRequiredSpaceGB

                    hasEnoughSpace = cDriveFreeSpaceGB >= remainingNative
                    hasEnoughSpaceForSample = cDriveFreeSpaceGB >= remainingSample
                    
                    val currentHasEnough = if (isSampleEncoding) hasEnoughSpaceForSample else hasEnoughSpace
                    if (isEncoding && !currentHasEnough && !isPaused) {
                        isPaused = true
                        statusText = "PAUSED (Not enough space on C: drive!)"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }


    LaunchedEffect(fitPath) {
        if (fitPath.isNotEmpty() && File(fitPath).exists()) {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = File(fitPath).readBytes()
                    val parser = FitParser(bytes)
                    parser.parse()
                    val telemetry = parser.getTelemetry()
                    println("DEBUG: FIT file parsed successfully. Points=${telemetry.size}")
                    telemetryPoints = telemetry
                } catch (e: Exception) {
                    println("ERROR: Failed to parse FIT file: ${e.message}")
                    e.printStackTrace()
                    telemetryPoints = emptyList()
                }
            }
        } else {
            telemetryPoints = emptyList()
        }
    }



    // Save path cache when modified
    LaunchedEffect(fitPath, videoPath, videoStartUtc, timeOffsetState.millis, settings, moveOutputToSource, showLivePreview, windowState.position, windowState.size, viewModel.splitPoints) {
        if (isLoaded) {
            kotlinx.coroutines.delay(500)
            withContext(Dispatchers.IO) {
                try {
                    val pos = windowState.position
                    val (x, y) = if (pos is androidx.compose.ui.window.WindowPosition.Absolute) {
                        pos.x.value to pos.y.value
                    } else {
                        null to null
                    }
                    val cache = GuiPathCache(
                        fitPath = fitPath,
                        videoPath = videoPath,
                        videoStartUtc = videoStartUtc,
                        timeOffsetSeconds = null,
                        timeOffsetMillis = timeOffsetState.millis,
                        settings = settings,
                        moveOutputToSource = moveOutputToSource,
                        showLivePreview = showLivePreview,
                        trimStartSeconds = trimStartSeconds,
                        trimEndSeconds = trimEndSeconds,
                        splitPoints = viewModel.splitPoints,
                        windowX = x,
                        windowY = y,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    GuiCache.save(cache)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }



    LaunchedEffect(videoPath) {
        val originalPathAtStart = videoPath
        // Initialize states immediately on video changes to prevent obsolete metadata leak (Requirement 2)
        videoLengthMs = 0L
        isHudBurned = false
        if (!ignoreNextStartUtcClear) {
            videoStartUtc = ""
        }
        ignoreNextStartUtcClear = false

        if (videoPath.isNotEmpty() && File(videoPath).exists()) {
            val originalFile = File(videoPath)
            val parentDir = originalFile.parentFile
            if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                outputDir = parentDir.absolutePath
            }
            var targetVideoPath = videoPath
            
            // WMF fails to play directly from DriveFS (H:\) so we create a temporary directory junction on local C: drive
            if (videoPath.startsWith("H:\\", ignoreCase = true)) {
                try {
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val junctionFolder = File(tempDir, "fit_trimmer_video_junction")
                    
                    if (junctionFolder.exists()) {
                        ProcessBuilder("cmd.exe", "/c", "rmdir", junctionFolder.absolutePath).start().waitFor()
                    }
                    
                    val parentDir = originalFile.parentFile.absolutePath
                    val pb = ProcessBuilder("cmd.exe", "/c", "mklink", "/j", junctionFolder.absolutePath, parentDir)
                    val p = pb.start()
                    p.waitFor()
                    
                    if (junctionFolder.exists()) {
                        targetVideoPath = File(junctionFolder, originalFile.name).absolutePath
                        println("DEBUG: Successfully created junction for WMF: $targetVideoPath")
                    } else {
                        println("DEBUG: Failed to create junction for WMF, falling back to original path")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error creating junction: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            withContext(Dispatchers.IO) {
                try {
                    val duration = getVideoDuration(targetVideoPath)
                    val burned = checkIfHudBurned(targetVideoPath)
                    println("DEBUG: LaunchedEffect(videoPath) duration result: $duration, burned: $burned")
                    withContext(Dispatchers.Main) {
                        if (videoPath == originalPathAtStart) {
                            duration?.let { videoLengthMs = it }
                            isHudBurned = burned
                        }
                    }

                    val startUtc = getVideoStartUtc(targetVideoPath)
                    println("DEBUG: LaunchedEffect(videoPath) startUtc result: $startUtc")
                    startUtc?.let { utc ->
                        withContext(Dispatchers.Main) {
                            if (videoPath == originalPathAtStart) {
                                videoStartUtc = utc
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(playerState) {
        val winPlayer = playerState as? io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
        if (winPlayer != null) {
            snapshotFlow { winPlayer.durationSeconds }.collect { durSec ->
                if (durSec > 0.0 && videoLengthMs == 0L) {
                    videoLengthMs = (durSec * 1000).toLong()
                }
            }
        }
    }

    val cp = remember {
        ControlPlane(
            onCommand = { cmd ->
                var result: String? = null
                var exception: java.lang.reflect.InvocationTargetException? = null
                var interruptedException: InterruptedException? = null
                try {
                    javax.swing.SwingUtilities.invokeAndWait {
                        try {
                            when (cmd) {
                                is CpCommand.SetLayout -> settings = cmd.settings
                                is CpCommand.SetFiles -> {
                                    fitPath = cmd.fit
                                    if (videoPath != cmd.video) {
                                        if (cmd.startUtc != null) {
                                            ignoreNextStartUtcClear = true
                                        }
                                        videoPath = cmd.video
                                    }
                                    cmd.startUtc?.let { videoStartUtc = it }
                                }
                                is CpCommand.Fire -> {
                                    scope.launch {
                                        encodingPreviewImage = null
                                        isEncoding = true
                                        isPaused = false
                                        isCanceled = false
                                        viewModel.encodingSegmentStart = trimStartSeconds
                                        viewModel.encodingSegmentEnd = if (trimEndSeconds <= 0.0) videoLengthMs / 1000.0 else trimEndSeconds
                                        try {
                                            fireEncode(settings, fitPath, videoPath, outputDir, videoStartUtc,
                                                onProgress = { prog, status ->
                                                    progress = prog
                                                    statusText = status
                                                },
                                                onFrame = { bufferedImg ->
                                                    val bitmap = bufferedImg.toComposeImageBitmap()
                                                    javax.swing.SwingUtilities.invokeLater {
                                                        encodingPreviewImage = bitmap
                                                    }
                                                },
                                                trimStartSeconds = trimStartSeconds,
                                                trimEndSeconds = trimEndSeconds,
                                                pauseSupplier = { isPaused },
                                                cancelSupplier = { isCanceled },
                                                showLivePreviewSupplier = { showLivePreview }
                                            )
                                            statusText = "✨ Finished Successfully!"
                                            if (args.contains("--auto-sample")) {
                                                println("TEST_NOTIFICATION_SUCCESS: Encoding Finished Successfully!")
                                            } else {
                                                showSystemNotification(
                                                    "HUD エンコーダー",
                                                    "Encoding Finished Successfully!"
                                                )
                                            }
                                        } catch (e: Exception) {
                                            // Error string is set inside fireEncode's onProgress callback
                                            if (args.contains("--auto-sample")) {
                                                println("TEST_NOTIFICATION_ERROR: Encoding Failed: ${e.message}")
                                            } else {
                                                showSystemNotification(
                                                    "HUD エンコーダー - Error",
                                                    "Encoding Failed: ${e.message}"
                                                )
                                            }
                                        } finally {
                                            isEncoding = false
                                            viewModel.encodingSegmentStart = null
                                            viewModel.encodingSegmentEnd = null
                                        }
                                    }
                                }
                                is CpCommand.UpdateProgress -> {
                                    progress = cmd.progress
                                    isEncoding = cmd.isEncoding
                                }
                                CpCommand.GetState -> {} 
                                CpCommand.ReloadHud -> {
                                    rendererProxy.reload()
                                    reloadTrigger++
                                }
                                CpCommand.Play -> {
                                    playerState.play()
                                }
                                CpCommand.Pause -> {
                                    playerState.pause()
                                }
                                is CpCommand.Seek -> {
                                    val target = cmd.timeMs.coerceIn(0L, videoLengthMs)
                                    val ratio = if (videoLengthMs > 0) target.toFloat() / videoLengthMs.toFloat() else 0f
                                    playerState.seekTo(ratio * 1000f)
                                }
                                CpCommand.Capture -> {
                                    val win = composeWindow
                                    if (win != null) {
                                        try {
                                            win.isAlwaysOnTop = true
                                            win.toFront()
                                            win.requestFocus()
                                            Thread.sleep(200)
                                        } catch (e: Exception) {}
                                        
                                        val bounds = win.bounds
                                        val robot = java.awt.Robot()
                                        val screenCapture = robot.createScreenCapture(bounds)
                                        
                                        try {
                                            win.isAlwaysOnTop = false
                                        } catch (e: Exception) {}
                                        
                                        val tempFile = File(System.getProperty("java.io.tmpdir"), "fittrimmer_capture.png")
                                        javax.imageio.ImageIO.write(screenCapture, "png", tempFile)
                                        result = "{\"status\": \"ok\", \"path\": \"${tempFile.absolutePath.replace("\\", "\\\\")}\"}"
                                    } else {
                                        result = "{\"status\": \"error\", \"message\": \"Window not initialized\"}"
                                    }
                                }
                                CpCommand.AlignTelemetry -> {
                                    scope.launch {
                                        viewModel.isAligningTelemetry = true
                                        try {
                                            val originalInstant = try { java.time.Instant.parse(videoStartUtc) } catch(e: Exception) { null }
                                            val alignedUtc = TelemetryAligner.alignVideoWithTelemetry(videoPath, telemetryPoints, videoStartUtc)
                                            if (alignedUtc != null) {
                                                val alignedInstant = try { java.time.Instant.parse(alignedUtc) } catch(e: Exception) { null }
                                                if (originalInstant != null && alignedInstant != null) {
                                                    val diffMs = alignedInstant.toEpochMilli() - originalInstant.toEpochMilli()
                                                    val diffSec = diffMs / 1000.0
                                                    statusText = "IMU Sync: Adjusted offset by %.3f seconds".format(java.util.Locale.US, diffSec)
                                                    timeOffsetState.update(diffMs.toInt())
                                                } else {
                                                    statusText = "IMU Sync Successful"
                                                }
                                            } else {
                                                statusText = "IMU Sync failed (no correlation found)"
                                            }
                                        } finally {
                                            viewModel.isAligningTelemetry = false
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            result = "{\"status\": \"error\", \"message\": \"${e.message}\"}"
                        }
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    exception = e
                } catch (e: InterruptedException) {
                    interruptedException = e
                }
                
                if (exception != null) {
                    throw exception!!
                }
                if (interruptedException != null) {
                    Thread.currentThread().interrupt()
                }
                result
            },
            getState = {
                CpState(
                    settings = settings,
                    fitPath = fitPath,
                    videoPath = videoPath,
                    isEncoding = isEncoding,
                    progress = progress,
                    videoStartUtc = videoStartUtc,
                    isAligningTelemetry = viewModel.isAligningTelemetry
                )
            }
        )
    }
    
    LaunchedEffect(Unit) { cp.start() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "HUD エンコーダー",
        state = windowState
    ) {
        LaunchedEffect(window) {
            composeWindow = window
        }
        val textMeasurer = rememberTextMeasurer()

        // Windows Taskbar Progress integration
        val taskbar = remember {
            try {
                if (java.awt.Taskbar.isTaskbarSupported()) java.awt.Taskbar.getTaskbar() else null
            } catch (e: Exception) {
                null
            }
        }
        val isProgressSupported = remember(taskbar) {
            taskbar?.isSupported(java.awt.Taskbar.Feature.PROGRESS_VALUE_WINDOW) == true
        }

        LaunchedEffect(isEncoding, progress, isPaused, statusText) {
            if (taskbar != null && isProgressSupported) {
                try {
                    if (isEncoding) {
                        if (isPaused) {
                            taskbar.setWindowProgressState(window, java.awt.Taskbar.State.PAUSED)
                            val percent = (progress * 100).toInt().coerceIn(0, 100)
                            taskbar.setWindowProgressValue(window, percent)
                        } else if (statusText.contains("Merging", ignoreCase = true)) {
                            taskbar.setWindowProgressState(window, java.awt.Taskbar.State.INDETERMINATE)
                        } else {
                            taskbar.setWindowProgressState(window, java.awt.Taskbar.State.NORMAL)
                            val percent = (progress * 100).toInt().coerceIn(0, 100)
                            taskbar.setWindowProgressValue(window, percent)
                        }
                    } else {
                        taskbar.setWindowProgressState(window, java.awt.Taskbar.State.OFF)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        MaterialTheme(colors = lightColors(primary = Color(0xFF007AFF))) {
            Row(modifier = Modifier.fillMaxSize().background(Color.White)) {
                Column(
                    modifier = Modifier.width(320.dp).fillMaxHeight().background(Color(0xFFF5F5F7))
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {


                    val onNativeEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, adjustedStartUtc, isVideoInFitRange, outputDir, moveOutputToSource, showLivePreview, viewModel.splitPoints) {
                        {
                            val targetVideoPath = videoPath
                            var proceed = true
                            if (!isVideoInFitRange && !args.contains("--auto-sample")) {
                                val result = javax.swing.JOptionPane.showConfirmDialog(
                                    null,
                                    "警告: 動画の記録範囲がFITデータの範囲内に収まっていません。\nエンコードを続行しますか？\n(Warning: The video timeline is not within the FIT activity range. Proceed with encoding?)",
                                    "確認 (Confirmation)",
                                    javax.swing.JOptionPane.YES_NO_OPTION,
                                    javax.swing.JOptionPane.WARNING_MESSAGE
                                )
                                if (result != javax.swing.JOptionPane.YES_OPTION) {
                                    proceed = false
                                }
                            }
                            val ranges = viewModel.getSplitRanges()
                            val destFiles = mutableListOf<File>()
                            
                            // 1. Determine all standard target output files
                            val targetDestFiles = mutableListOf<File>()
                            val resSuffix = when (settings.exportResolution) {
                                "1080p" -> "_1080p"
                                "2.7k" -> "_2.7k"
                                else -> "_orig"
                            }
                            for (idx in ranges.indices) {
                                val partSuffix = if (ranges.size > 1) "_part${idx + 1}" else ""
                                val videoFile = File(targetVideoPath)
                                val baseName = videoFile.name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
                                val suffix = baseName + partSuffix + "_KMP_HUD" + resSuffix + ".mp4"
                                
                                val file = if (moveOutputToSource) {
                                    val sourceDir = videoFile.parentFile
                                    if (sourceDir != null && sourceDir.exists()) {
                                        File(sourceDir, suffix)
                                    } else {
                                        File(outputDir, suffix)
                                    }
                                } else {
                                    File(outputDir, suffix)
                                }
                                targetDestFiles.add(file)
                            }

                            // 2. Check for existing outputs
                            val existingOutputs = targetDestFiles.filter { it.exists() && it.length() > 0L }
                            
                            var shouldResume = false
                            if (existingOutputs.isNotEmpty()) {
                                val options = arrayOf("最初からやり直す (Overwrite)", "前回の続きから再開 (Resume)", "別名で保存 (Save as Copy)", "キャンセル (Cancel)")
                                val choice = javax.swing.JOptionPane.showOptionDialog(
                                    null,
                                    "出力ファイルの一部が既に存在します。どうしますか？\n" +
                                    "・「最初からやり直す」: 既存のファイルを上書きして最初からエンコードします。\n" +
                                    "・「前回の続きから再開」: 既に作成済みのパートをスキップし、残りの処理から再開します。\n" +
                                    "・「別名で保存」: 既存のファイルは残し、コピーとして別名で保存します。\n\n" +
                                    "(Output file already exists. Overwrite, Resume, or Save as Copy?)",
                                    "出力ファイルの重複 (File Exists)",
                                    javax.swing.JOptionPane.DEFAULT_OPTION,
                                    javax.swing.JOptionPane.QUESTION_MESSAGE,
                                    null,
                                    options,
                                    options[0]
                                )
                                when (choice) {
                                    0 -> { // Overwrite
                                        existingOutputs.forEach { 
                                            try { if (it.exists()) it.delete() } catch (e: Exception) { e.printStackTrace() }
                                        }
                                        destFiles.addAll(targetDestFiles)
                                        shouldResume = false
                                    }
                                    1 -> { // Resume
                                        destFiles.addAll(targetDestFiles)
                                        shouldResume = true
                                    }
                                    2 -> { // Save as Copy
                                        shouldResume = false
                                        for (idx in targetDestFiles.indices) {
                                            val file = targetDestFiles[idx]
                                            if (file.exists()) {
                                                val parent = file.parentFile
                                                val nameWithoutExt = file.nameWithoutExtension
                                                val ext = file.extension
                                                var counter = 1
                                                var uniqueFile = File(parent, "$nameWithoutExt ($counter).$ext")
                                                while (uniqueFile.exists()) {
                                                    counter++
                                                    uniqueFile = File(parent, "$nameWithoutExt ($counter).$ext")
                                                }
                                                destFiles.add(uniqueFile)
                                            } else {
                                                destFiles.add(file)
                                            }
                                        }
                                    }
                                    else -> { // Cancel / Closed
                                        proceed = false
                                    }
                                }
                            } else {
                                destFiles.addAll(targetDestFiles)
                            }

                            if (proceed) {
                                scope.launch {
                                    encodingPreviewImage = null
                                    isSampleEncoding = false
                                    isEncoding = true
                                    isPaused = false
                                    isCanceled = false
                                    try {
                                        val totalDuration = ranges.sumOf { it.second - it.first }
                                        var completedDuration = 0.0
                                        var hasCloudSyncMsg = false
                                        
                                        for (idx in ranges.indices) {
                                            if (isCanceled) break
                                            val (pStart, pEnd) = ranges[idx]
                                            val partDuration = pEnd - pStart
                                            
                                            val finalOutFile = destFiles.getOrNull(idx)
                                            
                                            if (shouldResume && finalOutFile != null && finalOutFile.exists() && finalOutFile.length() > 0L) {
                                                println("DEBUG: Segment ${idx + 1} already finished. Skipping. File: ${finalOutFile.absolutePath}")
                                                completedDuration += partDuration
                                                continue
                                            }
                                            
                                            viewModel.encodingSegmentStart = pStart
                                            viewModel.encodingSegmentEnd = pEnd
                                            
                                            val partOutPath = fireEncode(settings, fitPath, videoPath, outputDir, adjustedStartUtc,
                                                onProgress = { prog, status ->
                                                    val segmentProgress = prog.toDouble()
                                                    val overallProg = if (totalDuration > 0.0) {
                                                        (completedDuration + segmentProgress * partDuration) / totalDuration
                                                    } else 0.0
                                                    progress = overallProg.toFloat()
                                                    statusText = "[Part ${idx + 1}/${ranges.size}] $status"
                                                },
                                                onFrame = { bufferedImg ->
                                                    val bitmap = bufferedImg.toComposeImageBitmap()
                                                    javax.swing.SwingUtilities.invokeLater {
                                                        encodingPreviewImage = bitmap
                                                    }
                                                },
                                                trimStartSeconds = pStart,
                                                trimEndSeconds = pEnd,
                                                pauseSupplier = { isPaused },
                                                cancelSupplier = { isCanceled },
                                                showLivePreviewSupplier = { showLivePreview },
                                                partIndex = idx,
                                                numParts = ranges.size
                                            )
                                            
                                            if (destFiles.isNotEmpty() && !isCanceled) {
                                                val finalDestFile = destFiles.getOrNull(idx)
                                                if (finalDestFile != null) {
                                                    val outFile = File(partOutPath)
                                                    if (outFile.absolutePath != finalDestFile.absolutePath) {
                                                        statusText = "[Part ${idx + 1}/${ranges.size}] Moving file to destination..."
                                                        withContext(Dispatchers.IO) {
                                                            java.nio.file.Files.copy(outFile.toPath(), finalDestFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                            outFile.delete()
                                                        }
                                                    }
                                                    
                                                    if (moveOutputToSource) {
                                                        val normalized = targetVideoPath.replace("\\", "/").lowercase()
                                                        if (normalized.contains("google drive") || 
                                                            normalized.contains("マイドライブ") || 
                                                            normalized.contains("my drive") ||
                                                            normalized.startsWith("g:/") || 
                                                            normalized.startsWith("h:/")) {
                                                            hasCloudSyncMsg = true
                                                        }
                                                    }
                                                }
                                            }
                                            completedDuration += partDuration
                                        }
                                        
                                        if (hasCloudSyncMsg) {
                                            statusText = "✨ Copied to Cloud. Drive Desktop is syncing in background (Check system tray)."
                                        } else {
                                            statusText = if (isCanceled) "Encoding Canceled" else "✨ Finished Successfully!"
                                        }
                                        
                                        if (!isCanceled) {
                                            if (args.contains("--auto-sample")) {
                                                println("TEST_NOTIFICATION_SUCCESS: Encoding Finished Successfully!")
                                            } else {
                                                showSystemNotification(
                                                    "HUD エンコーダー",
                                                    "Encoding Finished Successfully!"
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (args.contains("--auto-sample")) {
                                            println("TEST_NOTIFICATION_ERROR: Encoding Failed: ${e.message}")
                                        } else {
                                            showSystemNotification(
                                                "HUD エンコーダー - Error",
                                                "Encoding Failed: ${e.message}"
                                            )
                                        }
                                    } finally {
                                        isEncoding = false
                                        isSampleEncoding = false
                                        viewModel.encodingSegmentStart = null
                                        viewModel.encodingSegmentEnd = null
                                    }
                                }
                            }
                            Unit
                        }
                    }

                    val onSampleEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, adjustedStartUtc, isVideoInFitRange, outputDir, moveOutputToSource, showLivePreview) {
                        {
                            val targetVideoPath = videoPath
                            var proceed = true
                            if (!isVideoInFitRange && !args.contains("--auto-sample")) {
                                val result = javax.swing.JOptionPane.showConfirmDialog(
                                    null,
                                    "警告: 動画の記録範囲がFITデータの範囲内に収まっていません。\nエンコードを続行しますか？\n(Warning: The video timeline is not within the FIT activity range. Proceed with encoding?)",
                                    "確認 (Confirmation)",
                                    javax.swing.JOptionPane.YES_NO_OPTION,
                                    javax.swing.JOptionPane.WARNING_MESSAGE
                                )
                                if (result != javax.swing.JOptionPane.YES_OPTION) {
                                    proceed = false
                                }
                            }
                            if (proceed) {
                                scope.launch {
                                    encodingPreviewImage = null
                                    isSampleEncoding = true
                                    isEncoding = true
                                    isPaused = false
                                    isCanceled = false
                                    viewModel.encodingSegmentStart = trimStartSeconds
                                    viewModel.encodingSegmentEnd = trimStartSeconds + 5.0
                                    try {
                                        val outPath = fireEncode(settings, fitPath, videoPath, outputDir, adjustedStartUtc,
                                            onProgress = { prog, status ->
                                                progress = prog
                                                statusText = status
                                            },
                                            onFrame = { bufferedImg ->
                                                val bitmap = bufferedImg.toComposeImageBitmap()
                                                javax.swing.SwingUtilities.invokeLater {
                                                    encodingPreviewImage = bitmap
                                                }
                                            },
                                            maxDurationSeconds = 5,
                                            trimStartSeconds = trimStartSeconds,
                                            trimEndSeconds = trimEndSeconds,
                                            pauseSupplier = { isPaused },
                                            cancelSupplier = { isCanceled },
                                            showLivePreviewSupplier = { showLivePreview }
                                        )
                                        if (moveOutputToSource && !isCanceled) {
                                            val sourceDir = File(targetVideoPath).parentFile
                                            val outFile = File(outPath)
                                            if (sourceDir != null && sourceDir.exists()) {
                                                val destFile = File(sourceDir, outFile.name)
                                                if (outFile.absolutePath != destFile.absolutePath) {
                                                    statusText = "Moving file to source directory..."
                                                    withContext(Dispatchers.IO) {
                                                        java.nio.file.Files.copy(outFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                        outFile.delete()
                                                    }
                                                }
                                            }
                                        }
                                        statusText = if (isCanceled) "Encoding Canceled" else "✨ Sample Finished Successfully!"
                                        if (!isCanceled) {
                                            if (args.contains("--auto-sample")) {
                                                println("TEST_NOTIFICATION_SUCCESS: Sample Encoding Finished Successfully!")
                                            } else {
                                                showSystemNotification(
                                                    "HUD エンコーダー",
                                                    "Sample Encoding Finished Successfully!"
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Handled and displayed in statusText
                                        if (args.contains("--auto-sample")) {
                                            println("TEST_NOTIFICATION_ERROR: Sample Encoding Failed: ${e.message}")
                                        } else {
                                            showSystemNotification(
                                                "HUD エンコーダー - Error",
                                                "Sample Encoding Failed: ${e.message}"
                                            )
                                        }
                                    } finally {
                                        isEncoding = false
                                        isSampleEncoding = false
                                        viewModel.encodingSegmentStart = null
                                        viewModel.encodingSegmentEnd = null
                                        if (args.contains("--auto-sample")) {
                                            println("🏁 Auto-sample test finished. Exiting application...")
                                            exitApplication()
                                        }
                                    }
                                }
                            }
                            Unit
                        }
                    }

                    if (args.contains("--auto-sample")) {
                        LaunchedEffect(isLoaded) {
                            if (isLoaded) {
                                // Wait for UI to render
                                kotlinx.coroutines.delay(1000)
                                println("🚀 Auto-sample test triggered")
                                onSampleEncodeClick()
                            }
                        }
                    }

                    // 1. ENCODER SETUP Card
                    Card(
                        backgroundColor = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                        elevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("ENCODER SETUP", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = fitPath,
                                    onValueChange = { fitPath = it },
                                    label = { Text("FIT File Path", color = Color(0xFF636366), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 11.sp),
                                    singleLine = true,
                                    enabled = !isEncoding,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        backgroundColor = Color(0xFFF2F2F7),
                                        focusedBorderColor = Color(0xFF007AFF),
                                        unfocusedBorderColor = Color(0xFFD1D1D6),
                                        textColor = Color(0xFF1C1C1E),
                                        disabledTextColor = Color(0xFF8E8E93)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFile("Select FIT File", listOf("*.fit"))
                                        if (path != null) fitPath = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color(0xFF1C1C1E), fontSize = 11.sp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = videoPath,
                                    onValueChange = { videoPath = it },
                                    label = { Text("MP4 File Path", color = Color(0xFF636366), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 11.sp),
                                    singleLine = true,
                                    enabled = !isEncoding,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        backgroundColor = Color(0xFFF2F2F7),
                                        focusedBorderColor = Color(0xFF007AFF),
                                        unfocusedBorderColor = Color(0xFFD1D1D6),
                                        textColor = Color(0xFF1C1C1E),
                                        disabledTextColor = Color(0xFF8E8E93)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFile("Select MP4 File", listOf("*.mp4", "*.mov"))
                                        if (path != null) videoPath = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color(0xFF1C1C1E), fontSize = 11.sp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = outputDir,
                                    onValueChange = { outputDir = it },
                                    label = { Text("Output Directory", color = Color(0xFF636366), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 11.sp),
                                    singleLine = true,
                                    enabled = !isEncoding,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        backgroundColor = Color(0xFFF2F2F7),
                                        focusedBorderColor = Color(0xFF007AFF),
                                        unfocusedBorderColor = Color(0xFFD1D1D6),
                                        textColor = Color(0xFF1C1C1E),
                                        disabledTextColor = Color(0xFF8E8E93)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFolder("Select Output Directory")
                                        if (path != null) outputDir = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color(0xFF1C1C1E), fontSize = 11.sp) }
                            }
                              Row(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .clickable { moveOutputToSource = !moveOutputToSource }
                                      .padding(horizontal = 10.dp, vertical = 8.dp),
                                  horizontalArrangement = Arrangement.SpaceBetween,
                                  verticalAlignment = Alignment.CenterVertically
                              ) {
                                  Column(modifier = Modifier.weight(1f)) {
                                      Text("Move Output to Source", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                      Text("Saves finished video in the original folder", color = Color(0xFF636366), fontSize = 9.sp)
                                  }
                                  Switch(
                                      checked = moveOutputToSource,
                                      onCheckedChange = { moveOutputToSource = it },
                                      colors = SwitchDefaults.colors(
                                          checkedThumbColor = Color.White,
                                          checkedTrackColor = Color(0xFF34C759),
                                          uncheckedThumbColor = Color(0xFFE5E5EA),
                                          uncheckedTrackColor = Color(0xFFD1D1D6)
                                      )
                                  )
                              }

                              Spacer(modifier = Modifier.height(4.dp))

                              Row(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .clickable { 
                                          showLivePreview = !showLivePreview 
                                          if (!showLivePreview) {
                                              encodingPreviewImage = null
                                          }
                                      }
                                      .padding(horizontal = 10.dp, vertical = 8.dp),
                                  horizontalArrangement = Arrangement.SpaceBetween,
                                  verticalAlignment = Alignment.CenterVertically
                              ) {
                                  Column(modifier = Modifier.weight(1f)) {
                                      Text("Show Live Preview", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                      Text("Disable for slightly faster encoding", color = Color(0xFF636366), fontSize = 9.sp)
                                  }
                                  Switch(
                                      checked = showLivePreview,
                                      onCheckedChange = { 
                                          showLivePreview = it 
                                          if (!it) {
                                              encodingPreviewImage = null
                                          }
                                      },
                                      colors = SwitchDefaults.colors(
                                          checkedThumbColor = Color.White,
                                          checkedTrackColor = Color(0xFF34C759),
                                          uncheckedThumbColor = Color(0xFFE5E5EA),
                                          uncheckedTrackColor = Color(0xFFD1D1D6)
                                      )
                                  )
                              }

                              Spacer(modifier = Modifier.height(4.dp))

                              // Export Resolution Selector
                              Column(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .padding(horizontal = 10.dp, vertical = 8.dp),
                                  verticalArrangement = Arrangement.spacedBy(6.dp)
                              ) {
                                  Column {
                                      Text("Output Resolution", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                      Text("Downscales resolution to save space & speed up", color = Color(0xFF636366), fontSize = 9.sp)
                                  }
                                  Row(
                                      modifier = Modifier.fillMaxWidth().height(28.dp),
                                      horizontalArrangement = Arrangement.spacedBy(4.dp)
                                  ) {
                                      val options = listOf(
                                          Triple("1080p", "1080p", Modifier.weight(1f)),
                                          Triple("2.7k", "2.7K", Modifier.weight(1f)),
                                          Triple("original", "Original", Modifier.weight(1.2f))
                                      )
                                      options.forEach { (value, label, weightModifier) ->
                                          val isSelected = settings.exportResolution == value
                                          Button(
                                              onClick = { settings = settings.copy(exportResolution = value) },
                                              colors = ButtonDefaults.buttonColors(
                                                  backgroundColor = if (isSelected) Color(0xFF007AFF) else Color(0xFFE5E5EA),
                                                  contentColor = if (isSelected) Color.White else Color(0xFF1C1C1E)
                                              ),
                                              contentPadding = PaddingValues(0.dp),
                                              shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                              modifier = weightModifier.fillMaxHeight(),
                                              enabled = !isEncoding
                                          ) {
                                              Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                          }
                                      }
                                  }
                              }

                              Spacer(modifier = Modifier.height(6.dp))

                              // Route Captions / Road Captions Settings
                              Column(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                      .padding(horizontal = 10.dp, vertical = 8.dp),
                                  verticalArrangement = Arrangement.spacedBy(6.dp)
                              ) {
                                  Row(
                                      modifier = Modifier.fillMaxWidth(),
                                      horizontalArrangement = Arrangement.SpaceBetween,
                                      verticalAlignment = Alignment.CenterVertically
                                  ) {
                                      Column {
                                          Text("ROUTE CAPTIONS (路線名テロップ)", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          Text("動画上に道路の路線名を焼き込みます", color = Color(0xFF636366), fontSize = 8.sp)
                                      }
                                  }

                                  if (isDetectingRoads) {
                                      Column(
                                          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                          verticalArrangement = Arrangement.spacedBy(4.dp),
                                          horizontalAlignment = Alignment.CenterHorizontally
                                      ) {
                                          CircularProgressIndicator(
                                              modifier = Modifier.size(20.dp),
                                              color = Color(0xFF007AFF),
                                              strokeWidth = 2.dp
                                          )
                                          Text(roadDetectionStatus, fontSize = 9.sp, color = Color(0xFF1C1C1E))
                                          OutlinedButton(
                                              onClick = {
                                                  roadDetectionJob?.cancel()
                                                  isDetectingRoads = false
                                              },
                                              modifier = Modifier.height(20.dp),
                                              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                                          ) {
                                              Text("キャンセル", fontSize = 8.sp)
                                          }
                                      }
                                  } else {
                                      val captions = settings.roadCaptions
                                      if (captions.isEmpty()) {
                                          Text("路線名データがありません。GPSログから自動検出できます。", fontSize = 9.sp, color = Color.Gray)
                                          Button(
                                              onClick = {
                                                  isDetectingRoads = true
                                                  roadDetectionStatus = "スキャン準備中..."
                                                  roadDetectionJob = scope.launch {
                                                      try {
                                                          val durationSec = videoLengthMs / 1000.0
                                                          val detected = detectRoadSegments(
                                                              points = telemetryPoints,
                                                              videoStartUtc = adjustedStartUtc.ifEmpty { videoStartUtc },
                                                              timeOffsetMillis = timeOffsetState.millis.toLong(),
                                                              videoDurationSeconds = durationSec,
                                                              onProgress = { progressText ->
                                                                  roadDetectionStatus = progressText
                                                              }
                                                          )
                                                          settings = settings.copy(roadCaptions = detected)
                                                      } catch (e: Exception) {
                                                          e.printStackTrace()
                                                          roadDetectionStatus = "エラーが発生しました: ${e.message}"
                                                      } finally {
                                                          isDetectingRoads = false
                                                      }
                                                  }
                                              },
                                              colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF)),
                                              contentPadding = PaddingValues(0.dp),
                                              shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                              modifier = Modifier.fillMaxWidth().height(24.dp),
                                              enabled = videoPath.isNotEmpty() && telemetryPoints.isNotEmpty() && !isEncoding
                                          ) {
                                              Text("🔍 GPSから路線名を自動検出する", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                          }
                                      } else {
                                          Column(
                                              modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                                              verticalArrangement = Arrangement.spacedBy(4.dp)
                                          ) {
                                              captions.forEachIndexed { index, segment ->
                                                  Row(
                                                      modifier = Modifier
                                                          .fillMaxWidth()
                                                          .background(Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                          .border(0.5.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                          .padding(4.dp),
                                                      verticalAlignment = Alignment.CenterVertically
                                                  ) {
                                                      Checkbox(
                                                          checked = segment.isEnabled,
                                                          onCheckedChange = { checked ->
                                                              val updated = captions.mapIndexed { idx, item ->
                                                                  if (idx == index) item.copy(isEnabled = checked) else item
                                                              }
                                                              settings = settings.copy(roadCaptions = updated)
                                                          },
                                                          modifier = Modifier.size(24.dp)
                                                      )
                                                      Spacer(Modifier.width(2.dp))
                                                      Column(modifier = Modifier.weight(1f)) {
                                                          androidx.compose.foundation.text.BasicTextField(
                                                              value = segment.text,
                                                              onValueChange = { newText ->
                                                                  val updated = captions.mapIndexed { idx, item ->
                                                                      if (idx == index) item.copy(text = newText) else item
                                                                  }
                                                                  settings = settings.copy(roadCaptions = updated)
                                                              },
                                                              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold),
                                                              modifier = Modifier.fillMaxWidth().background(Color(0xFFF2F2F7)).padding(2.dp)
                                                          )
                                                          Text(
                                                              text = "${utils.formatTime((segment.startSeconds * 1000).toLong())} - ${utils.formatTime((segment.endSeconds * 1000).toLong())}",
                                                              fontSize = 8.sp,
                                                              color = Color.Gray
                                                          )
                                                      }
                                                      Spacer(Modifier.width(2.dp))
                                                      IconButton(
                                                          onClick = {
                                                              val updated = captions.filterIndexed { idx, _ -> idx != index }
                                                              settings = settings.copy(roadCaptions = updated)
                                                          },
                                                          modifier = Modifier.size(20.dp)
                                                      ) {
                                                          Text("❌", fontSize = 8.sp)
                                                      }
                                                  }
                                              }
                                          }

                                          Spacer(modifier = Modifier.height(2.dp))

                                          Row(
                                              modifier = Modifier.fillMaxWidth(),
                                              horizontalArrangement = Arrangement.spacedBy(4.dp)
                                          ) {
                                              Button(
                                                  onClick = {
                                                      val newSegment = RoadCaptionSegment(
                                                          id = java.util.UUID.randomUUID().toString(),
                                                          startSeconds = 0.0,
                                                          endSeconds = videoLengthMs / 1000.0,
                                                          text = "新しいテロップ",
                                                          isEnabled = true
                                                      )
                                                      settings = settings.copy(roadCaptions = captions + newSegment)
                                                  },
                                                  colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                                                  contentPadding = PaddingValues(0.dp),
                                                  modifier = Modifier.weight(1f).height(20.dp)
                                              ) {
                                                  Text("＋ 追加", fontSize = 9.sp, color = Color(0xFF1C1C1E))
                                              }

                                              Button(
                                                  onClick = {
                                                      isDetectingRoads = true
                                                      roadDetectionStatus = "スキャン準備中..."
                                                      roadDetectionJob = scope.launch {
                                                          try {
                                                              val durationSec = videoLengthMs / 1000.0
                                                              val detected = detectRoadSegments(
                                                                  points = telemetryPoints,
                                                                  videoStartUtc = adjustedStartUtc.ifEmpty { videoStartUtc },
                                                                  timeOffsetMillis = timeOffsetState.millis.toLong(),
                                                                  videoDurationSeconds = durationSec,
                                                                  onProgress = { progressText ->
                                                                      roadDetectionStatus = progressText
                                                                  }
                                                              )
                                                              settings = settings.copy(roadCaptions = detected)
                                                          } catch (e: Exception) {
                                                              e.printStackTrace()
                                                          } finally {
                                                              isDetectingRoads = false
                                                          }
                                                      }
                                                  },
                                                  colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                                                  contentPadding = PaddingValues(0.dp),
                                                  modifier = Modifier.weight(1f).height(20.dp)
                                              ) {
                                                  Text("🔄 再検出", fontSize = 9.sp, color = Color(0xFF1C1C1E))
                                              }

                                              Button(
                                                  onClick = {
                                                      settings = settings.copy(roadCaptions = emptyList())
                                                  },
                                                  colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFFD6D6)),
                                                  contentPadding = PaddingValues(0.dp),
                                                  modifier = Modifier.weight(1f).height(20.dp)
                                              ) {
                                                  Text("🗑 クリア", fontSize = 9.sp, color = Color.Red)
                                              }
                                          }
                                      }
                                  }
                              }



                               // Display FIT start/end timestamp and warning if video range is out of bounds
                              if (fitStartInstant != null && fitEndInstant != null) {
                                  val fitStartStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                      .withZone(java.time.ZoneId.systemDefault()).format(fitStartInstant)
                                  val fitEndStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                      .withZone(java.time.ZoneId.systemDefault()).format(fitEndInstant)

                                  Divider(color = Color(0xFFE5E5EA), modifier = Modifier.padding(vertical = 4.dp))
                                  
                                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                      Text("FIT TIMELINE", color = Color(0xFF636366), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                      Text("$fitStartStr  ~\n$fitEndStr", color = Color(0xFF1C1C1E), fontSize = 11.sp)
                                  }
                                  
                                   val vStart = videoStartInstant
                                   val vEnd = videoEndInstant
                                   if (vStart != null && vEnd != null) {
                                       val videoStartStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                           .withZone(java.time.ZoneId.systemDefault()).format(vStart)
                                       val videoEndStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                           .withZone(java.time.ZoneId.systemDefault()).format(vEnd)

                                       Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                           Text("VIDEO TIMELINE", color = Color(0xFF636366), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                           Text("$videoStartStr  ~\n$videoEndStr", color = Color(0xFF1C1C1E), fontSize = 11.sp)
                                       }
                                      
                                      val trimStartInstant = vStart.plusMillis((trimStartSeconds * 1000).toLong())
                                      val trimEndInstant = vStart.plusMillis((trimEndSeconds * 1000).toLong())
                                      val trimStartStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                          .withZone(java.time.ZoneId.systemDefault()).format(trimStartInstant)
                                      val trimEndStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                          .withZone(java.time.ZoneId.systemDefault()).format(trimEndInstant)

                                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                          Text("TRIMMED TIMELINE", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                          Text("$trimStartStr  ~\n$trimEndStr", color = Color(0xFF1C1C1E), fontSize = 11.sp)
                                      }

                                      Spacer(modifier = Modifier.height(2.dp))

                                      if (isVideoInFitRange) {
                                          Row(
                                              verticalAlignment = Alignment.CenterVertically,
                                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                                              modifier = Modifier
                                                  .fillMaxWidth()
                                                  .background(Color(0xFFE2F6E9), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                  .padding(horizontal = 8.dp, vertical = 6.dp)
                                          ) {
                                              Text("✓ VIDEO WITHIN FIT RANGE", color = Color(0xFF1E7E34), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          }
                                      } else {
                                          Row(
                                              verticalAlignment = Alignment.CenterVertically,
                                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                                              modifier = Modifier
                                                  .fillMaxWidth()
                                                  .background(Color(0xFFFDE8E8), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                  .border(BorderStroke(1.dp, Color(0xFFE02424)), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                  .padding(horizontal = 8.dp, vertical = 6.dp)
                                          ) {
                                              Text("⚠️ VIDEO OUTSIDE FIT RANGE", color = Color(0xFFE02424), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          }
                                      }

                                      if (isHudBurned) {
                                          Row(
                                              verticalAlignment = Alignment.CenterVertically,
                                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                                              modifier = Modifier
                                                  .fillMaxWidth()
                                                  .background(Color(0xFFFEF3C7), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                  .border(BorderStroke(1.dp, Color(0xFFD97706)), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                  .padding(horizontal = 8.dp, vertical = 6.dp)
                                          ) {
                                              Text("⚠️ HUD ALREADY BURNED (焼き込み済み)", color = Color(0xFFB45309), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          }
                                      }
                                  }
                              }
                        }
                    }

                    // TIME ALIGNMENT Card
                    TimeAlignmentCard(
                        state = timeOffsetState,
                        videoCurrentTimeMs = videoCurrentTimeMs,
                        videoStartUtc = videoStartUtc,
                        onVideoStartUtcChange = { videoStartUtc = it },
                        videoPath = videoPath,
                        telemetryPoints = telemetryPoints,
                        isAligning = viewModel.isAligningTelemetry,
                        onAlignTelemetryClick = {
                            scope.launch {
                                viewModel.isAligningTelemetry = true
                                try {
                                    val originalInstant = try { java.time.Instant.parse(videoStartUtc) } catch(e: Exception) { null }
                                    val alignedUtc = TelemetryAligner.alignVideoWithTelemetry(videoPath, telemetryPoints, videoStartUtc)
                                    if (alignedUtc != null) {
                                        val alignedInstant = try { java.time.Instant.parse(alignedUtc) } catch(e: Exception) { null }
                                        if (originalInstant != null && alignedInstant != null) {
                                            val diffMs = alignedInstant.toEpochMilli() - originalInstant.toEpochMilli()
                                            val diffSec = diffMs / 1000.0
                                            statusText = "IMU Sync: Adjusted offset by %.3f seconds".format(java.util.Locale.US, diffSec)
                                            timeOffsetState.update(diffMs.toInt())
                                        } else {
                                            statusText = "IMU Sync Successful"
                                        }
                                    } else {
                                        statusText = "IMU Sync failed (no correlation found)"
                                    }
                                } finally {
                                    viewModel.isAligningTelemetry = false
                                }
                            }
                        }
                    )

                    // VIDEO TRIM RANGE Card
                    if (videoLengthMs > 0) {
                        VideoTrimCard(
                            videoLengthMs = videoLengthMs,
                            trimStartSeconds = trimStartSeconds,
                            trimEndSeconds = trimEndSeconds,
                            onTrimStartChange = { trimStartSeconds = it },
                            onTrimEndChange = { trimEndSeconds = it }
                        )
                        Spacer(Modifier.height(8.dp))
                        VideoSplitCard(
                            viewModel = viewModel,
                            videoCurrentTimeMs = videoCurrentTimeMs
                        )
                    }

                    // C-DRIVE SPACE MONITOR Card
                    Card(
                        backgroundColor = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (hasEnoughSpace) Color(0xFFE5E5EA) else Color(0xFFE02424)),
                        elevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
                                val driveLabel = if (isWindows) "$monitoredDriveName SPACE MONITOR" else "SYSTEM DISK MONITOR"
                                Text(driveLabel, color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
                                if (hasEnoughSpace) {
                                    Text("SAFE", color = Color(0xFF1E7E34), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                } else {
                                    Text("LOW SPACE WARNING", color = Color(0xFFE02424), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                            
                            val freeRatio = if (cDriveTotalSpaceGB > 0) (cDriveFreeSpaceGB / cDriveTotalSpaceGB).toFloat() else 0f
                            val barColor = when {
                                !hasEnoughSpace -> Color(0xFFE02424) // Red
                                freeRatio < 0.1f -> Color(0xFFD84315) // Orange
                                else -> Color(0xFF2E7D32) // Green
                            }
                            
                            LinearProgressIndicator(
                                progress = freeRatio,
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = barColor,
                                backgroundColor = Color(0xFFE5E5EA)
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Free Space:", color = Color(0xFF636366), fontSize = 10.sp)
                                Text(
                                    "%.2f GB / %.1f GB (%.1f%%)".format(cDriveFreeSpaceGB, cDriveTotalSpaceGB, freeRatio * 100),
                                    color = Color(0xFF1C1C1E),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Est. Required Space:", color = Color(0xFF636366), fontSize = 10.sp)
                                Text(
                                    "%.2f GB".format(requiredSpaceGB),
                                    color = if (hasEnoughSpace) Color(0xFF1C1C1E) else Color(0xFFE02424),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Divider(color = Color(0xFFE5E5EA), modifier = Modifier.padding(vertical = 2.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("App Temp Space:", color = Color(0xFF636366), fontSize = 10.sp)
                                Text(
                                    "%.2f GB".format(appTempSpaceGB),
                                    color = Color(0xFFD84315),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Released on Finish:", color = Color(0xFF636366), fontSize = 10.sp)
                                Text(
                                    "~ %.2f GB".format(appTempSpaceGB),
                                    color = Color(0xFF2E7D32),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Divider(color = Color(0xFFE5E5EA), modifier = Modifier.padding(vertical = 4.dp))

                            OutlinedButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val workDir = fit.PathResolver.getTempWorkDir(videoPath)
                                            if (workDir.exists()) {
                                                workDir.listFiles()?.forEach { it.deleteRecursively() }
                                            }
                                            appTempSpaceGB = 0.0
                                            println("🧹 Manually cleared temp_work directory successfully.")
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                enabled = !isEncoding,
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFE02424),
                                    disabledContentColor = Color(0xFF8E8E93)
                                ),
                                border = BorderStroke(1.dp, if (!isEncoding) Color(0xFFE02424).copy(alpha = 0.5f) else Color(0xFFE5E5EA)),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("CLEAR ALL APP TEMP FILES", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            }
                        }
                    }

                    // 3. ENCODE Actions / Progress Monitor
                    if (isEncoding) {
                        Card(
                            backgroundColor = Color.White,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                            elevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (statusText.contains("Merging", ignoreCase = true)) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF34C759),
                                        backgroundColor = Color(0xFFE5E5EA)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (isPaused) Color(0xFFFF9F0A) else Color(0xFF007AFF),
                                        backgroundColor = Color(0xFFE5E5EA)
                                    )
                                }
                                Text(if (isPaused) "PAUSED (Not enough space or paused manually)\n$statusText" else statusText, color = Color(0xFF1C1C1E), fontSize = 12.sp)
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { isPaused = !isPaused },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        enabled = !(!isPaused && !hasEnoughSpace),
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = if (isPaused) Color(0xFF34C759) else Color(0xFFFF9F0A),
                                            disabledBackgroundColor = Color(0xFFE5E5EA)
                                        ),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    ) {
                                        Text(if (isPaused) "RESUME" else "PAUSE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Button(
                                        onClick = { isCanceled = true },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE02424)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    ) {
                                        Text("CANCEL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = onNativeEncodeClick,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            enabled = hasEnoughSpace && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (hasEnoughSpace) Color(0xFF007AFF) else Color(0xFFD1D1D6),
                                disabledBackgroundColor = Color(0xFFE5E5EA)
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text(if (hasEnoughSpace) "RUN NATIVE ENCODE" else "INSUFFICIENT DISK SPACE", color = if (hasEnoughSpace) Color.White else Color(0xFF8E8E93), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val isSampleEnabled = hasEnoughSpaceForSample && fitPath.isNotEmpty() && videoPath.isNotEmpty()
                        OutlinedButton(
                            onClick = onSampleEncodeClick,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            enabled = isSampleEnabled,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF007AFF),
                                disabledContentColor = Color(0xFF8E8E93)
                            ),
                            border = BorderStroke(1.5.dp, if (isSampleEnabled) Color(0xFF007AFF) else Color(0xFFE5E5EA)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text("RUN 5s SAMPLE", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }

                    Divider(color = Color(0xFFE5E5EA))

                    // 4. HUD LAYOUT CONFIG Card
                    Card(
                        backgroundColor = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                        elevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("HUD LAYOUT CONFIG", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            ControlSlider("VAL SIZE", settings.valSize, 10f, 150f) { settings = settings.copy(valSize = it) }
                            ControlSlider("TIGHTNESS", settings.tightness, -10f, 40f) { settings = settings.copy(tightness = it) }
                            ControlSlider("SPACING", settings.spacing, 0f, 100f) { settings = settings.copy(spacing = it) }
                            ControlSlider("X OFFSET", settings.xOffset, 0f, 500f) { settings = settings.copy(xOffset = it) }
                            ControlSlider("Y OFFSET", settings.yOffset, 0f, 500f) { settings = settings.copy(yOffset = it) }
                            ControlSlider("GRAPH H", settings.graphH, 20f, 300f) { settings = settings.copy(graphH = it) }
                            ControlSlider("GRAPH W", settings.graphW, 50f, 800f) { settings = settings.copy(graphW = it) }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.White).padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isEncoding) {
                            VideoPreviewArea(
                                videoPath = videoPath,
                                videoLengthMs = videoLengthMs,
                                adjustedStartUtc = adjustedStartUtc,
                                telemetryPoints = telemetryPoints,
                                trimmedTelemetryPoints = trimmedTelemetryPoints,
                                settings = settings,
                                rendererProxy = rendererProxy,
                                textMeasurer = textMeasurer,
                                playerState = playerState,
                                videoCurrentTimeMs = videoCurrentTimeMs,
                                onCurrentTimeChange = { videoCurrentTimeMs = it },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            
                            Spacer(Modifier.height(8.dp))
                            
                            TelemetryTimelineGraph(
                                videoLengthMs = videoLengthMs,
                                adjustedStartUtc = adjustedStartUtc,
                                telemetryPoints = telemetryPoints,
                                trimStartSeconds = trimStartSeconds,
                                trimEndSeconds = trimEndSeconds,
                                splitPoints = viewModel.splitPoints,
                                videoCurrentTimeMs = videoCurrentTimeMs,
                                onTrimStartChange = { trimStartSeconds = it },
                                onTrimEndChange = { trimEndSeconds = it },
                                onSeek = { timeMs ->
                                    val ratio = if (videoLengthMs > 0) timeMs.toFloat() / videoLengthMs.toFloat() else 0f
                                    playerState.seekTo(ratio * 1000f)
                                    videoCurrentTimeMs = timeMs
                                },
                                modifier = Modifier.fillMaxWidth().height(140.dp)
                            )
                        } else {
                            val actualTrimStart = trimStartSeconds.coerceIn(0.0, videoLengthMs / 1000.0)
                            val actualTrimEnd = if (trimEndSeconds <= 0.0 || trimEndSeconds > videoLengthMs / 1000.0) {
                                videoLengthMs / 1000.0
                            } else {
                                trimEndSeconds
                            }
                            val trimmedDurationMs = ((actualTrimEnd - actualTrimStart) * 1000).toLong().coerceAtLeast(1000L)

                            EncodingProgressArea(
                                progress = progress,
                                statusText = statusText,
                                encodingPreviewImage = encodingPreviewImage,
                                showLivePreview = showLivePreview,
                                isPaused = isPaused,
                                videoLengthMs = trimmedDurationMs,
                                onPauseToggle = { isPaused = !isPaused },
                                onCancel = { isCanceled = true },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(8.dp))
                            val baseName = File(videoPath).name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
                            val totalParts = viewModel.getSplitRanges().size
                            val partSuffix = if (totalParts > 1) "_part*" else ""
                            val suffix = if (isSampleEncoding) "${partSuffix}_TEST_HUD.mp4" else "${partSuffix}_KMP_HUD.mp4"
                            val output = File(outputDir, baseName + suffix).absolutePath
                            val destInfo = if (moveOutputToSource) {
                                val sourceDir = File(videoPath).parentFile
                                val finalDest = if (sourceDir != null) File(sourceDir, baseName + suffix).absolutePath else output
                                "$output\n(Will be moved to: $finalDest)"
                            } else {
                                output
                            }
                            Card(
                                backgroundColor = Color(0xFFF2F2F7),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                                elevation = 0.dp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = if (isSampleEncoding) "SAMPLE ENCODING STATUS" else "HUD ENCODING STATUS",
                                        color = Color(0xFF007AFF),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Source:", color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                                        Text(videoPath, color = Color(0xFF1C1C1E), fontSize = 10.sp)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Output:", color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                                        Text(destInfo, color = Color(0xFF1C1C1E), fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        Text("1920x1080 Overlay Preview", color = Color(0xFF1C1C1E), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

var globalRendererProxy: fit.DynamicRendererProxy? = null

suspend fun fireEncode(
    s: HudSettings, 
    fit: String, 
    video: String, 
    outDir: String,
    startUtc: String, 
    onProgress: (Float, String) -> Unit,
    onFrame: (java.awt.image.BufferedImage) -> Unit,
    maxDurationSeconds: Int = -1,
    trimStartSeconds: Double = 0.0,
    trimEndSeconds: Double = -1.0,
    pauseSupplier: () -> Boolean = { false },
    cancelSupplier: () -> Boolean = { false },
    showLivePreviewSupplier: () -> Boolean = { true },
    partIndex: Int = -1,
    numParts: Int = 1
): String {
    println("DEBUG: fireEncode called with HudSettings: $s")
    return withContext(Dispatchers.IO) {
        try {
            val config = HudConfig(
                valSize = s.valSize, tightness = s.tightness, spacing = s.spacing,
                xOffset = s.xOffset, yOffset = s.yOffset, graphH = s.graphH, graphW = s.graphW
            )
            val proxy = DynamicRendererProxy(config)
            globalRendererProxy = proxy
            
            val encoder = NativeHudEncoder(s, 
                onProgress = { prog, status ->
                    println("PROGRESS: $status")
                    onProgress(prog, status)
                }, 
                onFrameRendered = onFrame,
                pauseSupplier = pauseSupplier,
                cancelSupplier = cancelSupplier,
                customRenderer = { canvas, point, allPoints, pBuf, progressRatio ->
                    proxy.renderFrame(canvas, point, allPoints, pBuf, progressRatio)
                },
                showLivePreviewSupplier = showLivePreviewSupplier
            )
            val videoFile = File(video)
            val baseName = videoFile.name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
            val partSuffix = if (partIndex >= 0 && numParts > 1) "_part${partIndex + 1}" else ""
            val resSuffix = when (s.exportResolution) {
                "1080p" -> "_1080p"
                "2.7k" -> "_2.7k"
                else -> "_orig"
            }
            val suffix = if (maxDurationSeconds > 0) {
                "${partSuffix}_TEST_HUD.mp4"
            } else {
                "${partSuffix}_KMP_HUD${resSuffix}.mp4"
            }
            val output = File(outDir, baseName + suffix).absolutePath
            encoder.encode(fit, video, output, startUtc, 
                maxDurationSeconds = maxDurationSeconds,
                trimStartSeconds = trimStartSeconds,
                trimEndSeconds = trimEndSeconds
            )
            output
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress(0f, "❌ Error: ${e.message ?: "Unknown error"}")
            throw e
        }
    }
}

suspend fun queryRoadName(lat: Double, lon: Double): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
            
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=ja&addressdetails=1&extratags=1"
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", "FitTrimmerApp/1.0 (yuuji@kamura.jp)")
                .timeout(java.time.Duration.ofSeconds(5))
                .build()
            
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val body = response.body()
                val road = extractJsonValue(body, "road")
                val ref = extractJsonValue(body, "ref")
                val city = extractJsonValue(body, "city")
                val town = extractJsonValue(body, "town")
                val village = extractJsonValue(body, "village")
                val suburb = extractJsonValue(body, "suburb")
                
                return@withContext buildCaptionText(ref, road, city, town, village, suburb)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}

fun extractJsonValue(json: String, key: String): String? {
    val regex = Regex("""\"$key\"\s*:\s*\"([^\"]+)\"""")
    val match = regex.find(json)
    if (match != null) {
        return unescapeUnicode(match.groupValues[1])
    }
    return null
}

fun buildCaptionText(
    ref: String?,
    road: String?,
    city: String?,
    town: String?,
    village: String?,
    suburb: String?
): String? {
    val roadName = road ?: ""
    val roadRef = ref ?: ""
    
    var mainRoadText = ""
    if (roadRef.isNotEmpty() && roadName.isNotEmpty()) {
        if (roadName.contains(roadRef) || roadRef.contains(roadName)) {
            mainRoadText = roadName
        } else {
            val formattedRef = if (roadRef.all { it.isDigit() }) {
                if (roadName.contains("県道")) "県道${roadRef}号"
                else if (roadName.contains("国道")) "国道${roadRef}号"
                else "r$roadRef"
            } else {
                roadRef
            }
            mainRoadText = "$formattedRef $roadName"
        }
    } else if (roadName.isNotEmpty()) {
        mainRoadText = roadName
    } else if (roadRef.isNotEmpty()) {
        mainRoadText = roadRef
    }
    
    if (mainRoadText.isEmpty()) return null
    
    val area = town ?: city ?: village ?: suburb ?: ""
    val areaSuffix = if (area.isNotEmpty()) "（$area 付近）" else ""
    
    return "$mainRoadText$areaSuffix"
}

fun unescapeUnicode(str: String): String {
    val regex = Regex("""\\u([0-9a-fA-F]{4})""")
    return regex.replace(str) { match ->
        val code = match.groupValues[1].toInt(16)
        code.toChar().toString()
    }
}

suspend fun detectRoadSegments(
    points: List<fit.FitParser.TelemetryPoint>,
    videoStartUtc: String,
    timeOffsetMillis: Long,
    videoDurationSeconds: Double,
    onProgress: (String) -> Unit
): List<RoadCaptionSegment> {
    if (points.isEmpty() || videoStartUtc.isEmpty()) return emptyList()
    
    val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
    val videoStartInstant = try {
        java.time.Instant.parse(videoStartUtc).plusMillis(timeOffsetMillis)
    } catch (e: Exception) {
        return emptyList()
    }
    
    val startFitTime = videoStartInstant.epochSecond - fitEpoch
    val endFitTime = startFitTime + videoDurationSeconds
    
    val rangePoints = points.filter { it.timestamp in startFitTime.toDouble()..endFitTime.toDouble() }
    if (rangePoints.isEmpty()) return emptyList()
    
    val segments = mutableListOf<RoadCaptionSegment>()
    var currentRoadName: String? = null
    var segmentStartSeconds = 0.0
    
    val sampleInterval = 15
    val numSteps = (videoDurationSeconds / sampleInterval).toInt().coerceAtLeast(1)
    
    for (i in 0..numSteps) {
        val currentOffset = i * sampleInterval.toDouble()
        if (currentOffset > videoDurationSeconds) break
        
        val targetFitTime = startFitTime + currentOffset
        val point = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - targetFitTime) } ?: continue
        
        onProgress("GPS解析中 (${(currentOffset / videoDurationSeconds * 100).toInt()}%): 座標 (${"%.4f".format(point.lat)}, ${"%.4f".format(point.lon)}) を検索中...")
        
        kotlinx.coroutines.delay(1000)
        
        val roadName = queryRoadName(point.lat, point.lon)
        
        if (roadName != null && roadName.isNotEmpty()) {
            if (currentRoadName == null) {
                currentRoadName = roadName
                segmentStartSeconds = currentOffset
            } else if (currentRoadName != roadName) {
                segments.add(
                    RoadCaptionSegment(
                        id = java.util.UUID.randomUUID().toString(),
                        startSeconds = segmentStartSeconds,
                        endSeconds = currentOffset,
                        text = currentRoadName,
                        isEnabled = true
                    )
                )
                currentRoadName = roadName
                segmentStartSeconds = currentOffset
            }
        } else {
            if (currentRoadName != null) {
                segments.add(
                    RoadCaptionSegment(
                        id = java.util.UUID.randomUUID().toString(),
                        startSeconds = segmentStartSeconds,
                        endSeconds = currentOffset,
                        text = currentRoadName,
                        isEnabled = true
                    )
                )
                currentRoadName = null
            }
        }
    }
    
    if (currentRoadName != null) {
        segments.add(
            RoadCaptionSegment(
                id = java.util.UUID.randomUUID().toString(),
                startSeconds = segmentStartSeconds,
                endSeconds = videoDurationSeconds,
                text = currentRoadName,
                isEnabled = true
            )
        )
    }
    
    return segments
}

@Composable
fun ControlSlider(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 1.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF1C1C1E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("%.1f".format(value), color = Color(0xFF007AFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, 
            onValueChange = onValueChange, 
            valueRange = min..max,
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF007AFF),
                activeTrackColor = Color(0xFF007AFF),
                inactiveTrackColor = Color(0xFFE5E5EA)
            )
        )
    }
}

fun runCli(args: Array<String>) {
    var fit: String? = null
    var video: String? = null
    var output: String? = null
    var startUtc: String? = null
    var duration = -1
    var trimStart = 0.0
    var trimEnd = -1.0

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--fit" -> fit = args.getOrNull(++i)
            "--video" -> video = args.getOrNull(++i)
            "--output" -> output = args.getOrNull(++i)
            "--video-start-utc" -> startUtc = args.getOrNull(++i)
            "--duration" -> duration = args.getOrNull(++i)?.toIntOrNull() ?: -1
            "--trim-start" -> trimStart = args.getOrNull(++i)?.toDoubleOrNull() ?: 0.0
            "--trim-end" -> trimEnd = args.getOrNull(++i)?.toDoubleOrNull() ?: -1.0
        }
        i++
    }

    if (fit == null || video == null || output == null || startUtc == null) {
        println("❌ Missing required arguments. Usage:")
        println("fit-trimmer --fit <fit> --video <video> --output <output> --video-start-utc <startUtc> [--duration <duration>] [--trim-start <seconds>] [--trim-end <seconds>]")
        kotlin.system.exitProcess(1)
    }

    println("🚀 Starting CLI Encode...")
    println("FIT: $fit")
    println("Video: $video")
    println("Output: $output")
    println("Start UTC: $startUtc")
    if (duration > 0) println("Duration Limit: ${duration}s")
    if (trimStart > 0.0) println("Trim Start: ${trimStart}s")
    if (trimEnd > 0.0) println("Trim End: ${trimEnd}s")

    // Load settings from GUI cache if available to respect user custom layout
    val settings = try {
        val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
        if (cacheFile.exists()) {
            val content = cacheFile.readText(kotlin.text.Charsets.UTF_8)
            // Custom simplified parser to avoid dependencies
            val cache = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<GuiPathCache>(content)
            cache.settings
        } else HudSettings()
    } catch (e: Exception) {
        HudSettings()
    }

    println("Loaded settings: $settings")

    kotlinx.coroutines.runBlocking {
        try {
            val encoder = NativeHudEncoder(settings,
                onProgress = { prog, status ->
                    print("\r$status")
                }
            )
            encoder.encode(fit, video, output, startUtc, 
                maxDurationSeconds = duration,
                trimStartSeconds = trimStart,
                trimEndSeconds = trimEnd
            )
            println("\n✨ CLI Encode Finished Successfully!")
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            println("\n❌ CLI Encode Failed: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(2)
        }
    }
}




@Composable
fun TimeAlignmentCard(
    state: TimeAlignmentState,
    videoCurrentTimeMs: Long,
    videoStartUtc: String,
    onVideoStartUtcChange: (String) -> Unit,
    videoPath: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    isAligning: Boolean,
    onAlignTelemetryClick: () -> Unit
) {
    Card(
        backgroundColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
        elevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("TIME ALIGNMENT (Time Offset)", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offset:", color = Color(0xFF636366), fontSize = 10.sp)
                Text(
                    "${if (state.seconds >= 0) "+" else ""}${String.format("%.1f", state.seconds)} s",
                    color = Color(0xFF007AFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Slider(
                value = state.seconds,
                onValueChange = { state.update((it * 1000).roundToInt()) },
                valueRange = -TimeAlignmentState.MAX_OFFSET_SECONDS..TimeAlignmentState.MAX_OFFSET_SECONDS,
                modifier = Modifier.height(20.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF007AFF),
                    activeTrackColor = Color(0xFF007AFF),
                    inactiveTrackColor = Color(0xFFE5E5EA)
                )
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val buttonModifier = Modifier.weight(1f).height(24.dp)
                val buttonColors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                
                val specs = listOf(
                    "-1s" to -1000,
                    "-0.1s" to -100,
                    "Reset" to 0,
                    "+0.1s" to 100,
                    "+1s" to 1000
                )
                
                for ((label, delta) in specs) {
                    OutlinedButton(
                        onClick = {
                            val nextVal = if (delta == 0) 0 else state.millis + delta
                            state.update(nextVal)
                        },
                        modifier = buttonModifier,
                        colors = buttonColors,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(label, fontSize = 9.sp)
                    }
                }
            }

            Divider(color = Color(0xFFE5E5EA))

            Text("AUTO IMU ALIGNMENT", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAlignTelemetryClick,
                    enabled = !isAligning && videoPath.isNotEmpty() && telemetryPoints.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF34C759),
                        contentColor = Color.White,
                        disabledBackgroundColor = Color(0xFFE5E5EA),
                        disabledContentColor = Color(0xFF8E8E93)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) {
                    if (isAligning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Aligning with IMU...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("IMU Sync (Auto)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Divider(color = Color(0xFFE5E5EA))

            Text("SYNC WITH CYCLING COMPUTER TIME", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp)

            var inputTimeText by remember { mutableStateOf("") }
            var isError by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputTimeText,
                    onValueChange = { 
                        inputTimeText = it 
                        isError = false
                    },
                    label = { Text("Display Time (HH:mm:ss)", color = Color(0xFF636366), fontSize = 10.sp) },
                    placeholder = { Text("16:20:30", color = Color(0xFFC7C7CC), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    textStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 11.sp),
                    singleLine = true,
                    isError = isError,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        backgroundColor = Color(0xFFF2F2F7),
                        focusedBorderColor = Color(0xFF007AFF),
                        unfocusedBorderColor = Color(0xFFD1D1D6),
                        textColor = Color(0xFF1C1C1E)
                    )
                )

                Button(
                    onClick = {
                        val cleanInput = inputTimeText.trim()
                        val parts = cleanInput.split(Regex("[:\\s\\-\\.]"))
                        val parsedTime = if (parts.size == 3) {
                            val h = parts[0].toIntOrNull()
                            val m = parts[1].toIntOrNull()
                            val s = parts[2].toIntOrNull()
                            if (h != null && m != null && s != null && h in 0..23 && m in 0..59 && s in 0..59) {
                                Triple(h, m, s)
                            } else null
                        } else if (cleanInput.length == 6) {
                            val h = cleanInput.substring(0, 2).toIntOrNull()
                            val m = cleanInput.substring(2, 4).toIntOrNull()
                            val s = cleanInput.substring(4, 6).toIntOrNull()
                            if (h != null && m != null && s != null && h in 0..23 && m in 0..59 && s in 0..59) {
                                Triple(h, m, s)
                            } else null
                        } else null

                        if (parsedTime != null) {
                            val (h, m, s) = parsedTime
                            try {
                                val baseInstant = if (videoStartUtc.isNotEmpty()) {
                                    java.time.Instant.parse(videoStartUtc)
                                } else {
                                    java.time.Instant.now()
                                }
                                val zoneId = java.time.ZoneId.systemDefault()
                                val zonedDateTime = java.time.ZonedDateTime.ofInstant(baseInstant, zoneId)
                                    .withHour(h)
                                    .withMinute(m)
                                    .withSecond(s)
                                    .withNano(0)

                                val frameInstant = zonedDateTime.toInstant()
                                val newStartInstant = frameInstant.minusMillis(videoCurrentTimeMs)

                                onVideoStartUtcChange(newStartInstant.toString())
                                state.update(0) // Reset manual offset slider
                                inputTimeText = ""
                                isError = false
                            } catch (e: Exception) {
                                e.printStackTrace()
                                isError = true
                            }
                        } else {
                            isError = true
                        }
                    },
                    modifier = Modifier.height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF007AFF),
                        contentColor = Color.White
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) {
                    Text("Sync", fontSize = 11.sp)
                }
            }

            if (isError) {
                Text("Invalid format. Use HH:mm:ss (e.g. 16:20:30)", color = Color(0xFFFF3B30), fontSize = 9.sp)
            }
        }
    }
}

@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun VideoTrimCard(
    videoLengthMs: Long,
    trimStartSeconds: Double,
    trimEndSeconds: Double,
    onTrimStartChange: (Double) -> Unit,
    onTrimEndChange: (Double) -> Unit
) {
    val totalSec = videoLengthMs / 1000.0
    val range = trimStartSeconds.toFloat()..trimEndSeconds.toFloat()
    
    Card(
        backgroundColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
        elevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("VIDEO TRIM RANGE", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Start:", color = Color(0xFF636366), fontSize = 9.sp)
                    Text(utils.formatTime((trimStartSeconds * 1000).toLong()), color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("End:", color = Color(0xFF636366), fontSize = 9.sp)
                    Text(utils.formatTime((trimEndSeconds * 1000).toLong()), color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    onTrimStartChange(r.start.toDouble())
                    onTrimEndChange(r.endInclusive.toDouble())
                },
                valueRange = 0f..totalSec.toFloat(),
                modifier = Modifier.height(20.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF007AFF),
                    activeTrackColor = Color(0xFF007AFF),
                    inactiveTrackColor = Color(0xFFE5E5EA)
                )
            )

            val durationSec = trimEndSeconds - trimStartSeconds
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trim Duration:", color = Color(0xFF636366), fontSize = 9.sp)
                Text(
                    utils.formatTime((durationSec * 1000).toLong()) + " (${String.format("%.1f", durationSec)}s)",
                    color = Color(0xFF2E7D32),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VideoSplitCard(
    viewModel: AppViewModel,
    videoCurrentTimeMs: Long
) {
    val splitPoints = viewModel.splitPoints
    val ranges = viewModel.getSplitRanges()
    
    Card(
        backgroundColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
        elevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("VIDEO SPLIT POINTS", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
            
            val playheadSec = videoCurrentTimeMs / 1000.0
            val canAdd = playheadSec > viewModel.trimStartSeconds && playheadSec < viewModel.trimEndSeconds && playheadSec !in splitPoints
            
            Button(
                onClick = { viewModel.addSplitPoint(playheadSec) },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF007AFF),
                    contentColor = Color.White
                )
            ) {
                Text("Add Split at Playhead (${utils.formatTime(videoCurrentTimeMs)})", fontSize = 10.sp)
            }
            
            if (splitPoints.isNotEmpty()) {
                Text("Current Splits:", color = Color(0xFF636366), fontSize = 9.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    splitPoints.forEach { splitSec ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = utils.formatTime((splitSec * 1000).toLong()),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1C1C1E)
                            )
                            Button(
                                onClick = { viewModel.removeSplitPoint(splitSec) },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFFFF3B30),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.height(24.dp).padding(0.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Remove", fontSize = 9.sp)
                            }
                        }
                    }
                }
                
                Button(
                    onClick = { viewModel.clearSplitPoints() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFFF3B30),
                        contentColor = Color.White
                    )
                ) {
                    Text("Clear All Splits", fontSize = 10.sp)
                }
            } else {
                Text("No split points set.", color = Color(0xFF8E8E93), fontSize = 10.sp)
            }
            
            Divider(color = Color(0xFFE5E5EA))
            
            Text(
                text = "${ranges.size} video parts will be encoded.",
                color = Color(0xFF2E7D32),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


