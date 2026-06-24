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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
// VLC dependency removed


@Serializable
data class GuiPathCache(
    val fitPath: String,
    val videoPath: String,
    val videoStartUtc: String,
    val settings: HudSettings = HudSettings(),
    val moveOutputToSource: Boolean = false,
    val showLivePreview: Boolean = true,
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null
)

fun pickFile(title: String, extensions: List<String>): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    return if (dialog.file != null) File(dialog.directory, dialog.file).absolutePath else null
}

fun pickFolder(title: String): String? {
    val chooser = javax.swing.JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else null
}

@OptIn(ExperimentalTextApi::class)
fun main(args: Array<String>) {
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("jna.library.path", "C:\\Program Files\\VideoLAN\\VLC")
    if (args.contains("--test")) {
        runTest()
        return
    }
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
    val initialCache = remember {
        try {
            val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
            if (cacheFile.exists()) {
                val content = cacheFile.readText()
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<GuiPathCache>(content)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    val windowState = rememberWindowState(
        position = if (initialCache?.windowX != null && initialCache.windowY != null) {
            androidx.compose.ui.window.WindowPosition(initialCache.windowX.dp, initialCache.windowY.dp)
        } else {
            androidx.compose.ui.window.WindowPosition.PlatformDefault
        },
        size = androidx.compose.ui.unit.DpSize(
            width = (initialCache?.windowWidth ?: 1400f).dp,
            height = (initialCache?.windowHeight ?: 900f).dp
        )
    )

    var settings by remember { mutableStateOf(initialCache?.settings ?: HudSettings()) }
    var fitPath by remember { mutableStateOf(initialCache?.fitPath ?: "") }
    var videoPath by remember { mutableStateOf(initialCache?.videoPath ?: "") }
    var outputDir by remember { mutableStateOf(System.getProperty("user.home") + File.separator + "Downloads") }
    var videoStartUtc by remember { mutableStateOf(initialCache?.videoStartUtc ?: "2026-06-21T02:09:49Z") }
    var isEncoding by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isCanceled by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var videoPreviewImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var encodingPreviewImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var statusText by remember { mutableStateOf("") }
    var hudSettingsExpanded by remember { mutableStateOf(false) }
    var isLoaded by remember { mutableStateOf(true) }

    // C Drive Monitor State
    var cDriveFreeSpaceGB by remember { mutableStateOf(0.0) }
    var cDriveTotalSpaceGB by remember { mutableStateOf(0.0) }
    var requiredSpaceGB by remember { mutableStateOf(2.0) }
    var hasEnoughSpace by remember { mutableStateOf(true) }
    var appTempSpaceGB by remember { mutableStateOf(0.0) }

    // Dynamic Hud Proxy & Hot Reload State
    val hudConfig = remember(settings) {
        fit.HudConfig(
            valSize = settings.valSize,
            tightness = settings.tightness,
            spacing = settings.spacing,
            xOffset = settings.xOffset,
            yOffset = settings.yOffset,
            graphH = settings.graphH,
            graphW = settings.graphW
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
    var moveOutputToSource by remember { mutableStateOf(initialCache?.moveOutputToSource ?: false) }
    var showLivePreview by remember { mutableStateOf(initialCache?.showLivePreview ?: true) }

    // Telemetry state
    val vlcAvailable = false
    var telemetryPoints by remember { mutableStateOf<List<FitParser.TelemetryPoint>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoLengthMs by remember { mutableStateOf(0L) }
    var videoCurrentTimeMs by remember { mutableStateOf(0L) }
    var previewTimeMs by remember { mutableStateOf(0L) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying) {
                previewTimeMs = videoCurrentTimeMs
                kotlinx.coroutines.delay(250)
            }
        } else {
            previewTimeMs = videoCurrentTimeMs
        }
    }

    val fitStartInstant = remember(telemetryPoints) {
        if (telemetryPoints.isNotEmpty()) {
            try {
                java.time.Instant.ofEpochSecond(telemetryPoints.first().timestamp.toLong() + 631065600L)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val fitEndInstant = remember(telemetryPoints) {
        if (telemetryPoints.isNotEmpty()) {
            try {
                java.time.Instant.ofEpochSecond(telemetryPoints.last().timestamp.toLong() + 631065600L)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val videoStartInstant = remember(videoStartUtc) {
        try {
            if (videoStartUtc.isNotEmpty()) java.time.Instant.parse(videoStartUtc) else null
        } catch (e: Exception) {
            null
        }
    }

    val videoEndInstant = remember(videoStartInstant, videoLengthMs) {
        if (videoStartInstant != null && videoLengthMs > 0) {
            try {
                videoStartInstant.plusMillis(videoLengthMs)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val isVideoInFitRange = remember(fitStartInstant, fitEndInstant, videoStartInstant, videoEndInstant) {
        if (fitStartInstant != null && fitEndInstant != null && videoStartInstant != null && videoEndInstant != null) {
            (!videoStartInstant.isBefore(fitStartInstant)) && (!videoEndInstant.isAfter(fitEndInstant))
        } else {
            true
        }
    }

    val trimmedTelemetryPoints = remember(telemetryPoints, videoStartInstant, videoEndInstant) {
        if (telemetryPoints.isNotEmpty() && videoStartInstant != null && videoEndInstant != null) {
            try {
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val videoStartFit = videoStartInstant.epochSecond - fitEpoch
                val videoEndFit = videoEndInstant.epochSecond - fitEpoch
                val filtered = telemetryPoints.filter { it.timestamp in videoStartFit.toDouble()..videoEndFit.toDouble() }
                if (filtered.isNotEmpty()) filtered else telemetryPoints
            } catch (e: Exception) {
                telemetryPoints
            }
        } else {
            telemetryPoints
        }
    }

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
     * Safety multiplier factor is set to 5.5:
     * 1. CRF 18 high-quality encoding can expand compressed videos (especially 4K) by up to 2.5x.
     * 2. During the final segment concat merge, the temporary .ts chunks and the final output video
     *    co-exist on the disk temporarily, adding another 2.5x file size footprint (2.5x + 2.5x = 5.0x).
     * 3. A safety multiplier of 5.5 (plus an extra safety buffer of 2.0 GiB) helps prevent running out of space.
     */
    fun estimateRequiredSpaceGiB(sizeBytes: Long): Double {
        val safetyMultiplier = 5.5
        val bufferGiB = 2.0
        val gibBytes = 1024.0 * 1024.0 * 1024.0
        return (sizeBytes * safetyMultiplier) / gibBytes + bufferGiB
    }

    LaunchedEffect(videoPath) {
        if (videoPath.isNotEmpty()) {
            val f = File(videoPath)
            if (f.exists()) {
                requiredSpaceGB = estimateRequiredSpaceGiB(f.length())
            } else {
                requiredSpaceGB = 2.0
            }
        } else {
            requiredSpaceGB = 2.0
        }
    }

    LaunchedEffect(requiredSpaceGB, isEncoding, isPaused) {
        launch(Dispatchers.IO) {
            while (true) {
                try {
                    val file = File("C:\\")
                    cDriveFreeSpaceGB = file.freeSpace / (1024.0 * 1024.0 * 1024.0)
                    cDriveTotalSpaceGB = file.totalSpace / (1024.0 * 1024.0 * 1024.0)
                    hasEnoughSpace = cDriveFreeSpaceGB >= requiredSpaceGB
                    
                    if (isEncoding && !hasEnoughSpace && !isPaused) {
                        isPaused = true
                        statusText = "PAUSED (Not enough space on C: drive!)"
                    }

                    // Calculate temp_work size dynamically
                    val workDir = fit.PathResolver.getTempWorkDir()
                    var totalBytes = 0L
                    
                    fun getDirSize(dir: File): Long {
                        var size = 0L
                        dir.listFiles()?.forEach { f ->
                            size += if (f.isDirectory) getDirSize(f) else f.length()
                        }
                        return size
                    }
                    
                    if (workDir.exists()) totalBytes += getDirSize(workDir)
                    appTempSpaceGB = totalBytes / (1024.0 * 1024.0 * 1024.0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    val togglePlay = {
        if (isPlaying) {
            isPlaying = false
        } else {
            if (videoCurrentTimeMs >= videoLengthMs) {
                videoCurrentTimeMs = 0L
            }
            isPlaying = true
        }
    }

    val seekTo = { timeMs: Long ->
        val target = timeMs.coerceIn(0L, videoLengthMs)
        videoCurrentTimeMs = target
        previewTimeMs = target
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis()
            val startMs = videoCurrentTimeMs
            while (isPlaying) {
                val elapsed = System.currentTimeMillis() - startTime
                val nextMs = startMs + elapsed
                if (nextMs >= videoLengthMs) {
                    videoCurrentTimeMs = videoLengthMs
                    isPlaying = false
                    break
                }
                videoCurrentTimeMs = nextMs
                kotlinx.coroutines.delay(30)
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
                    telemetryPoints = parser.getTelemetry()
                } catch (e: Exception) {
                    e.printStackTrace()
                    telemetryPoints = emptyList()
                }
            }
        } else {
            telemetryPoints = emptyList()
        }
    }

    val currentPoint = remember(videoCurrentTimeMs, telemetryPoints, videoStartUtc) {
        if (telemetryPoints.isEmpty() || videoStartUtc.isEmpty()) null
        else {
            try {
                val startTime = java.time.Instant.parse(videoStartUtc)
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val elapsedSeconds = videoCurrentTimeMs / 1000.0
                val currentUtc = startTime.toEpochMilli() / 1000.0 + elapsedSeconds
                val currentFitTs = currentUtc - fitEpoch
                telemetryPoints.find { it.timestamp >= currentFitTs } ?: telemetryPoints.lastOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    val currentTrendPoints = remember(currentPoint, telemetryPoints) {
        if (currentPoint == null || telemetryPoints.isEmpty()) emptyList<Double>()
        else {
            val idx = telemetryPoints.indexOfFirst { it.timestamp >= currentPoint.timestamp }
            if (idx == -1) {
                emptyList()
            } else {
                val startIdx = maxOf(0, idx - 30)
                telemetryPoints.subList(startIdx, idx + 1).map { it.power }
            }
        }
    }

    // Save path cache when modified
    LaunchedEffect(fitPath, videoPath, videoStartUtc, settings, moveOutputToSource, showLivePreview, windowState.position, windowState.size) {
        if (isLoaded) {
            kotlinx.coroutines.delay(500)
            withContext(Dispatchers.IO) {
                try {
                    // Save GUI Cache to home directory (always, even if paths are empty)
                    val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
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
                        settings = settings,
                        moveOutputToSource = moveOutputToSource,
                        showLivePreview = showLivePreview,
                        windowX = x,
                        windowY = y,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    cacheFile.writeText(Json.encodeToString(cache))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(videoPath) {
        if (videoPath.isNotEmpty() && File(videoPath).exists()) {
            withContext(Dispatchers.IO) {
                val ffmpegPath = fit.findFfmpegPath()
                try {
                    // Extract duration using ffmpeg -i output if VLC isn't active
                    val pbDur = ProcessBuilder(ffmpegPath, "-i", videoPath)
                    pbDur.redirectErrorStream(true)
                    val pDur = pbDur.start()
                    val output = pDur.inputStream.bufferedReader().readText()
                    pDur.waitFor()
                    
                    val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
                    val durMatch = durRegex.find(output)
                    if (durMatch != null) {
                        val h = durMatch.groupValues[1].toInt()
                        val m = durMatch.groupValues[2].toInt()
                        val s = durMatch.groupValues[3].toInt()
                        val ms = durMatch.groupValues[4].padEnd(3, '0').take(3).toInt()
                        val totalMs = (h * 3600 + m * 60 + s) * 1000L + ms
                        videoLengthMs = totalMs
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    val videoFile = File(videoPath)
                    println("DEBUG: LaunchedEffect(videoPath) - file exists: ${videoFile.exists()}, size: ${videoFile.length()}")
                    if (videoFile.exists()) {
                        val parser = mp4.Mp4Parser()
                        val scanSize = minOf(videoFile.length(), 100L * 1024 * 1024).toInt()
                        
                        // 1. Scan head
                        val headBytes = videoFile.inputStream().use { it.readNBytes(scanSize) }
                        var meta = parser.parse(headBytes)
                        
                        // 2. If not found, scan tail
                        if (meta == null && videoFile.length() > scanSize) {
                            println("DEBUG: mvhd not found in head 100MB. Scanning tail 100MB...")
                            val tailOffset = videoFile.length() - scanSize
                            videoFile.inputStream().use { input ->
                                input.skip(tailOffset)
                                val tailBytes = input.readNBytes(scanSize)
                                meta = parser.parse(tailBytes)
                            }
                        }
                        
                        if (meta != null) {
                            val unixStart = meta.creationTimeSeconds - 2082844800L
                            val startUtcStr = java.time.Instant.ofEpochSecond(unixStart).toString()
                            javax.swing.SwingUtilities.invokeLater {
                                videoStartUtc = startUtcStr
                            }
                            println("DEBUG: Successfully parsed video Start UTC: $startUtcStr")
                        } else {
                            println("DEBUG: Failed to parse video metadata (mvhd not found in head or tail)")
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: Exception during video start UTC parse: ${e.message}")
                    e.printStackTrace()
                }

                try {
                    val tempThumb = File("temp_preview.jpg")
                    val pb = ProcessBuilder(ffmpegPath, "-y", "-i", videoPath, "-ss", "00:00:01", "-vframes", "1", tempThumb.absolutePath)
                    pb.start().waitFor()
                    if (tempThumb.exists()) {
                        videoPreviewImage = Image.makeFromEncoded(tempThumb.readBytes()).asImageBitmap()
                        tempThumb.delete()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    LaunchedEffect(videoPath, previewTimeMs, isEncoding) {
        if (isEncoding) return@LaunchedEffect
        if (videoPath.isNotEmpty() && File(videoPath).exists()) {
            kotlinx.coroutines.delay(50)
            withContext(Dispatchers.IO) {
                val ffmpegPath = fit.findFfmpegPath()
                var proc: Process? = null
                try {
                    val timeSec = previewTimeMs / 1000.0
                    val pb = ProcessBuilder(
                        ffmpegPath, "-y",
                        "-loglevel", "quiet",
                        "-ss", String.format(java.util.Locale.US, "%.3f", timeSec),
                        "-i", videoPath,
                        "-vframes", "1",
                        "-f", "image2pipe",
                        "-vcodec", "mjpeg",
                        "-"
                    )
                    proc = pb.start()
                    val bytes = proc.inputStream.readBytes()
                    proc.waitFor()
                    if (proc.exitValue() == 0 && bytes.isNotEmpty()) {
                        val bitmap = Image.makeFromEncoded(bytes).asImageBitmap()
                        javax.swing.SwingUtilities.invokeLater {
                            videoPreviewImage = bitmap
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    proc?.destroyForcibly()
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    proc?.destroy()
                }
            }
        }
    }

    val cp = remember {
        ControlPlane(
            onCommand = { cmd ->
                javax.swing.SwingUtilities.invokeLater {
                    when (cmd) {
                        is CpCommand.SetLayout -> settings = cmd.settings
                        is CpCommand.SetFiles -> {
                            fitPath = cmd.fit
                            videoPath = cmd.video
                            cmd.startUtc?.let { videoStartUtc = it }
                        }
                        is CpCommand.Fire -> {
                            scope.launch {
                                encodingPreviewImage = null
                                isEncoding = true
                                isPaused = false
                                isCanceled = false
                                try {
                                    fireEncode(settings, fitPath, videoPath, outputDir, videoStartUtc,
                                        onProgress = { prog, status ->
                                            progress = prog
                                            statusText = status
                                            if (videoLengthMs > 0) {
                                                val currentMs = (prog * videoLengthMs).toLong()
                                                javax.swing.SwingUtilities.invokeLater {
                                                    videoCurrentTimeMs = currentMs
                                                    previewTimeMs = currentMs
                                                }
                                            }
                                        },
                                        onFrame = { bufferedImg ->
                                            val bitmap = bufferedImg.toComposeImageBitmap()
                                            javax.swing.SwingUtilities.invokeLater {
                                                encodingPreviewImage = bitmap
                                            }
                                        },
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
                    }
                }
            },
            getState = { CpState(settings, fitPath, videoPath, isEncoding, progress) }
        )
    }
    
    LaunchedEffect(Unit) { cp.start() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "HUD エンコーダー",
        state = windowState
    ) {
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


                    val onNativeEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, isVideoInFitRange) {
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
                                    isEncoding = true
                                    isPaused = false
                                    isCanceled = false
                                    try {
                                        val outPath = fireEncode(settings, fitPath, videoPath, outputDir, videoStartUtc,
                                            onProgress = { prog, status ->
                                                progress = prog
                                                statusText = status
                                                if (videoLengthMs > 0) {
                                                    val currentMs = (prog * videoLengthMs).toLong()
                                                    javax.swing.SwingUtilities.invokeLater {
                                                        videoCurrentTimeMs = currentMs
                                                        previewTimeMs = currentMs
                                                    }
                                                }
                                            },
                                            onFrame = { bufferedImg ->
                                                val bitmap = bufferedImg.toComposeImageBitmap()
                                                javax.swing.SwingUtilities.invokeLater {
                                                    encodingPreviewImage = bitmap
                                                }
                                            },
                                            pauseSupplier = { isPaused },
                                            cancelSupplier = { isCanceled },
                                            showLivePreviewSupplier = { showLivePreview }
                                        )
                                        if (moveOutputToSource && !isCanceled) {
                                            statusText = "Moving file to source directory..."
                                            val sourceDir = File(targetVideoPath).parentFile
                                            val outFile = File(outPath)
                                            if (sourceDir != null && sourceDir.exists()) {
                                                val destFile = File(sourceDir, outFile.name)
                                                withContext(Dispatchers.IO) {
                                                    java.nio.file.Files.copy(outFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                    outFile.delete()
                                                }
                                            }
                                        }
                                        statusText = if (isCanceled) "Encoding Canceled" else "✨ Finished Successfully!"
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
                                        // Handled and displayed in statusText
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
                                    }
                                }
                            }
                            Unit
                        }
                    }

                    val onSampleEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, isVideoInFitRange) {
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
                                    isEncoding = true
                                    isPaused = false
                                    isCanceled = false
                                    try {
                                        val outPath = fireEncode(settings, fitPath, videoPath, outputDir, videoStartUtc,
                                            onProgress = { prog, status ->
                                                progress = prog
                                                statusText = status
                                                if (videoLengthMs > 0) {
                                                    val currentMs = (prog * videoLengthMs).toLong()
                                                    javax.swing.SwingUtilities.invokeLater {
                                                        videoCurrentTimeMs = currentMs
                                                        previewTimeMs = currentMs
                                                    }
                                                }
                                            },
                                            onFrame = { bufferedImg ->
                                                val bitmap = bufferedImg.toComposeImageBitmap()
                                                javax.swing.SwingUtilities.invokeLater {
                                                    encodingPreviewImage = bitmap
                                                }
                                            },
                                            maxDurationSeconds = 5,
                                            pauseSupplier = { isPaused },
                                            cancelSupplier = { isCanceled },
                                            showLivePreviewSupplier = { showLivePreview }
                                        )
                                        if (moveOutputToSource && !isCanceled) {
                                            statusText = "Moving file to source directory..."
                                            val sourceDir = File(targetVideoPath).parentFile
                                            val outFile = File(outPath)
                                            if (sourceDir != null && sourceDir.exists()) {
                                                val destFile = File(sourceDir, outFile.name)
                                                withContext(Dispatchers.IO) {
                                                    java.nio.file.Files.copy(outFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                    outFile.delete()
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

                                  if (videoStartInstant != null && videoEndInstant != null) {
                                      val videoStartStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                          .withZone(java.time.ZoneId.systemDefault()).format(videoStartInstant)
                                      val videoEndStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                          .withZone(java.time.ZoneId.systemDefault()).format(videoEndInstant)

                                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                          Text("VIDEO TIMELINE", color = Color(0xFF636366), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                          Text("$videoStartStr  ~\n$videoEndStr", color = Color(0xFF1C1C1E), fontSize = 11.sp)
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
                                  }
                              }
                        }
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
                                Text("C-DRIVE SPACE MONITOR", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
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
                                            val workDir = fit.PathResolver.getTempWorkDir()
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

                        OutlinedButton(
                            onClick = onSampleEncodeClick,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            enabled = hasEnoughSpace && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF007AFF),
                                disabledContentColor = Color(0xFF8E8E93)
                            ),
                            border = BorderStroke(1.5.dp, if (hasEnoughSpace && fitPath.isNotEmpty() && videoPath.isNotEmpty()) Color(0xFF007AFF) else Color(0xFFE5E5EA)),
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

                Box(modifier = Modifier.fillMaxSize().background(Color.White).padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .aspectRatio(16f / 9f)
                                .fillMaxWidth()
                                .background(Color.Black, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        ) {
                            videoPreviewImage?.let {
                                androidx.compose.foundation.Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            } ?: Box(Modifier.fillMaxSize().background(Color.Black))

                            if (isEncoding) {
                                if (showLivePreview) {
                                    encodingPreviewImage?.let { hudImg ->
                                         Canvas(modifier = Modifier.fillMaxSize()) {
                                             drawImage(
                                                 image = hudImg,
                                                 dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                             )
                                         }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color(0xE0101012)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Live Preview Disabled\n(Encoding is running)",
                                            color = Color.Gray,
                                            fontSize = 14.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = { isPaused = !isPaused },
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = if (isPaused) Color(0xFF10B981) else Color(0xFFF59E0B)
                                        )
                                    ) {
                                        Text(if (isPaused) "▶ RESUME" else "⏸ PAUSE", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    Button(
                                        onClick = { isCanceled = true },
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEF4444))
                                    ) {
                                        Text("⏹ CANCEL", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                val density = androidx.compose.ui.platform.LocalDensity.current.density
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val scale = size.width / 1920f
                                    val currentRatio = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f
                                    val telemetryPoint = currentPoint ?: fit.FitParser.TelemetryPoint(
                                        timestamp = 0.0,
                                        speed = 26.2,
                                        power = 175.0,
                                        cadence = 79.0,
                                        heartRate = 148.0,
                                        elevation = 63.2,
                                        grade = 4.0
                                    )
                                    val pBuf = currentTrendPoints.map { it }
                                    val composeCanvas = ComposeHudCanvas(this, textMeasurer, scale, density)
                                    settings.hashCode()
                                    rendererProxy.renderFrame(
                                        composeCanvas,
                                        telemetryPoint,
                                        trimmedTelemetryPoints,
                                        pBuf,
                                        currentRatio
                                    )
                                }
                            }
                        }

                        // Video Player Control UI
                        if (videoPath.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = togglePlay,
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF))
                                ) {
                                    Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                Text(
                                    text = "${formatTime(videoCurrentTimeMs)} / ${formatTime(videoLengthMs)}",
                                    color = Color(0xFF1C1C1E),
                                    fontSize = 11.sp
                                )

                                Slider(
                                    value = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f,
                                    onValueChange = { ratio ->
                                        val targetTime = (ratio * videoLengthMs).toLong()
                                        seekTo(targetTime)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF007AFF),
                                        activeTrackColor = Color(0xFF007AFF),
                                        inactiveTrackColor = Color(0xFFE5E5EA)
                                    )
                                )

                                // VLC Status Badge
                                Text(
                                    text = if (vlcAvailable) "VLC ACTIVE" else "NO VLC (SIM)",
                                    color = if (vlcAvailable) Color(0xFF2E7D32) else Color(0xFFD84315),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
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
    pauseSupplier: () -> Boolean = { false },
    cancelSupplier: () -> Boolean = { false },
    showLivePreviewSupplier: () -> Boolean = { true }
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
            val suffix = if (maxDurationSeconds > 0) "_TEST_HUD.mp4" else "_KMP_HUD.mp4"
            val output = File(outDir, baseName + suffix).absolutePath
            encoder.encode(fit, video, output, startUtc, maxDurationSeconds = maxDurationSeconds)
            output
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress(0f, "❌ Error: ${e.message ?: "Unknown error"}")
            throw e
        }
    }
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

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--fit" -> fit = args.getOrNull(++i)
            "--video" -> video = args.getOrNull(++i)
            "--output" -> output = args.getOrNull(++i)
            "--video-start-utc" -> startUtc = args.getOrNull(++i)
            "--duration" -> duration = args.getOrNull(++i)?.toIntOrNull() ?: -1
        }
        i++
    }

    if (fit == null || video == null || output == null || startUtc == null) {
        println("❌ Missing required arguments. Usage:")
        println("fit-trimmer --fit <fit> --video <video> --output <output> --video-start-utc <startUtc> [--duration <duration>]")
        kotlin.system.exitProcess(1)
    }

    println("🚀 Starting CLI Encode...")
    println("FIT: $fit")
    println("Video: $video")
    println("Output: $output")
    println("Start UTC: $startUtc")
    if (duration > 0) println("Duration Limit: ${duration}s")

    // Load settings from GUI cache if available to respect user custom layout
    val settings = try {
        val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
        if (cacheFile.exists()) {
            val content = cacheFile.readText()
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
            encoder.encode(fit, video, output, startUtc, maxDurationSeconds = duration)
            println("\n✨ CLI Encode Finished Successfully!")
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            println("\n❌ CLI Encode Failed: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(2)
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

class ComposeHudCanvas(
    private val drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    private val textMeasurer: androidx.compose.ui.text.TextMeasurer,
    private val scale: Float,
    private val density: Float
) : fit.HudCanvas {

    private fun parseColor(colorStr: String): androidx.compose.ui.graphics.Color {
        val clean = colorStr.replace("#", "")
        return try {
            if (clean.length == 8) {
                androidx.compose.ui.graphics.Color(clean.toLong(16))
            } else if (clean.length == 6) {
                androidx.compose.ui.graphics.Color((0xFF000000 or clean.toLong(16)))
            } else {
                androidx.compose.ui.graphics.Color.White
            }
        } catch (e: Exception) {
            androidx.compose.ui.graphics.Color.White
        }
    }

    override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
        val c = parseColor(color)
        val style = androidx.compose.ui.text.TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            color = c,
            fontSize = (size * scale / density).sp,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
        )
        val layout = textMeasurer.measure(text, style)
        val drawX = x * scale - when (anchor) {
            "center" -> layout.size.width.toFloat() / 2f
            "top-right", "bottom-right" -> layout.size.width.toFloat()
            else -> 0f
        }
        val drawY = y * scale - when (anchor) {
            "center" -> layout.size.height.toFloat() / 2f
            "bottom-left", "bottom-right" -> layout.size.height.toFloat()
            else -> 0f
        }
        drawScope.drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(drawX, drawY))
    }

    override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {
        val c = parseColor(color)
        drawScope.drawRect(
            color = c,
            topLeft = androidx.compose.ui.geometry.Offset(x * scale, y * scale),
            size = androidx.compose.ui.geometry.Size(w * scale, h * scale),
            alpha = alpha,
            style = if (outline) androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * scale) else androidx.compose.ui.graphics.drawscope.Fill
        )
    }

    override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {
        if (points.size < 2) return
        val c = parseColor(color)
        for (i in 0 until points.size - 1) {
            drawScope.drawLine(
                color = c,
                start = androidx.compose.ui.geometry.Offset(points[i].first * scale, points[i].second * scale),
                end = androidx.compose.ui.geometry.Offset(points[i + 1].first * scale, points[i + 1].second * scale),
                strokeWidth = width * scale,
                alpha = alpha
            )
        }
    }

    override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {
        if (points.size < 3) return
        val c = parseColor(color)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points[0].first * scale, points[0].second * scale)
            for (i in 1 until points.size) {
                lineTo(points[i].first * scale, points[i].second * scale)
            }
            close()
        }
        drawScope.drawPath(path = path, color = c, alpha = alpha)
    }

    override fun getTextWidth(text: String, size: Float, bold: Boolean): Float {
        val style = androidx.compose.ui.text.TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            fontSize = (size / density).sp,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
        )
        val layout = textMeasurer.measure(text, style)
        return layout.size.width.toFloat()
    }
}

