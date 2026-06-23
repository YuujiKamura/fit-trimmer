import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val settings: HudSettings = HudSettings()
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
    if (args.isNotEmpty()) {
        runCli(args)
        return
    }
    startGui()
}

@OptIn(ExperimentalTextApi::class)
fun startGui() = application {
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
    var hudSettingsExpanded by remember { mutableStateOf(true) }
    var isLoaded by remember { mutableStateOf(false) }

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
                    Json.decodeFromString<GuiPathCache>(content)
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
        }
        isLoaded = true
    }

    // Save path cache when modified
    LaunchedEffect(fitPath, videoPath, videoStartUtc, settings) {
        if (isLoaded) {
            withContext(Dispatchers.IO) {
                try {
                    // Save GUI Cache to home directory (always, even if paths are empty)
                    val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
                    val cache = GuiPathCache(fitPath, videoPath, videoStartUtc, settings)
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
                                } catch (e: Exception) {
                                    // Error string is set inside fireEncode's onProgress callback
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
            Row(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
                Column(
                    modifier = Modifier.width(300.dp).fillMaxHeight().background(Color(0xFF16171D))
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("📡 CONTROL PLANE", color = Color(0xFF3B82F6), fontWeight = FontWeight.Black, fontSize = 16.sp)
                    
                    Divider(color = Color.DarkGray)

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { hudSettingsExpanded = !hudSettingsExpanded }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚙️ HUD LAYOUT SETTINGS", color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(if (hudSettingsExpanded) "▼" else "▶", color = Color.Gray, fontSize = 10.sp)
                    }

                    if (hudSettingsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ControlSlider("VAL SIZE", settings.valSize, 10f, 150f) { settings = settings.copy(valSize = it) }
                            ControlSlider("TIGHTNESS", settings.tightness, -10f, 40f) { settings = settings.copy(tightness = it) }
                            ControlSlider("SPACING", settings.spacing, 0f, 100f) { settings = settings.copy(spacing = it) }
                            ControlSlider("X OFFSET", settings.xOffset, 0f, 500f) { settings = settings.copy(xOffset = it) }
                            ControlSlider("Y OFFSET", settings.yOffset, 0f, 500f) { settings = settings.copy(yOffset = it) }
                            ControlSlider("GRAPH H", settings.graphH, 20f, 300f) { settings = settings.copy(graphH = it) }
                            ControlSlider("GRAPH W", settings.graphW, 50f, 800f) { settings = settings.copy(graphW = it) }
                        }
                    }

                    Divider(color = Color.DarkGray)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = fitPath,
                                onValueChange = { fitPath = it },
                                label = { Text("FIT File Path", color = Color.Gray, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                            )
                            Button(
                                onClick = {
                                    val path = pickFile("Select FIT File", listOf("*.fit"))
                                    if (path != null) fitPath = path
                                },
                                modifier = Modifier.height(50.dp)
                            ) { Text("...", fontSize = 11.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = videoPath,
                                onValueChange = { videoPath = it },
                                label = { Text("MP4 File Path", color = Color.Gray, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                            )
                            Button(
                                onClick = {
                                    val path = pickFile("Select MP4 File", listOf("*.mp4", "*.mov"))
                                    if (path != null) videoPath = path
                                },
                                modifier = Modifier.height(50.dp)
                            ) { Text("...", fontSize = 11.sp) }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = outputDir,
                                onValueChange = { outputDir = it },
                                label = { Text("Output Directory", color = Color.Gray, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                textStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                            )
                            Button(
                                onClick = {
                                    val path = pickFolder("Select Output Directory")
                                    if (path != null) outputDir = path
                                },
                                modifier = Modifier.height(50.dp)
                            ) { Text("...", fontSize = 11.sp) }
                        }
                        OutlinedTextField(
                            value = videoStartUtc,
                            onValueChange = { videoStartUtc = it },
                            label = { Text("Video Start UTC", color = Color.Gray, fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp)
                        )
                    }

                    if (isEncoding) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF3B82F6)
                            )
                            Text(statusText, color = Color.White, fontSize = 12.sp)
                        }
                    }

                    val onNativeEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc) {
                        {
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
                                } catch (e: Exception) {
                                    // Handled and displayed in statusText
                                } finally {
                                    isEncoding = false
                                }
                            }
                            Unit
                        }
                    }

                    Button(
                        onClick = onNativeEncodeClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isEncoding && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6366F1))
                    ) {
                        Text("🚀 RUN NATIVE ENCODE", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))

                    var hotReloadStatus by remember { mutableStateOf("") }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        hotReloadStatus = "Compiling..."
                                        val pb = ProcessBuilder("cmd.exe", "/c", ".\\gradlew.bat", ":shared-core:compileKotlinDesktop")
                                        pb.directory(File(System.getProperty("user.dir")))
                                        val p = pb.start()
                                        if (p.waitFor() == 0) {
                                            hotReloadStatus = "Reloading..."
                                            val proxy = globalRendererProxy
                                            if (proxy != null) {
                                                val success = proxy.reload()
                                                if (success) {
                                                    hotReloadStatus = "✅ Reloaded!"
                                                } else {
                                                    hotReloadStatus = "❌ Reload Failed"
                                                }
                                            } else {
                                                hotReloadStatus = "❌ No Proxy"
                                            }
                                        } else {
                                            hotReloadStatus = "❌ Compile Failed"
                                        }
                                    } catch(e:Exception) {
                                        hotReloadStatus = "❌ Error: ${e.message}"
                                    }
                                    kotlinx.coroutines.delay(3000)
                                    hotReloadStatus = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6366F1)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text("HOT RELOAD RENDERER", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (hotReloadStatus.isNotEmpty()) {
                            Text(hotReloadStatus, color = Color(0xFF10B981), fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val onSampleEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc) {
                        {
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
                                        maxDurationSeconds = 5,
                                        pauseSupplier = { isPaused },
                                        cancelSupplier = { isCanceled }
                                    )
                                    statusText = "✨ Sample Finished Successfully!"
                                } catch (e: Exception) {
                                    // Handled and displayed in statusText
                                } finally {
                                    isEncoding = false
                                }
                            }
                            Unit
                        }
                    }

                    Button(
                        onClick = onSampleEncodeClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isEncoding && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF43F5E))
                    ) {
                        Text("⏱️ RUN 5s SAMPLE", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.aspectRatio(16f/9f).fillMaxWidth()) {
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
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val scale = size.width / 1920f
                                    val textShadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black,
                                        offset = Offset(2f * scale, 2f * scale),
                                        blurRadius = 3f * scale
                                    )
                                    
                                    val speedText = currentPoint?.let { "%.1f".format(it.speed) } ?: "26.2"
                                    val cadenceText = currentPoint?.let { it.cadence.toInt().toString() } ?: "79"
                                    val powerText = currentPoint?.let { it.power.toInt().toString() } ?: "175"
                                    val hrText = currentPoint?.let { it.heartRate.toInt().toString() } ?: "148"
                                    val gradeText = currentPoint?.let { "%.1f".format(it.grade) } ?: "4.0"
                                    val wkgText = currentPoint?.let { "%.1f".format(it.power / 70.0) } ?: "2.5"

                                    val metrics = listOf(
                                        Triple("SPEED", speedText to "km/h", Color(0xFF60A5FA)),
                                        Triple("CADENCE", cadenceText to "rpm", Color(0xFFC084FC)),
                                        Triple("POWER", powerText to "W", Color(0xFF34D399)),
                                        Triple("HEART RATE", hrText to "bpm", Color(0xFFF87171)),
                                        Triple("GRADE", gradeText to "%", Color(0xFFFCD34D)),
                                        Triple("W/KG", wkgText to "w/kg", Color(0xFF2DD4BF))
                                    )
                                    val previewX = settings.xOffset * scale
                                    var previewCy = settings.yOffset * scale

                                    metrics.forEach { (label, valAndUnit, color) ->
                                        val (value, unit) = valAndUnit
                                        val valSize = settings.valSize * scale
                                        val lblSize = (valSize * 0.45f).coerceAtLeast(10f)
                                        val lblLayout = textMeasurer.measure(label, TextStyle(color = Color(0xFFE2E8F0), fontSize = lblSize.sp, fontWeight = FontWeight.Bold, shadow = textShadow))
                                        val valLayout = textMeasurer.measure(value, TextStyle(color = Color.White, fontSize = valSize.sp, fontWeight = FontWeight.Black, shadow = textShadow))
                                        
                                        // Compensate Compose vertical font padding using firstBaseline
                                        val lblDrawY = previewCy - (lblLayout.firstBaseline - lblSize)
                                        drawText(lblLayout, topLeft = Offset(previewX, lblDrawY))
                                        
                                        val valY = previewCy + lblSize + (settings.tightness * scale)
                                        val valDrawY = valY - (valLayout.firstBaseline - valSize)
                                        drawText(valLayout, topLeft = Offset(previewX, valDrawY))
                                        
                                        val unitLayout = textMeasurer.measure(unit, TextStyle(color = color, fontSize = (valSize * 0.5f).sp, fontWeight = FontWeight.Bold, shadow = textShadow))
                                        val unitDrawY = valY + (valSize * 0.15f) - (unitLayout.firstBaseline - (valSize * 0.5f))
                                        drawText(unitLayout, topLeft = Offset(previewX + valLayout.size.width + (6 * scale), unitDrawY))
                                        
                                        previewCy = valY + valSize + (settings.spacing * scale)
                                    }
                                    
                                    val valSize = settings.valSize * scale
                                    val lblSize = (valSize * 0.45f).coerceAtLeast(10f)
                                    val gW = settings.graphW * scale
                                    val gH = settings.graphH * scale
                                    
                                    val trendLabel = textMeasurer.measure("POWER TREND", TextStyle(color = Color(0xFFE2E8F0), fontSize = lblSize.sp, fontWeight = FontWeight.Bold, shadow = textShadow))
                                    val trendLblDrawY = previewCy - (trendLabel.firstBaseline - lblSize)
                                    drawText(trendLabel, topLeft = Offset(previewX, trendLblDrawY))
                                    val trendGy = previewCy + lblSize + (4 * scale)
                                    
                                    drawRect(color = Color(0xFF1E2023), topLeft = Offset(previewX, trendGy), size = Size(gW, gH), alpha = 0.6f)
                                    drawRect(color = Color.White, topLeft = Offset(previewX, trendGy), size = Size(gW, gH), alpha = 0.1f, style = Stroke(width = 1f))
                                    
                                    // Power trend bar chart rendering
                                    val trendData = currentTrendPoints
                                    if (trendData.isNotEmpty()) {
                                        val bw = gW / trendData.size
                                        val maxP = maxOf(trendData.maxOrNull() ?: 250.0, 300.0).toFloat()
                                        trendData.forEachIndexed { i, v ->
                                            val bh = (v / maxP).toFloat() * gH
                                            drawRect(
                                                color = Color(0xFF10B981),
                                                topLeft = Offset(previewX + i * bw, trendGy + gH - bh),
                                                size = Size(maxOf(1f, bw - 1f * scale), bh)
                                            )
                                        }
                                    }

                                    previewCy = trendGy + gH + (settings.spacing * scale)
                                    
                                    val elevLabel = textMeasurer.measure("ELEVATION", TextStyle(color = Color(0xFFE2E8F0), fontSize = lblSize.sp, fontWeight = FontWeight.Bold, shadow = textShadow))
                                    val elevLblDrawY = previewCy - (elevLabel.firstBaseline - lblSize)
                                    drawText(elevLabel, topLeft = Offset(previewX, elevLblDrawY))
                                    val elevGy = previewCy + lblSize + (4 * scale)
                                    drawRect(color = Color(0xFF1E2023), topLeft = Offset(previewX, elevGy), size = Size(gW, gH), alpha = 0.6f)
                                    drawRect(color = Color.White, topLeft = Offset(previewX, elevGy), size = Size(gW, gH), alpha = 0.1f, style = Stroke(width = 1f))

                                    // Elevation line & polygon rendering
                                    val elevData = telemetryPoints.map { it.elevation }
                                    if (elevData.size > 1) {
                                        val maxV = elevData.maxOrNull() ?: 0.0
                                        val minV = elevData.minOrNull() ?: 0.0
                                        val range = maxOf(maxV - minV, 10.0).toFloat()
                                        
                                        val pts = elevData.mapIndexed { i, v ->
                                            val px = previewX + (i.toFloat() / (elevData.size - 1)) * gW
                                            val py = elevGy + gH - ((v - minV).toFloat() / range) * gH
                                            Offset(px, py)
                                        }
                                        
                                        val currentRatio = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f
                                        val split = ((elevData.size - 1) * currentRatio).toInt()
                                        
                                        if (split > 0) {
                                            // Traveled path
                                            for (k in 0 until split) {
                                                drawLine(
                                                    color = Color(0xFF3B82F6),
                                                    start = pts[k],
                                                    end = pts[k + 1],
                                                    strokeWidth = 2f * scale
                                                )
                                            }
                                            // Traveled area shading
                                            val polyPath = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(pts[0].x, pts[0].y)
                                                for (k in 1..split) {
                                                    lineTo(pts[k].x, pts[k].y)
                                                }
                                                lineTo(pts[split].x, elevGy + gH)
                                                lineTo(pts[0].x, elevGy + gH)
                                                close()
                                            }
                                            drawPath(
                                                path = polyPath,
                                                color = Color(0xFF3B82F6),
                                                alpha = 0.2f
                                            )
                                        }
                                        
                                        if (split < elevData.size - 1) {
                                            // Future path
                                            for (k in split until elevData.size - 1) {
                                                drawLine(
                                                    color = Color(0xFF8C8C8C),
                                                    start = pts[k],
                                                    end = pts[k + 1],
                                                    strokeWidth = 1f * scale
                                                )
                                            }
                                        }
                                        
                                        // Current position pin
                                        val cx = previewX + gW * currentRatio
                                        val triPath = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(cx - 6 * scale, elevGy - 6 * scale)
                                            lineTo(cx + 6 * scale, elevGy - 6 * scale)
                                            lineTo(cx, elevGy)
                                            close()
                                        }
                                        drawPath(
                                            path = triPath,
                                            color = Color(0xFFEF4444)
                                        )
                                    }
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
                                        inactiveTrackColor = Color(0xFF334155)
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
) {
    println("DEBUG: fireEncode called with HudSettings: $s")
    withContext(Dispatchers.IO) {
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
            Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("%.1f".format(value), color = Color(0xFF6366F1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, 
            onValueChange = onValueChange, 
            valueRange = min..max,
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF6366F1),
                activeTrackColor = Color(0xFF6366F1),
                inactiveTrackColor = Color(0xFF334155)
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

