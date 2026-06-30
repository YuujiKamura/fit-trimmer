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
import androidx.compose.ui.window.WindowPlacement
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
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
// VLC dependency removed
import utils.*
import components.*
import viewmodel.*
import fit.APP_VERSION
private const val PLAYBACK_PREVIEW_INTERVAL_MS = 250L
const val MAX_ROAD_SNAP_DISTANCE_METERS = 15.0
@OptIn(ExperimentalTextApi::class)
fun main(args: Array<String>) {
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            fit.globalActiveJobDir?.let {
                if (it.exists()) {
                    it.deleteRecursively()
                    println("🧹 JVM Shutdown Hook: Cleaned up active job directory: ${it.absolutePath}")
                }
            }
        } catch (e: Exception) {}
    })
    // Clean up stale job folders older than 24h at application startup to prevent disk bloat
    fit.PathResolver.cleanStaleJobs()
    System.setProperty("compose.interop.blending", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    if (args.contains("--test-update")) {
        kotlinx.coroutines.runBlocking {
            println("=== STARTING LOCAL UPDATE TEST ===")
            val testUpdateIdx = args.indexOf("--test-update")
            val localMsiPath = if (testUpdateIdx != -1 && testUpdateIdx + 1 < args.size) {
                args[testUpdateIdx + 1]
            } else {
                null
            }
            if (localMsiPath == null || !File(localMsiPath).exists()) {
                println("ERROR: Local MSI path for testing not found or not provided: $localMsiPath")
                System.exit(1)
                return@runBlocking
            }
            val localFile = File(localMsiPath)
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val uniqueId = java.util.UUID.randomUUID().toString().take(8)
            val tempFile = File(tempDir, "fit-trimmer-update-$uniqueId-${localFile.name}")
            println("Copying local msi to: ${tempFile.absolutePath}")
            try {
                localFile.copyTo(tempFile, overwrite = true)
            } catch (e: Exception) {
                println("ERROR: Failed to copy msi: ${e.message}")
                System.exit(1)
                return@runBlocking
            }
            val runningUri = UpdateManager::class.java.protectionDomain.codeSource.location.toURI()
            val runningFile = File(runningUri)
            val targetPath = if (runningFile.parentFile?.name == "app") {
                File(runningFile.parentFile.parentFile, "FitTrimmer.exe").absolutePath
            } else {
                runningFile.absolutePath
            }
            val batchFile = File(tempDir, "fit-trimmer-apply-update.bat")
            val launcherFile = File(tempDir, "fit-trimmer-launcher.vbs")
            batchFile.writeText("""
                @echo off
                timeout /t 2 /nobreak > nul
                start /wait "" msiexec.exe /i "${tempFile.absolutePath}" /qb /norestart
                start "" "$targetPath"
                del "${launcherFile.absolutePath}"
                del "%~f0"
            """.trimIndent(), charset("Shift_JIS"))
            launcherFile.writeText(
                "Set WshShell = CreateObject(\"WScript.Shell\")\n" +
                "WshShell.Run \"cmd.exe /c \"\"" + batchFile.absolutePath + "\"\"\", 0, false",
                charset("Shift_JIS")
            )
            ProcessBuilder("wscript.exe", launcherFile.absolutePath)
                .directory(tempDir)
                .start()
            println("Update trigger succeeded. Exiting process.")
            System.exit(0)
        }
        return
    }

    if (args.contains("--test-e2e")) {
        runE2ETest(args)
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
    var composeWindow: java.awt.Window? by remember { mutableStateOf(null) }
    Window(
        onCloseRequest = {
            System.exit(0)
        },
        title = "HUD エンコーダー",
        state = windowState
    ) {
        LaunchedEffect(window) {
            composeWindow = window
        }
        LaunchedEffect(viewModel.isPreviewFullscreen) {
            windowState.placement = if (viewModel.isPreviewFullscreen) {
                WindowPlacement.Fullscreen
            } else {
                WindowPlacement.Floating
            }
        }
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
        var lastPercent by remember { mutableStateOf(-1) }
        var lastState by remember { mutableStateOf<java.awt.Taskbar.State?>(null) }
        LaunchedEffect(viewModel.isEncoding, viewModel.progress, viewModel.isPaused, viewModel.statusText) {
            if (taskbar != null && isProgressSupported) {
                try {
                    val targetState = when {
                        !viewModel.isEncoding -> java.awt.Taskbar.State.OFF
                        viewModel.isPaused -> java.awt.Taskbar.State.PAUSED
                        viewModel.statusText.contains("Merging", ignoreCase = true) -> java.awt.Taskbar.State.INDETERMINATE
                        else -> java.awt.Taskbar.State.NORMAL
                    }
                    val targetPercent = if (viewModel.isEncoding) (viewModel.progress * 100).toInt().coerceIn(0, 100) else 0
                    if (targetState != lastState) {
                        taskbar.setWindowProgressState(window, targetState)
                        lastState = targetState
                    }
                    if (targetState == java.awt.Taskbar.State.NORMAL || targetState == java.awt.Taskbar.State.PAUSED) {
                        if (targetPercent != lastPercent) {
                            taskbar.setWindowProgressValue(window, targetPercent)
                            lastPercent = targetPercent
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        FitTrimmerMainContent(
            viewModel = viewModel,
            args = args,
            composeWindow = composeWindow,
            windowState = windowState,
            onCloseRequest = ::exitApplication
        )
    }
}
var globalRendererProxy: fit.DynamicRendererProxy? = null

data class EncodeSegmentPlan(
    val index: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val finalOutputFile: File
)

data class EncodePlan(
    val settings: HudSettings,
    val segments: List<EncodeSegmentPlan>
) {
    val totalDurationSeconds: Double
        get() = segments.sumOf { it.endSeconds - it.startSeconds }
}

data class RoadCaptionDetectionContext(
    val points: List<FitParser.TelemetryPoint>,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val videoDurationSeconds: Double
)

fun buildEncodeRanges(
    trimStartSeconds: Double,
    trimEndSeconds: Double,
    splitPoints: List<Double>
): List<Pair<Double, Double>> {
    val activeSplits = splitPoints.filter { it > trimStartSeconds && it < trimEndSeconds }.sorted()
    val ranges = mutableListOf<Pair<Double, Double>>()
    var currentStart = trimStartSeconds
    for (split in activeSplits) {
        ranges.add(Pair(currentStart, split))
        currentStart = split
    }
    ranges.add(Pair(currentStart, trimEndSeconds))
    return ranges
}

fun buildEncodeOutputFileName(
    settings: HudSettings,
    videoPath: String,
    partIndex: Int = -1,
    numParts: Int = 1,
    isSample: Boolean = false
): String {
    val videoFile = File(videoPath)
    val baseName = videoFile.name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
    val partSuffix = if (!isSample && partIndex >= 0 && numParts > 1) "_part${partIndex + 1}" else ""
    val resSuffix = when (settings.exportResolution) {
        "1080p" -> "_1080p"
        "2.7k" -> "_2.7k"
        else -> "_orig"
    }
    val suffix = if (isSample) {
        "${partSuffix}_TEST_HUD.mp4"
    } else {
        "${partSuffix}_KMP_HUD${resSuffix}.mp4"
    }
    return baseName + suffix
}

fun buildEncodePlan(
    settings: HudSettings,
    videoPath: String,
    outputDir: String,
    moveOutputToSource: Boolean,
    ranges: List<Pair<Double, Double>>,
    isSample: Boolean = false
): EncodePlan {
    val videoFile = File(videoPath)
    val segments = ranges.mapIndexed { idx, (start, end) ->
        val outputFileName = buildEncodeOutputFileName(
            settings = settings,
            videoPath = videoPath,
            partIndex = idx,
            numParts = ranges.size,
            isSample = isSample
        )
        val finalOutputFile = if (moveOutputToSource) {
            val parent = videoFile.parentFile
            if (parent != null && parent.exists() && parent.canWrite()) {
                File(parent, outputFileName)
            } else {
                File(outputDir, outputFileName)
            }
        } else {
            File(outputDir, outputFileName)
        }
        EncodeSegmentPlan(
            index = idx,
            startSeconds = start,
            endSeconds = end,
            finalOutputFile = finalOutputFile
        )
    }
    return EncodePlan(settings = settings, segments = segments)
}

suspend fun prepareRoadCaptionSettingsForEncode(
    baseSettings: HudSettings,
    autoDetectRoadCaptionsOnEncode: Boolean,
    context: RoadCaptionDetectionContext,
    onStatus: (String) -> Unit,
    onSettingsPrepared: (HudSettings) -> Unit = {}
): HudSettings {
    if (!autoDetectRoadCaptionsOnEncode) return baseSettings
    val clearedSettings = baseSettings.copy(roadCaptions = emptyList())
    onSettingsPrepared(clearedSettings)
    onStatus("路線名テロップをクリアして再検出中...")
    val detected = detectRoadSegments(
        points = context.points,
        videoStartUtc = context.videoStartUtc,
        timeOffsetMillis = context.timeOffsetMillis,
        videoDurationSeconds = context.videoDurationSeconds,
        language = baseSettings.language,
        enableRoadDetection = baseSettings.enableRoadDetection,
        onProgress = onStatus
    )
    val updatedSettings = clearedSettings.copy(roadCaptions = detected)
    onSettingsPrepared(updatedSettings)
    onStatus("路線名テロップを ${detected.size} 件アサインしました")
    return updatedSettings
}

suspend fun loadTelemetryPointsForRoadDetection(fitPath: String): List<FitParser.TelemetryPoint> = withContext(Dispatchers.IO) {
    if (fitPath.isEmpty()) return@withContext emptyList()
    try {
        val parser = FitParser(File(fitPath).readBytes())
        parser.parse()
        parser.getTelemetry()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

suspend fun runBatchJobs(
    viewModel: AppViewModel,
    outputDir: String,
    moveOutputToSource: Boolean,
    showLivePreview: Boolean,
    onProgressUpdate: () -> Unit
) {
    if (viewModel.isBatchRunning) return
    viewModel.isBatchRunning = true
    viewModel.batchStatusText = "バッチ処理を開始します..."
    viewModel.isCanceled = false
    viewModel.isPaused = false
    try {
        val jobs = viewModel.batchQueue.filter { it.status == BatchJobStatus.WAITING }
        if (jobs.isEmpty()) {
            viewModel.batchStatusText = "処理待ちのジョブがありません。"
            return
        }
        for ((jobIdx, job) in jobs.withIndex()) {
            if (viewModel.isCanceled) break
            job.status = BatchJobStatus.RUNNING
            job.progress = 0f
            onProgressUpdate()
            viewModel.batchStatusText = "[${jobIdx + 1}/${jobs.size}] ${File(job.videoPath).name} を処理中..."
            val detectionContext = if (job.autoDetectRoadCaptionsOnEncode) {
                val points = loadTelemetryPointsForRoadDetection(job.fitPath)
                val videoDurationSeconds = (getVideoDuration(job.videoPath)?.toDouble()?.div(1000.0) ?: job.trimEndSeconds)
                    .coerceAtLeast(job.trimEndSeconds)
                RoadCaptionDetectionContext(
                    points = points,
                    videoStartUtc = job.videoStartUtc,
                    timeOffsetMillis = job.timeOffsetMillis,
                    videoDurationSeconds = videoDurationSeconds
                )
            } else {
                RoadCaptionDetectionContext(
                    points = emptyList(),
                    videoStartUtc = job.videoStartUtc,
                    timeOffsetMillis = job.timeOffsetMillis,
                    videoDurationSeconds = job.trimEndSeconds
                )
            }
            val encodeSettings = prepareRoadCaptionSettingsForEncode(
                baseSettings = job.settings,
                autoDetectRoadCaptionsOnEncode = job.autoDetectRoadCaptionsOnEncode,
                context = detectionContext,
                onStatus = { progressText ->
                    viewModel.batchStatusText = "[${jobIdx + 1}/${jobs.size}] $progressText"
                }
            )
            val ranges = buildEncodeRanges(job.trimStartSeconds, job.trimEndSeconds, job.splitPoints)
            val encodePlan = buildEncodePlan(
                settings = encodeSettings,
                videoPath = job.videoPath,
                outputDir = outputDir,
                moveOutputToSource = moveOutputToSource,
                ranges = ranges
            )
            val destFiles = encodePlan.segments.map { it.finalOutputFile }
            try {
                val resultMsg = HudEncodePipeline.execute(
                    s = encodeSettings,
                    fitPath = job.fitPath,
                    videoPath = job.videoPath,
                    outputDir = outputDir,
                    videoStartUtc = job.videoStartUtc,
                    ranges = ranges,
                    destFiles = destFiles,
                    isSample = false,
                    shouldResume = false,
                    moveOutputToSource = moveOutputToSource,
                    onProgress = { prog, status ->
                        job.progress = prog
                        viewModel.progress = prog
                        viewModel.statusText = status
                        onProgressUpdate()
                    },
                    onFrame = { bufferedImg ->
                        val bitmap = bufferedImg.toComposeImageBitmap()
                        javax.swing.SwingUtilities.invokeLater {
                            viewModel.encodingPreviewImage = bitmap
                        }
                    },
                    pauseSupplier = { viewModel.isPaused },
                    cancelSupplier = { viewModel.isCanceled },
                    showLivePreviewSupplier = { showLivePreview },
                    onSegmentStart = { pStart, pEnd ->
                        viewModel.encodingSegmentStart = pStart
                        viewModel.encodingSegmentEnd = pEnd
                    }
                )
                job.status = BatchJobStatus.COMPLETED
                job.progress = 1.0f
            } catch (e: Exception) {
                if (viewModel.isCanceled) {
                    job.status = BatchJobStatus.WAITING
                    job.progress = 0f
                } else {
                    job.status = BatchJobStatus.FAILED
                    job.errorMessage = e.message ?: "Unknown error"
                    e.printStackTrace()
                }
            }
            onProgressUpdate()
        }
        viewModel.batchStatusText = if (viewModel.isCanceled) "バッチ処理をキャンセルしました" else "✨ すべてのバッチ処理が完了しました！"
        if (!viewModel.isCanceled) {
            showSystemNotification("HUD エンコーダー", "すべてのバッチエンコードが完了しました！")
        }
    } finally {
        viewModel.isBatchRunning = false
        viewModel.isCanceled = false
        viewModel.encodingSegmentStart = null
        viewModel.encodingSegmentEnd = null
    }
}

suspend fun queryRoadName(lat: Double, lon: Double, heading: Double? = null, language: String = ""): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
            // 1. Fetch GSI (国土地理院) vector tile for road category
            var rdCtg: String? = null
            var gsiRoadName: String? = null
            try {
                val (tileX, tileY) = fit.GsiRoadDetector.deg2tile(lat, lon, 16)
                val gsiUrl = "https://cyberjapandata.gsi.go.jp/xyz/experimental_rdcl/16/$tileX/$tileY.geojson"
                val gsiRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(gsiUrl))
                    .header("User-Agent", "FitTrimmerApp/1.0")
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build()
                val gsiResponse = client.send(gsiRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
                if (gsiResponse.statusCode() == 200) {
                    val roadInfo = fit.GsiRoadDetector.findClosestRoad(lat, lon, gsiResponse.body(), carHeading = heading, maxDistanceMeters = MAX_ROAD_SNAP_DISTANCE_METERS)
                    if (roadInfo != null && roadInfo.distanceMeters <= 50.0) {
                        rdCtg = roadInfo.rdCtg
                        gsiRoadName = roadInfo.name ?: roadInfo.comName
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Failed to query GSI vector tile: ${e.message}")
            }
            // 2. Fetch OSM Nominatim for local area and road names
            val langParam = if (language.isEmpty()) "ja" else language
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=$langParam&addressdetails=1&extratags=1"
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
                val county = extractJsonValue(body, "county")
                val neighbourhood = extractJsonValue(body, "neighbourhood")
                val state = extractJsonValue(body, "state") ?: extractJsonValue(body, "province")
                val countryCode = extractJsonValue(body, "country_code")
                val country = extractJsonValue(body, "country")
                // If municipal road has no official name, reject snapped OSM road name
                val finalRoadName = if (rdCtg == "市区町村道等" && gsiRoadName == null) {
                    null
                } else {
                    gsiRoadName ?: road
                }
                return@withContext fit.RoadNameBuilder.buildCaptionText(
                    rdCtg = rdCtg,
                    roadName = finalRoadName,
                    ref = ref,
                    city = city,
                    town = town,
                    village = village,
                    suburb = suburb,
                    county = county,
                    neighbourhood = neighbourhood,
                    countryCode = countryCode,
                    state = state,
                    country = country
                )
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
    language: String = "",
    enableRoadDetection: Boolean = true,
    onProgress: (String) -> Unit
): List<RoadCaptionSegment> {
    if (!enableRoadDetection) return emptyList()
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
    // 1. Calculate headings at each second point
    val numSecs = videoDurationSeconds.toInt()
    val headings = DoubleArray(numSecs + 1) { -1.0 }
    fun getHeadingAtSeconds(sec: Double): Double? {
        val targetFitTime = startFitTime + sec
        val prevPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime - 4.0)) } ?: return null
        val nextPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime + 4.0)) } ?: return null
        val dLat = nextPoint.lat - prevPoint.lat
        val dLon = nextPoint.lon - prevPoint.lon
        val dist = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        if (dist < 0.00005) return null // Less than ~5 meters of movement
        val dLonRad = (nextPoint.lon - prevPoint.lon) * (Math.PI / 180.0)
        val lat1 = prevPoint.lat * (Math.PI / 180.0)
        val lat2 = nextPoint.lat * (Math.PI / 180.0)
        val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLonRad)
        var brng = kotlin.math.atan2(y, x) * 180.0 / Math.PI
        return (brng + 360.0) % 360.0
    }
    for (s in 0..numSecs) {
        headings[s] = getHeadingAtSeconds(s.toDouble()) ?: -1.0
    }
    // Helper to compute absolute heading difference
    fun headingDiff(h1: Double, h2: Double): Double {
        val diff = kotlin.math.abs(h1 - h2)
        return if (diff > 180.0) 360.0 - diff else diff
    }
    // 2. Identify Turn Event Points (Intersections)
    val queryOffsets = mutableListOf<Double>()
    queryOffsets.add(0.0) // Always query start point
    var baseHeading = -1.0
    var isTurning = false
    var lastQueryOffset = 0.0
    // Find initial stable heading
    for (s in 0..numSecs) {
        if (headings[s] >= 0.0) {
            baseHeading = headings[s]
            break
        }
    }
    for (s in 1..numSecs) {
        val currentHeading = headings[s]
        if (currentHeading < 0.0) continue
        
        // Speed guard: If moving too slow (< 5 km/h), GPS noise can cause fake turn detections.
        val targetFitTime = startFitTime + s
        val currentPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - targetFitTime) }
        val speedKmh = currentPoint?.speed ?: 0.0
        if (speedKmh < 5.0) {
            continue
        }
        if (baseHeading < 0.0) {
            baseHeading = currentHeading
            continue
        }
        val diff = headingDiff(currentHeading, baseHeading)
        if (!isTurning) {
            if (diff >= 25.0) {
                // Entered a turn!
                isTurning = true
            }
        } else {
            // We are turning. Check if the turn has finished and stabilized.
            val lookaheadStable = (1..3).all { offset ->
                val nextH = headings.getOrNull(s + offset) ?: -1.0
                nextH >= 0.0 && headingDiff(currentHeading, nextH) < 8.0
            }
            if (lookaheadStable) {
                // Stabilized after turn! Trigger query right after exiting the intersection.
                queryOffsets.add(s.toDouble())
                baseHeading = currentHeading
                isTurning = false
            }
        }
    }
    // Filter and deduplicate query offsets to respect Nominatim API rate limit (at least 10.0 seconds gap)
    val cleanQueryOffsets = mutableListOf<Double>()
    for (offset in queryOffsets) {
        if (cleanQueryOffsets.isEmpty() || offset - cleanQueryOffsets.last() >= 10.0) {
            cleanQueryOffsets.add(offset)
        }
    }
    // 3. Perform Road Queries at clean query points and build segments
    val segments = mutableListOf<RoadCaptionSegment>()
    var currentRoadName: String? = null
    var segmentStartSeconds = 0.0
    val totalQueries = cleanQueryOffsets.size
    for ((index, currentOffset) in cleanQueryOffsets.withIndex()) {
        val targetFitTime = startFitTime + currentOffset
        val point = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - targetFitTime) } ?: continue
        val progressPercent = ((index.toDouble() / totalQueries) * 100).toInt()
        onProgress("GPS解析中 (${progressPercent}%): 座標 (${"%.4f".format(point.lat)}, ${"%.4f".format(point.lon)}) 付近で道路判定中...")
        // Safe rate limiting (1s wait) only for active queries
        kotlinx.coroutines.delay(1000)
        val headingVal = headings.getOrNull(currentOffset.toInt()) ?: -1.0
        val roadName = queryRoadName(point.lat, point.lon, if (headingVal >= 0.0) headingVal else null, language = language)
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
object UpdateManager {
    private const val GITHUB_API_URL = "https://api.github.com/repos/YuujiKamura/fit-trimmer/releases/latest"
    fun isDevelopment(): Boolean {
        return try {
            if (System.getProperty("idea.active") == "true") return true
            val userDir = System.getProperty("user.dir").lowercase()
            if (userDir.contains("fit-trimmer") || userDir.contains("build") || userDir.contains("out")) return true
            val runningUri = UpdateManager::class.java.protectionDomain.codeSource.location.toURI()
            val runningFile = File(runningUri)
            val isDev = isDevelopmentPath(runningFile.absolutePath)
            try {
                val logFile = File(System.getProperty("user.home"), "fit-trimmer-debug-env.txt")
                logFile.writeText("""
                    runningUri: ${runningUri}
                    runningFile.absolutePath: ${runningFile.absolutePath}
                    runningFile.isDirectory: ${runningFile.isDirectory}
                    extension: ${runningFile.extension}
                    isDevResult: ${isDev}
                    idea.active: ${System.getProperty("idea.active")}
                    sun.java.command: ${System.getProperty("sun.java.command")}
                    os.name: ${System.getProperty("os.name")}
                    user.dir: ${System.getProperty("user.dir")}
                """.trimIndent())
            } catch (e: Exception) {
                // ignore
            }
            isDev
        } catch (e: Exception) {
            true
        }
    }
    fun isDevelopmentPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        if (lowerPath.contains("${File.separator}build${File.separator}") ||
            lowerPath.contains("${File.separator}out${File.separator}") ||
            lowerPath.contains("/build/") ||
            lowerPath.contains("/out/") ||
            lowerPath.contains("fit-trimmer")) {
            return true
        }
        val file = File(path)
        val extension = file.extension.lowercase()
        return file.isDirectory || (extension != "jar" && extension != "exe")
    }
    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val assets: List<AssetInfo>,
        val body: String
    )
    data class AssetInfo(
        val name: String,
        val browserDownloadUrl: String
    )
    fun isNewerVersion(current: String, latest: String): Boolean {
        fun parseVersion(v: String): List<Int> {
            val clean = v.trim().lowercase().removePrefix("v")
            return clean.split(".").mapNotNull { it.toIntOrNull() }
        }
        val currParts = parseVersion(current)
        val lateParts = parseVersion(latest)
        val size = maxOf(currParts.size, lateParts.size)
        for (i in 0 until size) {
            val currVal = currParts.getOrNull(i) ?: 0
            val lateVal = lateParts.getOrNull(i) ?: 0
            if (lateVal > currVal) return true
            if (currVal > lateVal) return false
        }
        return false
    }
    suspend fun fetchLatestRelease(): ReleaseInfo? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(GITHUB_API_URL).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "FitTrimmerApp/1.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val tagName = extractJsonValue(json, "tag_name") ?: return@withContext null
                    val htmlUrl = extractJsonValue(json, "html_url") ?: ""
                    val body = extractJsonValue(json, "body") ?: ""
                    val assets = mutableListOf<AssetInfo>()
                    val assetRegex = Regex("""\"name\"\s*:\s*\"([^\"]+)\"[\s\S]*?\"browser_download_url\"\s*:\s*\"([^\"]+)\"""")
                    assetRegex.findAll(json).forEach { match ->
                        val name = match.groupValues[1]
                        val url = match.groupValues[2]
                        assets.add(AssetInfo(name, url))
                    }
                    ReleaseInfo(tagName, htmlUrl, assets, body)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    suspend fun performUpdate(
        downloadUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val runningUri = UpdateManager::class.java.protectionDomain.codeSource.location.toURI()
                val runningFile = File(runningUri)
                // If running from packaged app (jpackage), the JAR is inside the 'app' directory,
                // and the executable 'FitTrimmer.exe' is located two levels up.
                val targetPath = if (runningFile.parentFile?.name == "app") {
                    File(runningFile.parentFile.parentFile, "FitTrimmer.exe").absolutePath
                } else {
                    runningFile.absolutePath
                }
                val extension = runningFile.extension.lowercase()
                if (extension != "jar" && extension != "exe") {
                    return@withContext "DEVELOPMENT_ENV"
                }
                val tempDir = File(System.getProperty("java.io.tmpdir"))
                val uniqueId = java.util.UUID.randomUUID().toString().take(8)
                val tempFile = File(tempDir, "fit-trimmer-update-$uniqueId-$fileName")
                val url = java.net.URL(downloadUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val fileLength = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (fileLength > 0) {
                                onProgress(totalBytesRead.toFloat() / fileLength)
                            }
                        }
                    }
                }
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    val batchFile = File(tempDir, "fit-trimmer-apply-update.bat")
                    val launcherFile = File(tempDir, "fit-trimmer-launcher.vbs")
                    batchFile.writeText("""
                        @echo off
                        timeout /t 2 /nobreak > nul
                        start /wait "" msiexec.exe /i "${tempFile.absolutePath}" /qb /norestart
                        start "" "$targetPath"
                        del "${launcherFile.absolutePath}"
                        del "%~f0"
                    """.trimIndent(), charset("Shift_JIS"))
                    // Generate a silent VBScript launcher to run the batch file completely hidden
                    launcherFile.writeText(
                        "Set WshShell = CreateObject(\"WScript.Shell\")\n" +
                        "WshShell.Run \"cmd.exe /c \"\"" + batchFile.absolutePath + "\"\"\", 0, false",
                        charset("Shift_JIS")
                    )
                    // Launcher creation complete. The caller will execute the launcher (VBS) based on user preference.
                } else {
                    val shellFile = File(tempDir, "fit-trimmer-apply-update.sh")
                    shellFile.writeText("""
                        #!/bin/bash
                        echo "Waiting for FitTrimmer process to exit..."
                        sleep 2
                        echo "Copying new version..."
                        cp -f "${tempFile.absolutePath}" "$targetPath"
                        echo "Restarting application..."
                        open "$targetPath" || "$targetPath" &
                        echo "Clean up..."
                        rm -f "${tempFile.absolutePath}"
                        rm -- "${'$'}0"
                    """.trimIndent())
                    shellFile.setExecutable(true)
                    ProcessBuilder("/bin/bash", shellFile.absolutePath)
                        .directory(tempDir)
                        .start()
                }
                "SUCCESS"
            } catch (e: Exception) {
                e.printStackTrace()
                e.message
            }
        }
    }
}
@Composable
fun ControlSlider(label: String, value: Float, min: Float, max: Float, enabled: Boolean = true, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 1.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) Color(0xFF1C1C1E) else Color(0xFF8E8E93), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("%.1f".format(value), color = if (enabled) Color(0xFF007AFF) else Color(0xFF8E8E93), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, 
            onValueChange = onValueChange, 
            valueRange = min..max,
            enabled = enabled,
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF007AFF),
                activeTrackColor = Color(0xFF007AFF),
                inactiveTrackColor = Color(0xFFE5E5EA),
                disabledThumbColor = Color(0xFFD1D1D6),
                disabledActiveTrackColor = Color(0xFFD1D1D6),
                disabledInactiveTrackColor = Color(0xFFE5E5EA)
            )
        )
    }
}
fun runE2ETest(args: Array<String>) {
    println("🧪 Running E2E Test on current packaged build...")
    // 1. SSL/HTTPS connectivity test (reverse geocoding API)
    println("[1/3] Checking SSL & HTTPS network connectivity (Nominatim API)...")
    val testLat = 33.5897 // Fukuoka
    val testLon = 130.4208
    kotlinx.coroutines.runBlocking {
        try {
            val roadName = queryRoadName(testLat, testLon)
            println("✅ Network SSL check passed. Road name resolved: $roadName")
        } catch (e: Throwable) {
            println("❌ Network SSL check FAILED: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(10)
        }
    }
    // 2. Video encoding and rendering test
    println("[2/3] Checking video encoding functionality...")
    var fitPath: String? = null
    var videoPath: String? = null
    var outputPath: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--fit" -> fitPath = args.getOrNull(++i)
            "--video" -> videoPath = args.getOrNull(++i)
            "--output" -> outputPath = args.getOrNull(++i)
        }
        i++
    }
    if (fitPath == null || videoPath == null || outputPath == null) {
        println("❌ E2E Test missing required files. Usage: --test-e2e --fit <fit> --video <video> --output <output>")
        kotlin.system.exitProcess(1)
    }
    println("🚀 Starting test encode with duration limit (3 seconds)...")
    kotlinx.coroutines.runBlocking {
        try {
            val testCaption = fit.RoadCaptionSegment(
                id = "test-e2e-caption",
                startSeconds = 0.0,
                endSeconds = 3.0,
                text = "E2E Test Route 101",
                isEnabled = true
            )
            val settings = HudSettings(roadCaptions = listOf(testCaption))
            val encoder = NativeHudEncoder(settings,
                onProgress = { prog, status ->
                    print("\rProgress: ${(prog * 100).toInt()}% - $status")
                }
            )
            // Limit duration to 3 seconds for quick verification
            encoder.encode(
                fitPath = fitPath,
                videoPath = videoPath,
                output = outputPath,
                startUtc = "2026-06-21T02:09:49Z", 
                maxDurationSeconds = 3,
                trimStartSeconds = 0.0,
                trimEndSeconds = 3.0
            )
            println("\n✅ Video encoding check passed.")
        } catch (e: Exception) {
            println("\n❌ Video encoding check FAILED: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(20)
        }
    }
    // 3. Output file verification
    println("[3/3] Verifying output file...")
    val outFile = File(outputPath)
    if (outFile.exists() && outFile.length() > 0) {
        println("✅ Output file verified ($outputPath, size: ${outFile.length()} bytes)")
        println("\n🎉 ALL E2E TESTS PASSED SUCCESSFULLY!")
        kotlin.system.exitProcess(0)
    } else {
        println("❌ Output file verification FAILED: file does not exist or is empty")
        kotlin.system.exitProcess(30)
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
fun SourceRangeSummary(
    fitStartInstant: java.time.Instant?,
    fitEndInstant: java.time.Instant?,
    videoStartInstant: java.time.Instant?,
    videoEndInstant: java.time.Instant?,
    isVideoInFitRange: Boolean,
    isHudBurned: Boolean
) {
    if (fitStartInstant == null || fitEndInstant == null) return

    val formatter = remember {
        java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
    }
    val fitStartStr = formatter.format(fitStartInstant)
    val fitEndStr = formatter.format(fitEndInstant)
    val videoRangeText = if (videoStartInstant != null && videoEndInstant != null) {
        "${formatter.format(videoStartInstant)} - ${formatter.format(videoEndInstant)}"
    } else {
        "動画の開始時刻を読み込み中"
    }
    val bgColor = when {
        videoStartInstant == null || videoEndInstant == null -> Color(0xFFE5E5EA)
        isVideoInFitRange -> Color(0xFFE2F6E9)
        else -> Color(0xFFFDE8E8)
    }
    val textColor = when {
        videoStartInstant == null || videoEndInstant == null -> Color(0xFF636366)
        isVideoInFitRange -> Color(0xFF1E7E34)
        else -> Color(0xFFE02424)
    }
    val statusText = when {
        videoStartInstant == null || videoEndInstant == null -> "確認中"
        isVideoInFitRange -> "範囲OK"
        else -> "範囲外"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("ソース範囲の照合", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("FIT: $fitStartStr - $fitEndStr", color = Color(0xFF1C1C1E), fontSize = 9.sp, maxLines = 1)
                Text("動画: $videoRangeText", color = Color(0xFF1C1C1E), fontSize = 9.sp, maxLines = 1)
            }
            Text(
                text = statusText,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier
                    .background(bgColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (isHudBurned) {
            Text(
                "警告: この動画はHUD焼き込み済みの可能性があります",
                color = Color(0xFFB45309),
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEF3C7), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFD97706), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun TimeAlignmentCard(
    state: TimeAlignmentState,
    videoPath: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    isAligning: Boolean,
    onAlignTelemetryClick: () -> Unit,
    isEncoding: Boolean
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HUDと動画の時刻合わせ", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "映像に対して速度・地図・路線テロップが早い/遅いときに調整します。",
                        color = Color(0xFF636366),
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HUD表示オフセット", color = Color(0xFF636366), fontSize = 10.sp)
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
                enabled = !isEncoding,
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
                    "0" to 0,
                    "+0.1s" to 100,
                    "+1s" to 1000
                )
                for ((label, delta) in specs) {
                    OutlinedButton(
                        onClick = {
                            val nextVal = if (delta == 0) 0 else state.millis + delta
                            state.update(nextVal)
                        },
                        enabled = !isEncoding,
                        modifier = buttonModifier,
                        colors = buttonColors,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(label, fontSize = 9.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("マイナス: HUDを戻す", color = Color(0xFF8E8E93), fontSize = 9.sp)
                Text("プラス: HUDを進める", color = Color(0xFF8E8E93), fontSize = 9.sp)
            }
            Divider(color = Color(0xFFE5E5EA))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("振動データで自動合わせ", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("動画の揺れとFIT側の加速度が取れる場合だけ使います。", color = Color(0xFF636366), fontSize = 9.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAlignTelemetryClick,
                    enabled = !isAligning && videoPath.isNotEmpty() && telemetryPoints.isNotEmpty() && !isEncoding,
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
                        Text("自動調整中...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("自動で時刻を合わせる", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
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
    onTrimEndChange: (Double) -> Unit,
    isEncoding: Boolean
) {
    val totalSec = videoLengthMs / 1000.0
    val range = trimStartSeconds.toFloat()..trimEndSeconds.toFloat()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("書き出す範囲", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("開始", color = Color(0xFF636366), fontSize = 9.sp)
                Text(utils.formatTime((trimStartSeconds * 1000).toLong()), color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("終了", color = Color(0xFF636366), fontSize = 9.sp)
                Text(utils.formatTime((trimEndSeconds * 1000).toLong()), color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        RangeSlider(
            value = range,
            onValueChange = { r ->
                onTrimStartChange(r.start.toDouble())
                onTrimEndChange(r.endInclusive.toDouble())
            },
            enabled = !isEncoding,
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
            Text("書き出し時間", color = Color(0xFF636366), fontSize = 9.sp)
            Text(
                utils.formatTime((durationSec * 1000).toLong()) + " (${String.format("%.1f", durationSec)}s)",
                color = Color(0xFF2E7D32),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
@Composable
fun VideoSplitCard(
    viewModel: AppViewModel,
    videoCurrentTimeMs: Long,
    isEncoding: Boolean
) {
    val splitPoints = viewModel.splitPoints
    val ranges = viewModel.getSplitRanges()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("分割ポイント", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
        val playheadSec = videoCurrentTimeMs / 1000.0
        val canAdd = !isEncoding && playheadSec > viewModel.trimStartSeconds && playheadSec < viewModel.trimEndSeconds && playheadSec !in splitPoints
        Button(
            onClick = { viewModel.addSplitPoint(playheadSec) },
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF007AFF),
                contentColor = Color.White
            )
        ) {
            Text("現在位置で分割 (${utils.formatTime(videoCurrentTimeMs)})", fontSize = 10.sp)
        }
        if (splitPoints.isNotEmpty()) {
            Text("設定済みの分割ポイント", color = Color(0xFF636366), fontSize = 9.sp)
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
                            enabled = !isEncoding,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFFFF3B30),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(24.dp).padding(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("削除", fontSize = 9.sp)
                        }
                    }
                }
            }
            Button(
                onClick = { viewModel.clearSplitPoints() },
                enabled = !isEncoding,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFFF3B30),
                    contentColor = Color.White
                )
            ) {
                Text("分割をすべてクリア", fontSize = 10.sp)
            }
        } else {
            Text("分割ポイントは未設定です。", color = Color(0xFF8E8E93), fontSize = 10.sp)
        }
        Divider(color = Color(0xFFE5E5EA))
        Text(
            text = "${ranges.size}本の動画として書き出します。",
            color = Color(0xFF2E7D32),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
@Composable
fun RoadCaptionEditDialog(
    segment: fit.RoadCaptionSegment,
    index: Int,
    videoCurrentTimeMs: Long,
    videoLengthMs: Long,
    viewModel: AppViewModel,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = androidx.compose.ui.Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(480.dp)
                .wrapContentHeight()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { /* クリック伝播防止 */ },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            elevation = 8.dp,
            backgroundColor = Color.White
        ) {
            Column(
                modifier = androidx.compose.ui.Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "路線名テロップの調整",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1C1E)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onClose,
                        modifier = androidx.compose.ui.Modifier.size(24.dp)
                    ) {
                        Text("❌", fontSize = 10.sp)
                    }
                }
                Divider(color = Color(0xFFE5E5EA))
                // Text Input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("表示テキスト", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = segment.text,
                        onValueChange = { newText ->
                            val updated = viewModel.settings.roadCaptions.mapIndexed { idx, item ->
                                if (idx == index) item.copy(text = newText) else item
                            }
                            viewModel.settings = viewModel.settings.copy(roadCaptions = updated)
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color(0xFF1C1C1E)),
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color(0xFF007AFF),
                            unfocusedBorderColor = Color(0xFFE5E5EA)
                        )
                    )
                }
                // Current Time Display
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "動画の現在位置:",
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = utils.formatTime(videoCurrentTimeMs),
                        fontSize = 11.sp,
                        color = Color(0xFF007AFF),
                        fontWeight = FontWeight.Bold
                    )
                }
                // In / Out controls side-by-side
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Start (In)
                    Column(
                        modifier = androidx.compose.ui.Modifier
                            .weight(1f)
                            .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("開始時間 (In)", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text(
                            text = utils.formatTime((segment.startSeconds * 1000).toLong()),
                            fontSize = 14.sp,
                            color = Color(0xFF007AFF),
                            fontWeight = FontWeight.Bold,
                            modifier = androidx.compose.ui.Modifier.clickable {
                                onSeek((segment.startSeconds * 1000).toLong())
                            }
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.updateRoadCaptionStart(index, segment.startSeconds - 1.0) },
                                modifier = androidx.compose.ui.Modifier.size(24.dp)
                            ) {
                                Text("◀ 1s", fontSize = 8.sp, color = Color.DarkGray)
                            }
                            IconButton(
                                onClick = { viewModel.updateRoadCaptionStart(index, segment.startSeconds + 1.0) },
                                modifier = androidx.compose.ui.Modifier.size(24.dp)
                            ) {
                                Text("1s ▶", fontSize = 8.sp, color = Color.DarkGray)
                            }
                        }
                        Button(
                            onClick = { viewModel.updateRoadCaptionStart(index, videoCurrentTimeMs / 1000.0) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = androidx.compose.ui.Modifier.height(24.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        ) {
                            Text("現在位置をInに設定", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    // End (Out)
                    Column(
                        modifier = androidx.compose.ui.Modifier
                            .weight(1f)
                            .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("終了時間 (Out)", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text(
                            text = utils.formatTime((segment.endSeconds * 1000).toLong()),
                            fontSize = 14.sp,
                            color = Color(0xFF007AFF),
                            fontWeight = FontWeight.Bold,
                            modifier = androidx.compose.ui.Modifier.clickable {
                                onSeek((segment.endSeconds * 1000).toLong())
                            }
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.updateRoadCaptionEnd(index, segment.endSeconds - 1.0) },
                                modifier = androidx.compose.ui.Modifier.size(24.dp)
                            ) {
                                Text("◀ 1s", fontSize = 8.sp, color = Color.DarkGray)
                            }
                            IconButton(
                                onClick = { viewModel.updateRoadCaptionEnd(index, segment.endSeconds + 1.0) },
                                modifier = androidx.compose.ui.Modifier.size(24.dp)
                            ) {
                                Text("1s ▶", fontSize = 8.sp, color = Color.DarkGray)
                            }
                        }
                        Button(
                            onClick = { viewModel.updateRoadCaptionEnd(index, videoCurrentTimeMs / 1000.0) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = androidx.compose.ui.Modifier.height(24.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        ) {
                            Text("現在位置をOutに設定", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                    modifier = androidx.compose.ui.Modifier.align(Alignment.End).height(28.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Text("閉じる", fontSize = 10.sp, color = Color(0xFF1C1C1E))
                }
            }
        }
    }
}
