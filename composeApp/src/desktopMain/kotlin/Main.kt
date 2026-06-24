import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.awt.SwingPanel
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent


@Serializable
data class GuiPathCache(
    val fitPath: String,
    val videoPath: String,
    val videoStartUtc: String,
    val settings: HudSettings = HudSettings(),
    val moveOutputToSource: Boolean = false
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

@OptIn(ExperimentalTextApi::class)
fun startGui(args: Array<String>) = application {
    var settings by remember { mutableStateOf(HudSettings()) }
    var fitPath by remember { mutableStateOf("") }
    var videoPath by remember { mutableStateOf("") }
    var outputDir by remember { mutableStateOf(System.getProperty("user.home") + File.separator + "Downloads") }
    var videoStartUtc by remember { mutableStateOf("2026-06-21T02:09:49Z") }
    var isEncoding by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isCanceled by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var videoPreviewImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var encodingPreviewImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var statusText by remember { mutableStateOf("") }
    var hudSettingsExpanded by remember { mutableStateOf(false) }
    var isLoaded by remember { mutableStateOf(false) }

    // C Drive Monitor State
    var cDriveFreeSpaceGB by remember { mutableStateOf(0.0) }
    var cDriveTotalSpaceGB by remember { mutableStateOf(0.0) }
    var requiredSpaceGB by remember { mutableStateOf(2.0) }
    var hasEnoughSpace by remember { mutableStateOf(true) }

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
    var moveOutputToSource by remember { mutableStateOf(false) }

    // VLC / Telemetry state
    var vlcAvailable by remember { mutableStateOf(false) }
    var telemetryPoints by remember { mutableStateOf<List<FitParser.TelemetryPoint>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoLengthMs by remember { mutableStateOf(0L) }
    var videoCurrentTimeMs by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                vlcAvailable = NativeDiscovery().discover()
            } catch (e: Throwable) {
                e.printStackTrace()
                vlcAvailable = false
            }
            // Auto-cleanup temporary workspaces on startup
            try {
                val workDir = File(System.getProperty("user.dir"), "temp_work")
                val hudDir = File(System.getProperty("user.dir"), "tmp_hud")
                if (workDir.exists()) workDir.deleteRecursively()
                if (hudDir.exists()) hudDir.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(videoPath) {
        if (videoPath.isNotEmpty()) {
            val f = File(videoPath)
            if (f.exists()) {
                val sizeBytes = f.length()
                requiredSpaceGB = (sizeBytes * 1.5) / (1024.0 * 1024.0 * 1024.0) + 2.0
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    val mediaPlayerComponent = remember(vlcAvailable) {
        if (vlcAvailable) {
            try {
                CallbackMediaPlayerComponent()
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
        } else null
    }

    DisposableEffect(mediaPlayerComponent) {
        onDispose {
            mediaPlayerComponent?.release()
        }
    }

    val togglePlay = {
        if (vlcAvailable && mediaPlayerComponent != null) {
            val player = mediaPlayerComponent.mediaPlayer()
            if (isPlaying) {
                player.controls().pause()
                isPlaying = false
            } else {
                player.controls().play()
                isPlaying = true
            }
        } else {
            if (isPlaying) {
                isPlaying = false
            } else {
                if (videoCurrentTimeMs >= videoLengthMs) {
                    videoCurrentTimeMs = 0L
                }
                isPlaying = true
            }
        }
    }

    val seekTo = { timeMs: Long ->
        val target = timeMs.coerceIn(0L, videoLengthMs)
        if (vlcAvailable && mediaPlayerComponent != null) {
            mediaPlayerComponent.mediaPlayer().controls().setTime(target)
            videoCurrentTimeMs = target
        } else {
            videoCurrentTimeMs = target
        }
    }

    LaunchedEffect(videoPath, mediaPlayerComponent) {
        if (mediaPlayerComponent != null && videoPath.isNotEmpty() && File(videoPath).exists()) {
            val player = mediaPlayerComponent.mediaPlayer()
            player.media().startPaused(videoPath)
            withContext(Dispatchers.IO) {
                var attempts = 0
                while (attempts < 20) {
                    val len = player.status().length()
                    if (len > 0) {
                        videoLengthMs = len
                        break
                    }
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                
                // Force rendering of the first frame
                kotlinx.coroutines.delay(200)
                player.controls().setTime(0)
                player.controls().nextFrame()
            }
            videoCurrentTimeMs = 0L
            isPlaying = false
        }
    }

    LaunchedEffect(isPlaying, mediaPlayerComponent, vlcAvailable) {
        if (isPlaying) {
            if (vlcAvailable && mediaPlayerComponent != null) {
                while (isPlaying) {
                    val player = mediaPlayerComponent.mediaPlayer()
                    val isStillPlaying = player.status().isPlaying()
                    if (!isStillPlaying) {
                        isPlaying = false
                        break
                    }
                    videoCurrentTimeMs = player.status().time()
                    kotlinx.coroutines.delay(50)
                }
            } else {
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

    // Load path cache on startup
    LaunchedEffect(Unit) {
        val cache = withContext(Dispatchers.IO) {
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
        if (cache != null) {
            fitPath = cache.fitPath
            videoPath = cache.videoPath
            videoStartUtc = cache.videoStartUtc
            settings = cache.settings
            moveOutputToSource = cache.moveOutputToSource
        }
        isLoaded = true
    }

    // Save path cache when modified
    LaunchedEffect(fitPath, videoPath, videoStartUtc, settings, moveOutputToSource) {
        if (isLoaded) {
            withContext(Dispatchers.IO) {
                try {
                    // Save GUI Cache to home directory (always, even if paths are empty)
                    val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
                    val cache = GuiPathCache(fitPath, videoPath, videoStartUtc, settings, moveOutputToSource)
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
                try {
                    // Extract duration using ffmpeg -i output if VLC isn't active
                    val pbDur = ProcessBuilder("ffmpeg", "-i", videoPath)
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
                        if (!vlcAvailable) {
                            videoLengthMs = totalMs
                        }
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
                    val pb = ProcessBuilder("ffmpeg", "-y", "-i", videoPath, "-ss", "00:00:01", "-vframes", "1", tempThumb.absolutePath)
                    pb.start().waitFor()
                    if (tempThumb.exists()) {
                        videoPreviewImage = Image.makeFromEncoded(tempThumb.readBytes()).asImageBitmap()
                        tempThumb.delete()
                    }
                } catch (e: Exception) { e.printStackTrace() }
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
                                        },
                                        onFrame = { bufferedImg ->
                                            val bitmap = bufferedImg.toComposeImageBitmap()
                                            javax.swing.SwingUtilities.invokeLater {
                                                encodingPreviewImage = bitmap
                                            }
                                        },
                                        pauseSupplier = { isPaused },
                                        cancelSupplier = { isCanceled }
                                    )
                                    statusText = "✨ Finished Successfully!"
                                    if (args.contains("--auto-sample")) {
                                        println("TEST_NOTIFICATION_SUCCESS: Encoding Finished Successfully!")
                                    } else {
                                        javax.swing.SwingUtilities.invokeLater {
                                            javax.swing.JOptionPane.showMessageDialog(
                                                null,
                                                "Encoding Finished Successfully!",
                                                "Success",
                                                javax.swing.JOptionPane.INFORMATION_MESSAGE
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Error string is set inside fireEncode's onProgress callback
                                    if (args.contains("--auto-sample")) {
                                        println("TEST_NOTIFICATION_ERROR: Encoding Failed: ${e.message}")
                                    } else {
                                        javax.swing.SwingUtilities.invokeLater {
                                            javax.swing.JOptionPane.showMessageDialog(
                                                null,
                                                "Encoding Failed:\n${e.message}",
                                                "Error",
                                                javax.swing.JOptionPane.ERROR_MESSAGE
                                            )
                                        }
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
        title = "FitTrimmer CP Console",
        state = rememberWindowState(width = 1400.dp, height = 900.dp)
    ) {
        val textMeasurer = rememberTextMeasurer()

        MaterialTheme(colors = darkColors()) {
            Row(modifier = Modifier.fillMaxSize().background(Color(0xFF141416))) {
                Column(
                    modifier = Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF111111))
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("CONTROL PLANE", color = Color(0xFFF5F5F7), fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
                    
                    Divider(color = Color(0xFF2C2C2E))

                    val onNativeEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, moveOutputToSource) {
                        {
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
                                        },
                                        onFrame = { bufferedImg ->
                                            val bitmap = bufferedImg.toComposeImageBitmap()
                                            javax.swing.SwingUtilities.invokeLater {
                                                encodingPreviewImage = bitmap
                                            }
                                        },
                                        pauseSupplier = { isPaused },
                                        cancelSupplier = { isCanceled }
                                    )
                                    if (moveOutputToSource && !isCanceled) {
                                        statusText = "Moving file to source directory..."
                                        val sourceDir = File(videoPath).parentFile
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
                                            javax.swing.SwingUtilities.invokeLater {
                                                javax.swing.JOptionPane.showMessageDialog(
                                                    null,
                                                    "Encoding Finished Successfully!",
                                                    "Success",
                                                    javax.swing.JOptionPane.INFORMATION_MESSAGE
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Handled and displayed in statusText
                                    if (args.contains("--auto-sample")) {
                                        println("TEST_NOTIFICATION_ERROR: Encoding Failed: ${e.message}")
                                    } else {
                                        javax.swing.SwingUtilities.invokeLater {
                                            javax.swing.JOptionPane.showMessageDialog(
                                                null,
                                                "Encoding Failed:\n${e.message}",
                                                "Error",
                                                javax.swing.JOptionPane.ERROR_MESSAGE
                                            )
                                        }
                                    }
                                } finally {
                                    isEncoding = false
                                }
                            }
                            Unit
                        }
                    }

                    val onSampleEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, moveOutputToSource) {
                        {
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
                                        },
                                        onFrame = { bufferedImg ->
                                            val bitmap = bufferedImg.toComposeImageBitmap()
                                            javax.swing.SwingUtilities.invokeLater {
                                                encodingPreviewImage = bitmap
                                            }
                                        },
                                        maxDurationSeconds = 5,
                                        pauseSupplier = { isPaused },
                                        cancelSupplier = { isCanceled }
                                    )
                                    if (moveOutputToSource && !isCanceled) {
                                        statusText = "Moving file to source directory..."
                                        val sourceDir = File(videoPath).parentFile
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
                                            javax.swing.SwingUtilities.invokeLater {
                                                javax.swing.JOptionPane.showMessageDialog(
                                                    null,
                                                    "Sample Encoding Finished Successfully!",
                                                    "Success",
                                                    javax.swing.JOptionPane.INFORMATION_MESSAGE
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Handled and displayed in statusText
                                    if (args.contains("--auto-sample")) {
                                        println("TEST_NOTIFICATION_ERROR: Sample Encoding Failed: ${e.message}")
                                    } else {
                                        javax.swing.SwingUtilities.invokeLater {
                                            javax.swing.JOptionPane.showMessageDialog(
                                                null,
                                                "Sample Encoding Failed:\n${e.message}",
                                                "Error",
                                                javax.swing.JOptionPane.ERROR_MESSAGE
                                            )
                                        }
                                    }
                                } finally {
                                    isEncoding = false
                                    if (args.contains("--auto-sample")) {
                                        println("🏁 Auto-sample test finished. Exiting application...")
                                        exitApplication()
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
                        backgroundColor = Color(0xFF1C1C1E),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        elevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("ENCODER SETUP", color = Color(0xFFF5F5F7), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            
                            OutlinedTextField(
                                value = videoStartUtc,
                                onValueChange = { videoStartUtc = it },
                                label = { Text("Video Start UTC", color = Color(0xFF8E8E93), fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                singleLine = true,
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    focusedBorderColor = Color(0xFF0A84FF),
                                    unfocusedBorderColor = Color(0xFF2C2C2E)
                                )
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = fitPath,
                                    onValueChange = { fitPath = it },
                                    label = { Text("FIT File Path", color = Color(0xFF8E8E93), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                    singleLine = true,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = Color(0xFF0A84FF),
                                        unfocusedBorderColor = Color(0xFF2C2C2E)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFile("Select FIT File", listOf("*.fit"))
                                        if (path != null) fitPath = path
                                    },
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2C2C2E)),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color.White, fontSize = 11.sp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = videoPath,
                                    onValueChange = { videoPath = it },
                                    label = { Text("MP4 File Path", color = Color(0xFF8E8E93), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                    singleLine = true,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = Color(0xFF0A84FF),
                                        unfocusedBorderColor = Color(0xFF2C2C2E)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFile("Select MP4 File", listOf("*.mp4", "*.mov"))
                                        if (path != null) videoPath = path
                                    },
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2C2C2E)),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color.White, fontSize = 11.sp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = outputDir,
                                    onValueChange = { outputDir = it },
                                    label = { Text("Output Directory", color = Color(0xFF8E8E93), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                    singleLine = true,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = Color(0xFF0A84FF),
                                        unfocusedBorderColor = Color(0xFF2C2C2E)
                                    )
                                )
                                Button(
                                    onClick = {
                                        val path = pickFolder("Select Output Directory")
                                        if (path != null) outputDir = path
                                    },
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2C2C2E)),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("...", color = Color.White, fontSize = 11.sp) }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { moveOutputToSource = !moveOutputToSource }
                            ) {
                                Checkbox(
                                    checked = moveOutputToSource,
                                    onCheckedChange = { moveOutputToSource = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF0A84FF),
                                        uncheckedColor = Color(0xFF2C2C2E)
                                    )
                                )
                                Text("Move output to source video directory after encode", color = Color(0xFFE2E8F0), fontSize = 11.sp)
                            }
                        }
                    }

                    // Developer tools (C drive, hot reload, etc.) have been moved under the ADVANCED TOOLKIT card below to simplify the panel.

                    // 3. ENCODE Actions / Progress Monitor
                    if (isEncoding) {
                        Card(
                            backgroundColor = Color(0xFF1C1C1E),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            elevation = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (statusText.contains("Merging", ignoreCase = true)) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF30D158),
                                        backgroundColor = Color(0xFF2C2C2E)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (isPaused) Color(0xFFFF9F0A) else Color(0xFF0A84FF),
                                        backgroundColor = Color(0xFF2C2C2E)
                                    )
                                }
                                Text(if (isPaused) "PAUSED (Not enough space or paused manually)\n$statusText" else statusText, color = Color.White, fontSize = 12.sp)
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { isPaused = !isPaused },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        enabled = !(!isPaused && !hasEnoughSpace),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = if (isPaused) Color(0xFF30D158) else Color(0xFFFF9F0A)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    ) {
                                        Text(if (isPaused) "RESUME" else "PAUSE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Button(
                                        onClick = { isCanceled = true },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF453A)),
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
                                backgroundColor = if (hasEnoughSpace) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text(if (hasEnoughSpace) "RUN NATIVE ENCODE" else "INSUFFICIENT DISK SPACE", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }

                        Button(
                            onClick = onSampleEncodeClick,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            enabled = hasEnoughSpace && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (hasEnoughSpace) Color(0xFF30D158) else Color(0xFF2C2C2E)
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text("RUN 5s SAMPLE", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }


                }

                Box(modifier = Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .aspectRatio(16f / 9f)
                                .fillMaxWidth()
                                .background(Color.Black, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF3A3A3C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        ) {
                            if (vlcAvailable && mediaPlayerComponent != null) {
                                SwingPanel(
                                    background = Color.Black,
                                    factory = { mediaPlayerComponent },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                videoPreviewImage?.let {
                                    androidx.compose.foundation.Image(
                                        bitmap = it,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                    )
                                } ?: Box(Modifier.fillMaxSize().background(Color.Black))
                            }

                            if (isEncoding) {
                                encodingPreviewImage?.let { hudImg ->
                                     Canvas(modifier = Modifier.fillMaxSize()) {
                                         drawImage(
                                             image = hudImg,
                                             dstSize = IntSize(size.width.toInt(), size.height.toInt())
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
                                        telemetryPoints,
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
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3B82F6))
                                ) {
                                    Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                Text(
                                    text = "${formatTime(videoCurrentTimeMs)} / ${formatTime(videoLengthMs)}",
                                    color = Color.LightGray,
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
                                        thumbColor = Color(0xFF3B82F6),
                                        activeTrackColor = Color(0xFF3B82F6),
                                        inactiveTrackColor = Color(0xFF3A3A3C)
                                    )
                                )

                                // VLC Status Badge
                                Text(
                                    text = if (vlcAvailable) "VLC ACTIVE" else "NO VLC (SIM)",
                                    color = if (vlcAvailable) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }

                        Text("1920x1080 Overlay Preview", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
    cancelSupplier: () -> Boolean = { false }
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
                }
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
            Text(label, color = Color(0xFFE2E8F0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("%.1f".format(value), color = Color(0xFF0A84FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, 
            onValueChange = onValueChange, 
            valueRange = min..max,
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF0A84FF),
                activeTrackColor = Color(0xFF0A84FF),
                inactiveTrackColor = Color(0xFF3A3A3C)
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

