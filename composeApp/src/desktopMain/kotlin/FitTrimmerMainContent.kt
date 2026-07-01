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
import components.VideoPreviewArea
import components.EncodingProgressArea
import components.TelemetryTimelineGraph
@Composable
fun FitTrimmerMainContent(
    viewModel: AppViewModel,
    args: Array<String>,
    composeWindow: java.awt.Window?,
    windowState: androidx.compose.ui.window.WindowState,
    onCloseRequest: () -> Unit
) {
    val initialCache = remember { GuiCache.load() }
    var settings by viewModel::settings
    var editingCaptionIndex by viewModel::editingCaptionIndex
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
    var roadScanIntervalSeconds by remember { mutableStateOf(30) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0f) }
    var updateStatusText by remember { mutableStateOf("") }
    var manualCheckFeedback by remember { mutableStateOf<String?>(null) }
    val playerState = rememberVideoPlayerState()
    var composeWindow: java.awt.Window? by remember { mutableStateOf(null) }
    var ignoreNextStartUtcClear by remember { mutableStateOf(false) }
    var setupStep by remember { mutableStateOf(if (settings.language.isEmpty()) 1 else 0) }
    var tempSelectedLanguage by remember { mutableStateOf("en") }
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
            captionPosition = settings.captionPosition,
            roadCaptions = settings.roadCaptions,
            powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
            useImperialUnits = settings.useImperialUnits,
            language = settings.language
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
    var previewQualityMode by viewModel::previewQualityMode
    var autoDetectRoadCaptionsOnEncode by viewModel::autoDetectRoadCaptionsOnEncode
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
    LaunchedEffect(videoPath, settings.blurLicensePlates, viewModel.videoStartUtc) {
        if (settings.blurLicensePlates && videoPath.isNotEmpty() && viewModel.plateCache == null) {
            viewModel.runPlateDetection(scope)
        }
    }
    val triggerUpdatePrompt = remember(latestReleaseInfo, composeWindow) {
        fun() {
            val release = latestReleaseInfo ?: return
            scope.launch(Dispatchers.Main) {
                try {
                    val runningUri = UpdateManager::class.java.protectionDomain.codeSource.location.toURI()
                    val runningFile = File(runningUri)
                    val ext = runningFile.extension.lowercase()
                    val isDev = ext != "jar" && ext != "exe"
                    val message = if (isDev) {
                        "新しいバージョン ${release.tagName} が利用可能です。\n\n" +
                        "変更内容:\n${release.body.take(300)}${if (release.body.length > 300) "..." else ""}\n\n" +
                        "※現在の環境は開発用ソース実行（$ext）のため自動更新できません。\n" +
                        "GitHubの配布ページを開いて最新版をダウンロードしますか？"
                    } else {
                        "新しいバージョン ${release.tagName} が利用可能です。\n\n" +
                        "変更内容:\n${release.body.take(300)}${if (release.body.length > 300) "..." else ""}\n\n" +
                        "今すぐ自動アップデートをダウンロードし、アプリを再起動しますか？"
                    }
                    val options = if (isDev) {
                        arrayOf("GitHubを開く", "キャンセル")
                    } else {
                        arrayOf("アップデートして再起動", "キャンセル")
                    }
                    val choice = javax.swing.JOptionPane.showOptionDialog(
                        composeWindow,
                        message,
                        "ソフトウェア・アップデート",
                        javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE,
                        null,
                        options,
                        options[0]
                    )
                    if (choice == javax.swing.JOptionPane.YES_OPTION) {
                        if (isDev) {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(release.htmlUrl))
                        } else {
                            val isWindows = System.getProperty("os.name").lowercase().contains("win")
                            val matchedAsset = if (isWindows) {
                                release.assets.find { it.name.endsWith(".msi") }
                            } else {
                                release.assets.find { it.name.endsWith(".dmg") }
                            } ?: release.assets.firstOrNull()
                            if (matchedAsset != null) {
                                isDownloadingUpdate = true
                                updateStatusText = "ダウンロード中 (0%)..."
                                statusText = "⏬ ソフトウェア更新: $updateStatusText"
                                scope.launch {
                                    val result = UpdateManager.performUpdate(
                                        downloadUrl = matchedAsset.browserDownloadUrl,
                                        fileName = matchedAsset.name,
                                        onProgress = { progress ->
                                            updateProgress = progress
                                            updateStatusText = "ダウンロード中 (${(progress * 100).toInt()}%)..."
                                            statusText = "⏬ ソフトウェア更新: $updateStatusText"
                                        }
                                    )
                                    isDownloadingUpdate = false
                                    if (result == "SUCCESS") {
                                        val tempDir = File(System.getProperty("java.io.tmpdir"))
                                        val batchFile = File(tempDir, "fit-trimmer-apply-update.bat")
                                        val launcherFile = File(tempDir, "fit-trimmer-launcher.vbs")
                                        val options = arrayOf("今すぐ再起動して適用", "後で（アプリ終了時に適用）", "キャンセル")
                                        val choice = javax.swing.JOptionPane.showOptionDialog(
                                            composeWindow,
                                            "アップデートのダウンロードが完了しました。\n今すぐアプリを再起動して適用しますか？",
                                            "アップデート適用",
                                            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
                                            javax.swing.JOptionPane.QUESTION_MESSAGE,
                                            null,
                                            options,
                                            options[0]
                                        )
                                        if (choice == 0) { // 今すぐ再起動して適用
                                            statusText = "アップデート完了。再起動します..."
                                            val isWindows = System.getProperty("os.name").lowercase().contains("win")
                                            if (isWindows) {
                                                launcherFile.writeText(
                                                    "Set WshShell = CreateObject(\"WScript.Shell\")\n" +
                                                    "WshShell.Run \"cmd.exe /c \"\"" + batchFile.absolutePath + "\"\" restart\", 0, false",
                                                    charset("Shift_JIS")
                                                )
                                                ProcessBuilder("wscript.exe", launcherFile.absolutePath)
                                                    .directory(tempDir)
                                                    .start()
                                            } else {
                                                val shellFile = File(tempDir, "fit-trimmer-apply-update.sh")
                                                ProcessBuilder("/bin/bash", shellFile.absolutePath)
                                                    .directory(tempDir)
                                                    .start()
                                            }
                                            System.exit(0)
                                        } else if (choice == 1) { // 後で（アプリ終了時に適用）
                                            statusText = "⏬ アップデート待機中（次回終了時に適用）"
                                            val isWindows = System.getProperty("os.name").lowercase().contains("win")
                                            if (isWindows) {
                                                // Re-write launcher VBS to execute batch WITHOUT "restart" parameter
                                                launcherFile.writeText(
                                                    "Set WshShell = CreateObject(\"WScript.Shell\")\n" +
                                                    "WshShell.Run \"cmd.exe /c \"\"" + batchFile.absolutePath + "\"\"\", 0, false",
                                                    charset("Shift_JIS")
                                                )
                                                Runtime.getRuntime().addShutdownHook(Thread {
                                                    try {
                                                        ProcessBuilder("wscript.exe", launcherFile.absolutePath)
                                                            .directory(tempDir)
                                                            .start()
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                })
                                            } else {
                                                val shellFile = File(tempDir, "fit-trimmer-apply-update.sh")
                                                Runtime.getRuntime().addShutdownHook(Thread {
                                                    try {
                                                        ProcessBuilder("/bin/bash", shellFile.absolutePath)
                                                            .directory(tempDir)
                                                            .start()
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                })
                                            }
                                            javax.swing.JOptionPane.showMessageDialog(
                                                composeWindow,
                                                "アップデートは保留されました。\n次回アプリ終了時にバックグラウンドで適用されます。",
                                                "アップデート保留",
                                                javax.swing.JOptionPane.INFORMATION_MESSAGE
                                            )
                                        } else { // キャンセル
                                            statusText = "アップデート適用をキャンセルしました"
                                            try {
                                                batchFile.delete()
                                                launcherFile.delete()
                                                val shellFile = File(tempDir, "fit-trimmer-apply-update.sh")
                                                shellFile.delete()
                                            } catch (e: Exception) {
                                                // Ignore cleanup errors
                                            }
                                        }
                                    } else if (result == "DEVELOPMENT_ENV") {
                                        javax.swing.JOptionPane.showMessageDialog(
                                            composeWindow,
                                            "開発環境での実行を検知したため、自動更新を中止しました。\nGit Pull 等で手動更新を行ってください。",
                                            "アップデート中止",
                                            javax.swing.JOptionPane.WARNING_MESSAGE
                                        )
                                    } else {
                                        javax.swing.JOptionPane.showMessageDialog(
                                            composeWindow,
                                            "アップデート適用中にエラーが発生しました:\n$result",
                                            "エラー",
                                            javax.swing.JOptionPane.ERROR_MESSAGE
                                        )
                                    }
                                }
                            } else {
                                javax.swing.JOptionPane.showMessageDialog(
                                    composeWindow,
                                    "該当するリリースアセットが見つかりませんでした。\nブラウザから手動でインストールしてください。",
                                    "アセット不足",
                                    javax.swing.JOptionPane.ERROR_MESSAGE
                                )
                                java.awt.Desktop.getDesktop().browse(java.net.URI(release.htmlUrl))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
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
        // Startup silent update check (only if not encoding and not in development environment)
        if (!isEncoding) {
            if (UpdateManager.isDevelopment()) {
                println("=== UPDATE CHECK SKIPPED (DEVELOPMENT ENVIRONMENT DETECTED) ===")
            } else {
                scope.launch {
                    try {
                        val latest = UpdateManager.fetchLatestRelease()
                        if (latest != null && UpdateManager.isNewerVersion(APP_VERSION, latest.tagName)) {
                            latestReleaseInfo = latest
                            triggerUpdatePrompt()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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
    // Save path cache when modified.
    LaunchedEffect(fitPath, videoPath, videoStartUtc, timeOffsetState.millis, settings, moveOutputToSource, showLivePreview, previewQualityMode, autoDetectRoadCaptionsOnEncode, windowState.position, windowState.size, viewModel.splitPoints) {
        if (isLoaded) {
            kotlinx.coroutines.delay(500)
            if (GuiCache.shouldDeferSaveUntilVideoStartIsLoaded(videoPath, videoStartUtc)) {
                println("DEBUG: Deferring GUI cache save until videoStartUtc is loaded for $videoPath")
                return@LaunchedEffect
            }
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
                        previewQualityMode = previewQualityMode,
                        autoDetectRoadCaptionsOnEncode = autoDetectRoadCaptionsOnEncode,
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
        // Clear derived video metadata immediately to prevent stale data from previous video
        videoLengthMs = 0L
        isHudBurned = false
        // videoStartUtc is ephemeral — always read from the file, never from cache.
        // Clear it immediately; it will be repopulated by getVideoStartUtc() below.
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

    suspend fun prepareSettingsForEncode(baseSettings: HudSettings): HudSettings {
        return prepareRoadCaptionSettingsForEncode(
            baseSettings = baseSettings,
            autoDetectRoadCaptionsOnEncode = autoDetectRoadCaptionsOnEncode,
            context = RoadCaptionDetectionContext(
                points = telemetryPoints,
                videoStartUtc = adjustedStartUtc.ifEmpty { videoStartUtc },
                timeOffsetMillis = timeOffsetState.millis.toLong(),
                videoDurationSeconds = videoLengthMs / 1000.0
            ),
            onStatus = { progressText ->
                statusText = progressText
            },
            onSettingsPrepared = { preparedSettings ->
                settings = preparedSettings
            }
        )
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
                                        try {
                                            val encodeSettings = prepareSettingsForEncode(settings)
                                            val ranges = viewModel.getSplitRanges()
                                            val encodePlan = buildEncodePlan(
                                                settings = encodeSettings,
                                                videoPath = videoPath,
                                                outputDir = outputDir,
                                                moveOutputToSource = moveOutputToSource,
                                                ranges = ranges
                                            )
                                            val destFiles = encodePlan.segments.map { it.finalOutputFile }
                                            val resultMsg = HudEncodePipeline.execute(
                                                s = encodeSettings,
                                                fitPath = fitPath,
                                                videoPath = videoPath,
                                                outputDir = outputDir,
                                                videoStartUtc = adjustedStartUtc,
                                                ranges = ranges,
                                                destFiles = destFiles,
                                                isSample = false,
                                                shouldResume = false,
                                                moveOutputToSource = moveOutputToSource,
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
                                                cancelSupplier = { isCanceled },
                                                showLivePreviewSupplier = { showLivePreview },
                                                onSegmentStart = { pStart, pEnd ->
                                                    viewModel.encodingSegmentStart = pStart
                                                    viewModel.encodingSegmentEnd = pEnd
                                                }
                                            )
                                            statusText = resultMsg
                                            if (args.contains("--auto-sample")) {
                                                println("TEST_NOTIFICATION_SUCCESS: Encoding Finished Successfully!")
                                            } else {
                                                showSystemNotification(
                                                    "HUD エンコーダー",
                                                    "Encoding Finished Successfully!"
                                                )
                                            }
                                        } catch (e: Exception) {
                                            statusText = "❌ Error: ${e.message ?: "Unknown error"}"
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
    DisposableEffect(cp) {
        cp.start()
        onDispose {
            cp.stop()
        }
    }
        val textMeasurer = rememberTextMeasurer()
        val effectiveVideoTimeMs by remember {
            derivedStateOf {
                if (isEncoding) {
                    val actualTrimStart = trimStartSeconds.coerceIn(0.0, videoLengthMs / 1000.0)
                    val actualTrimEnd = if (trimEndSeconds <= 0.0 || trimEndSeconds > videoLengthMs / 1000.0) {
                        videoLengthMs / 1000.0
                    } else {
                        trimEndSeconds
                    }
                    val durationSec = actualTrimEnd - actualTrimStart
                    ((actualTrimStart + progress * durationSec) * 1000).toLong()
                } else {
                    videoCurrentTimeMs
                }
            }
        }
        MaterialTheme(colors = lightColors(primary = Color(0xFF007AFF))) {
            val isPreviewFullscreen = viewModel.isPreviewFullscreen
            val showSidebar = viewModel.isSidebarVisible && !isPreviewFullscreen
            Row(modifier = Modifier.fillMaxSize().background(if (isPreviewFullscreen) Color.Black else Color.White)) {
                val sidebarScrollState = rememberScrollState()
                val sidebarWidth = if (showSidebar) 320.dp else 0.dp
                Box(modifier = Modifier.width(sidebarWidth).fillMaxHeight()) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F7))
                            .verticalScroll(sidebarScrollState).padding(
                                horizontal = if (showSidebar) 16.dp else 0.dp,
                                vertical = if (showSidebar) 16.dp else 0.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    val onNativeEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, adjustedStartUtc, isVideoInFitRange, outputDir, moveOutputToSource, showLivePreview, autoDetectRoadCaptionsOnEncode, telemetryPoints, videoLengthMs, timeOffsetState.millis, viewModel.splitPoints) {
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
                            val initialPlan = buildEncodePlan(
                                settings = settings,
                                videoPath = targetVideoPath,
                                outputDir = outputDir,
                                moveOutputToSource = moveOutputToSource,
                                ranges = ranges
                            )
                            val destFiles = mutableListOf<File>()
                            val targetDestFiles = initialPlan.segments.map { it.finalOutputFile }
                            // Check for existing outputs
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
                                        val encodeSettings = prepareSettingsForEncode(settings)
                                        val resultMsg = HudEncodePipeline.execute(
                                            s = encodeSettings,
                                            fitPath = fitPath,
                                            videoPath = targetVideoPath,
                                            outputDir = outputDir,
                                            videoStartUtc = adjustedStartUtc,
                                            ranges = ranges,
                                            destFiles = destFiles,
                                            isSample = false,
                                            shouldResume = shouldResume,
                                            moveOutputToSource = moveOutputToSource,
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
                                            cancelSupplier = { isCanceled },
                                            showLivePreviewSupplier = { showLivePreview },
                                            onSegmentStart = { pStart, pEnd ->
                                                viewModel.encodingSegmentStart = pStart
                                                viewModel.encodingSegmentEnd = pEnd
                                            }
                                        )
                                        statusText = resultMsg
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
                                        statusText = "❌ Error: ${e.message ?: "Unknown error"}"
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
                    val onSampleEncodeClick = remember(settings, fitPath, videoPath, videoStartUtc, adjustedStartUtc, isVideoInFitRange, outputDir, moveOutputToSource, showLivePreview, autoDetectRoadCaptionsOnEncode, telemetryPoints, videoLengthMs, timeOffsetState.millis) {
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
                                    try {
                                        val encodeSettings = prepareSettingsForEncode(settings)
                                        val samplePlan = buildEncodePlan(
                                            settings = encodeSettings,
                                            videoPath = targetVideoPath,
                                            outputDir = outputDir,
                                            moveOutputToSource = moveOutputToSource,
                                            ranges = listOf(Pair(trimStartSeconds, trimStartSeconds + 5.0)),
                                            isSample = true
                                        )
                                        val destFile = samplePlan.segments.first().finalOutputFile
                                        val resultMsg = HudEncodePipeline.execute(
                                            s = encodeSettings,
                                            fitPath = fitPath,
                                            videoPath = targetVideoPath,
                                            outputDir = outputDir,
                                            videoStartUtc = adjustedStartUtc,
                                            ranges = listOf(Pair(trimStartSeconds, trimStartSeconds + 5.0)),
                                            destFiles = listOf(destFile),
                                            isSample = true,
                                            shouldResume = false,
                                            moveOutputToSource = moveOutputToSource,
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
                                            cancelSupplier = { isCanceled },
                                            showLivePreviewSupplier = { showLivePreview },
                                            onSegmentStart = { pStart, pEnd ->
                                                viewModel.encodingSegmentStart = pStart
                                                viewModel.encodingSegmentEnd = pEnd
                                            }
                                        )
                                        statusText = resultMsg
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
                                        statusText = "❌ Error: ${e.message ?: "Unknown error"}"
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
                                            onCloseRequest()
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
                    // 1. Source file selection
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
                            Text("ソースファイル", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            Text("GPSログと動画を選び、同じ走行範囲を指しているか確認します。", color = Color(0xFF636366), fontSize = 10.sp, lineHeight = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = fitPath,
                                    onValueChange = { fitPath = it },
                                    label = { Text("FIT / GPSログ", color = Color(0xFF636366), fontSize = 10.sp) },
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
                                        val path = pickFile("ソースファイルの選択 - FIT / GPSログ", listOf("*.fit"))
                                        if (path != null) fitPath = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("選択", color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = videoPath,
                                    onValueChange = { videoPath = it },
                                    label = { Text("動画ファイル", color = Color(0xFF636366), fontSize = 10.sp) },
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
                                        val path = pickFile("ソースファイルの選択 - 動画ファイル", listOf("*.mp4", "*.mov"))
                                        if (path != null) videoPath = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("選択", color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            }
                            SourceRangeSummary(
                                fitStartInstant = fitStartInstant,
                                fitEndInstant = fitEndInstant,
                                videoStartInstant = videoStartInstant,
                                videoEndInstant = videoEndInstant,
                                isVideoInFitRange = isVideoInFitRange,
                                isHudBurned = isHudBurned
                            )
                        }
                    }
                    // 2. Timeline alignment
                    TimeAlignmentCard(
                        state = timeOffsetState,
                        isEncoding = isEncoding,
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
                    // 3. Primary encode actions
                    Card(
                        backgroundColor = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                        elevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("エンコード", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            val isActiveEncoding = isEncoding || viewModel.isBatchRunning
                            if (!isActiveEncoding) {
                                Text("現在の設定でHUD付き動画を書き出します。サンプルは短い確認用、バッチは後でまとめて処理する待ち行列です。", color = Color(0xFF636366), fontSize = 10.sp, lineHeight = 13.sp)
                                Button(
                                    onClick = onNativeEncodeClick,
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    enabled = hasEnoughSpace && fitPath.isNotEmpty() && videoPath.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (hasEnoughSpace) Color(0xFF007AFF) else Color(0xFFD1D1D6),
                                        disabledBackgroundColor = Color(0xFFE5E5EA)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (hasEnoughSpace) "エンコード開始" else "ディスク容量不足", color = if (hasEnoughSpace) Color.White else Color(0xFF8E8E93), fontWeight = FontWeight.Bold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    val isSampleEnabled = hasEnoughSpaceForSample && fitPath.isNotEmpty() && videoPath.isNotEmpty()
                                    OutlinedButton(
                                        onClick = onSampleEncodeClick,
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        enabled = isSampleEnabled,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFF007AFF),
                                            disabledContentColor = Color(0xFF8E8E93)
                                        ),
                                        border = BorderStroke(1.5.dp, if (isSampleEnabled) Color(0xFF007AFF) else Color(0xFFE5E5EA)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    ) {
                                        Text("5秒サンプル", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    val isQueueEnabled = fitPath.isNotEmpty() && videoPath.isNotEmpty()
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.addToBatchQueue()
                                            statusText = "ジョブをキューに追加しました"
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        enabled = isQueueEnabled,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFF34C759),
                                            disabledContentColor = Color(0xFF8E8E93)
                                        ),
                                        border = BorderStroke(1.5.dp, if (isQueueEnabled) Color(0xFF34C759) else Color(0xFFE5E5EA)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    ) {
                                        Text("バッチに追加", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                val currentStatusText = if (viewModel.isBatchRunning) viewModel.statusText else statusText
                                val currentProgress = if (viewModel.isBatchRunning) viewModel.progress else progress
                                val currentPaused = if (viewModel.isBatchRunning) viewModel.isPaused else isPaused

                                if (currentStatusText.contains("Merging", ignoreCase = true)) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF34C759),
                                        backgroundColor = Color(0xFFE5E5EA)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = currentProgress,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (currentPaused) Color(0xFFFF9F0A) else Color(0xFF007AFF),
                                        backgroundColor = Color(0xFFE5E5EA)
                                    )
                                }
                                Text(if (currentPaused) "PAUSED (Not enough space or paused manually)\n$currentStatusText" else currentStatusText, color = Color(0xFF1C1C1E), fontSize = 11.sp, lineHeight = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (viewModel.isBatchRunning) {
                                                viewModel.isPaused = !viewModel.isPaused
                                            } else {
                                                isPaused = !isPaused
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        enabled = !(!currentPaused && !hasEnoughSpace),
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = if (currentPaused) Color(0xFF34C759) else Color(0xFFFF9F0A),
                                            disabledBackgroundColor = Color(0xFFE5E5EA)
                                        ),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    ) {
                                        Text(if (currentPaused) "RESUME" else "PAUSE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            if (viewModel.isBatchRunning) {
                                                viewModel.isCanceled = true
                                            } else {
                                                isCanceled = true
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEF4444)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    ) {
                                        Text("CANCEL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            val batchQueue = viewModel.batchQueue
                            if (batchQueue.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider(color = Color(0xFFE5E5EA))
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "BATCH JOB QUEUE (${batchQueue.size})",
                                        color = Color(0xFF1C1C1E),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                    if (!viewModel.isBatchRunning) {
                                        Text(
                                            text = "CLEAR ALL",
                                            color = Color(0xFFE02424),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable { viewModel.clearBatchQueue() }
                                        )
                                    }
                                }
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    batchQueue.forEach { job ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = File(job.videoPath).name,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1C1C1E)
                                                )
                                                Text(
                                                    text = "Trim: %.1fs - %.1fs".format(job.trimStartSeconds, job.trimEndSeconds),
                                                    fontSize = 8.sp,
                                                    color = Color(0xFF636366)
                                                )
                                                if (job.status == BatchJobStatus.RUNNING) {
                                                    LinearProgressIndicator(
                                                        progress = job.progress,
                                                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
                                                        color = Color(0xFF007AFF)
                                                    )
                                                }
                                                if (job.status == BatchJobStatus.FAILED && !job.errorMessage.isNullOrEmpty()) {
                                                    Text(
                                                        text = "Error: ${job.errorMessage}",
                                                        fontSize = 8.sp,
                                                        color = Color(0xFFE02424),
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            val statusLabel = when (job.status) {
                                                BatchJobStatus.WAITING -> "待機中"
                                                BatchJobStatus.RUNNING -> "処理中"
                                                BatchJobStatus.COMPLETED -> "完了"
                                                BatchJobStatus.FAILED -> "失敗"
                                            }
                                            val statusColor = when (job.status) {
                                                BatchJobStatus.WAITING -> Color(0xFF8E8E93)
                                                BatchJobStatus.RUNNING -> Color(0xFF007AFF)
                                                BatchJobStatus.COMPLETED -> Color(0xFF34C759)
                                                BatchJobStatus.FAILED -> Color(0xFFE02424)
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = statusLabel,
                                                    color = statusColor,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (!viewModel.isBatchRunning && job.status != BatchJobStatus.RUNNING) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clickable { viewModel.removeFromBatchQueue(job.id) },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Canvas(modifier = Modifier.size(8.dp)) {
                                                            val strokeWidth = 1.5.dp.toPx()
                                                            drawLine(
                                                                color = Color(0xFFEF4444),
                                                                start = Offset(0f, 0f),
                                                                end = Offset(size.width, size.height),
                                                                strokeWidth = strokeWidth,
                                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                                            )
                                                            drawLine(
                                                                color = Color(0xFFEF4444),
                                                                start = Offset(size.width, 0f),
                                                                end = Offset(0f, size.height),
                                                                strokeWidth = strokeWidth,
                                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (viewModel.isBatchRunning) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().background(Color(0xFFFEF3C7)).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = viewModel.batchStatusText,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD97706)
                                        )
                                        Button(
                                            onClick = { isCanceled = true },
                                            modifier = Modifier.fillMaxWidth().height(28.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE02424))
                                        ) {
                                            Text("CANCEL BATCH", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    val canStartBatch = batchQueue.any { it.status == BatchJobStatus.WAITING || it.status == BatchJobStatus.FAILED }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                batchQueue.forEach {
                                                    if (it.status == BatchJobStatus.FAILED || it.status == BatchJobStatus.COMPLETED) {
                                                        it.status = BatchJobStatus.WAITING
                                                        it.progress = 0f
                                                    }
                                                }
                                                runBatchJobs(
                                                    viewModel = viewModel,
                                                    outputDir = outputDir,
                                                    moveOutputToSource = moveOutputToSource,
                                                    showLivePreview = showLivePreview,
                                                    onProgressUpdate = {}
                                                )
                                            }
                                        },
                                        enabled = canStartBatch,
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF34C759))
                                    ) {
                                        Text("START BATCH ENCODE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // 4. Output and less frequently changed options
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
                            Text("出力設定", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            Text("書き出し先、画質、路線テロップ、動画の切り出し方を設定します。", color = Color(0xFF636366), fontSize = 10.sp, lineHeight = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = outputDir,
                                    onValueChange = { outputDir = it },
                                    label = { Text("出力先フォルダ", color = Color(0xFF636366), fontSize = 10.sp) },
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
                                        val path = pickFolder("出力先フォルダの選択")
                                        if (path != null) outputDir = path
                                    },
                                    enabled = !isEncoding,
                                    modifier = Modifier.height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFFE5E5EA),
                                        disabledBackgroundColor = Color(0xFFF2F2F7)
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ) { Text("選択", color = Color(0xFF1C1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
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
                                       Text("元動画フォルダへ書き戻す", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                       Text("ローカルでエンコード後、Google Drive等の元フォルダへ完成動画を移動します", color = Color(0xFF636366), fontSize = 9.sp)
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
                                       Text("ライブプレビュー", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                       Text("オフにするとエンコードが少し軽くなります", color = Color(0xFF636366), fontSize = 9.sp)
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
                                       Text("出力解像度", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                       Text("容量と処理時間を抑える場合は解像度を下げます", color = Color(0xFF636366), fontSize = 9.sp)
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
                              if (videoLengthMs > 0) {
                                  Column(
                                      modifier = Modifier
                                          .fillMaxWidth()
                                          .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                          .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                          .padding(horizontal = 10.dp, vertical = 8.dp),
                                      verticalArrangement = Arrangement.spacedBy(8.dp)
                                  ) {
                                      Column {
                                          Text("詳細な書き出し範囲", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          Text("動画の一部だけを書き出したり、複数ファイルに分割したりします。", color = Color(0xFF636366), fontSize = 8.sp)
                                      }
                                      VideoTrimCard(
                                          videoLengthMs = videoLengthMs,
                                          trimStartSeconds = trimStartSeconds,
                                          trimEndSeconds = trimEndSeconds,
                                          onTrimStartChange = { trimStartSeconds = it },
                                          onTrimEndChange = { trimEndSeconds = it },
                                          isEncoding = isEncoding
                                      )
                                      VideoSplitCard(
                                          viewModel = viewModel,
                                          videoCurrentTimeMs = effectiveVideoTimeMs,
                                          isEncoding = isEncoding
                                      )
                                  }
                                  Spacer(modifier = Modifier.height(6.dp))
                              }
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
                                   Row(
                                       modifier = Modifier
                                           .fillMaxWidth()
                                           .background(Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                           .border(0.5.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                           .padding(horizontal = 8.dp, vertical = 6.dp),
                                       horizontalArrangement = Arrangement.SpaceBetween,
                                       verticalAlignment = Alignment.CenterVertically
                                   ) {
                                       Column(modifier = Modifier.weight(1f)) {
                                           Text("エンコード前に自動再検出", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                           Text("既存テロップをクリアしてGPSから再アサインします", color = Color(0xFF636366), fontSize = 8.sp)
                                       }
                                       Switch(
                                           checked = autoDetectRoadCaptionsOnEncode,
                                           onCheckedChange = { autoDetectRoadCaptionsOnEncode = it },
                                           enabled = !isEncoding,
                                           colors = SwitchDefaults.colors(
                                               checkedThumbColor = Color.White,
                                               checkedTrackColor = Color(0xFF007AFF)
                                           )
                                       )
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
                                                               language = settings.language,
                                                               onProgress = { progressText ->
                                                                  roadDetectionStatus = progressText
                                                              }
                                                          )
                                                          settings = settings.copy(roadCaptions = detected)
                                                      } catch (e: Throwable) {
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
                                                                                                                     Spacer(modifier = Modifier.height(2.dp))
                                                           Row(
                                                               verticalAlignment = Alignment.CenterVertically,
                                                               modifier = Modifier.fillMaxWidth()
                                                           ) {
                                                               Text(
                                                                   text = "${utils.formatTime((segment.startSeconds * 1000).toLong())} - ${utils.formatTime((segment.endSeconds * 1000).toLong())}",
                                                                   fontSize = 9.sp,
                                                                   color = Color.Gray
                                                               )
                                                               Spacer(Modifier.weight(1f))
                                                               Button(
                                                                   onClick = {
                                                                       editingCaptionIndex = index
                                                                   },
                                                                   colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                                                                   contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                                   shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                                                   modifier = Modifier.height(18.dp)
                                                               ) {
                                                                   Text("⚙ 編集", fontSize = 8.sp, color = Color(0xFF1C1C1E))
                                                               }
                                                           }
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
                                                               language = settings.language,
                                                               onProgress = { progressText ->
                                                                      roadDetectionStatus = progressText
                                                                  }
                                                              )
                                                              settings = settings.copy(roadCaptions = detected)
                                                          } catch (e: Throwable) {
                                                              e.printStackTrace()
                                                              roadDetectionStatus = "エラーが発生しました: ${e.message}"
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
                        }
                    }
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
                            Text("その他", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            Text("更新確認、設定ファイル、アプリが自動保存する作業状態を扱います。", color = Color(0xFF636366), fontSize = 10.sp, lineHeight = 13.sp)
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
                                          Text("ソフトウェア更新", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                          Text("新しいリリースがあるか確認します。現在: $APP_VERSION", color = Color(0xFF636366), fontSize = 8.sp)
                                      }
                                  }
                                  if (isCheckingUpdate) {
                                      Row(
                                          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                          horizontalArrangement = Arrangement.spacedBy(6.dp),
                                          verticalAlignment = Alignment.CenterVertically
                                      ) {
                                          CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFF007AFF))
                                          Text("更新プログラムを確認中...", fontSize = 9.sp, color = Color(0xFF1C1C1E))
                                      }
                                  } else if (isDownloadingUpdate) {
                                      Column(
                                          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                          verticalArrangement = Arrangement.spacedBy(4.dp)
                                      ) {
                                          Text(updateStatusText, fontSize = 9.sp, color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold)
                                          LinearProgressIndicator(
                                              progress = updateProgress,
                                              modifier = Modifier.fillMaxWidth().height(4.dp),
                                              color = Color(0xFF34C759),
                                              backgroundColor = Color(0xFFE5E5EA)
                                          )
                                      }
                                  } else {
                                      Button(
                                          onClick = {
                                              if (UpdateManager.isDevelopment()) {
                                                  manualCheckFeedback = "開発環境のため更新は無効化されています"
                                                  return@Button
                                              }
                                              isCheckingUpdate = true
                                              manualCheckFeedback = null
                                              scope.launch {
                                                  try {
                                                      val latest = UpdateManager.fetchLatestRelease()
                                                      if (latest != null) {
                                                          latestReleaseInfo = latest
                                                          if (UpdateManager.isNewerVersion(APP_VERSION, latest.tagName)) {
                                                              triggerUpdatePrompt()
                                                          } else {
                                                              manualCheckFeedback = "最新のバージョンです ($APP_VERSION)"
                                                          }
                                                      } else {
                                                          manualCheckFeedback = "更新情報の取得に失敗しました"
                                                      }
                                                  } catch (e: Exception) {
                                                       manualCheckFeedback = "エラー: ${e.message}"
                                                  } finally {
                                                      isCheckingUpdate = false
                                                  }
                                              }
                                          },
                                          colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF)),
                                          contentPadding = PaddingValues(0.dp),
                                          shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                          modifier = Modifier.fillMaxWidth().height(24.dp),
                                          enabled = !isEncoding
                                      ) {
                                          Text("更新を確認", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                      }
                                      manualCheckFeedback?.let { feedback ->
                                          Text(feedback, fontSize = 9.sp, color = if (feedback.contains("エラー") || feedback.contains("失敗")) Color.Red else Color(0xFF34C759))
                                      }
                                  }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column {
                                    Text("設定ファイル", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    Text("HUD配置、同期、トリム、分割、選択中のソースをJSONで読み書きします。", color = Color(0xFF636366), fontSize = 8.sp)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val path = pickFile("設定JSONを読み込む", listOf("*.json"))
                                            if (path != null) {
                                                scope.launch {
                                                    try {
                                                        val jsonStr = File(path).readText(Charsets.UTF_8)
                                                        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                                        try {
                                                            val loadedCache = jsonParser.decodeFromString<utils.GuiPathCache>(jsonStr)
                                                            var pathChanged = false
                                                            if (loadedCache.videoPath.isNotEmpty() && loadedCache.videoPath != videoPath && File(loadedCache.videoPath).exists()) {
                                                                videoPath = loadedCache.videoPath
                                                                pathChanged = true
                                                            }
                                                            if (loadedCache.fitPath.isNotEmpty() && loadedCache.fitPath != fitPath && File(loadedCache.fitPath).exists()) {
                                                                fitPath = loadedCache.fitPath
                                                                pathChanged = true
                                                            }
                                                            if (pathChanged) {
                                                                var timeoutCount = 0
                                                                while (timeoutCount < 80) {
                                                                    val isVideoReady = videoPath.isEmpty() || (videoStartUtc.isNotEmpty() && videoLengthMs > 0L)
                                                                    val isFitReady = fitPath.isEmpty() || telemetryPoints.isNotEmpty()
                                                                    if (isVideoReady && isFitReady) break
                                                                    kotlinx.coroutines.delay(100)
                                                                    timeoutCount++
                                                                }
                                                                kotlinx.coroutines.delay(200)
                                                            }
                                                            settings = loadedCache.settings
                                                            moveOutputToSource = loadedCache.moveOutputToSource
                                                            showLivePreview = loadedCache.showLivePreview
                                                            previewQualityMode = loadedCache.previewQualityMode
                                                            autoDetectRoadCaptionsOnEncode = loadedCache.autoDetectRoadCaptionsOnEncode
                                                            loadedCache.timeOffsetMillis?.let { timeOffsetState.update(it) }
                                                            loadedCache.trimStartSeconds?.let { trimStartSeconds = it }
                                                            loadedCache.trimEndSeconds?.let { trimEndSeconds = it }
                                                            loadedCache.splitPoints?.let { viewModel.splitPoints = it }
                                                            statusText = "設定ファイル（同期範囲・パス込）を読み込みました"
                                                        } catch (e: Exception) {
                                                            val loadedSettings = jsonParser.decodeFromString<fit.HudSettings>(jsonStr)
                                                            settings = loadedSettings
                                                            statusText = "HUD設定ファイルを読み込みました"
                                                        }
                                                    } catch (e: Exception) {
                                                        statusText = "読み込みエラー: ${e.message}"
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                                        contentPadding = PaddingValues(0.dp),
                                        enabled = !isEncoding
                                    ) {
                                        Text("読み込み", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            val path = saveFile("設定JSONを書き出す", "fit_trimmer_settings.json")
                                            if (path != null) {
                                                try {
                                                    val cacheToSave = utils.GuiPathCache(
                                                        fitPath = fitPath,
                                                        videoPath = videoPath,
                                                        videoStartUtc = adjustedStartUtc,
                                                        timeOffsetMillis = timeOffsetState.millis,
                                                        settings = settings,
                                                        moveOutputToSource = moveOutputToSource,
                                                        showLivePreview = showLivePreview,
                                                        previewQualityMode = previewQualityMode,
                                                        autoDetectRoadCaptionsOnEncode = autoDetectRoadCaptionsOnEncode,
                                                        trimStartSeconds = trimStartSeconds,
                                                        trimEndSeconds = trimEndSeconds,
                                                        splitPoints = viewModel.splitPoints.toList()
                                                    )
                                                    val jsonStr = kotlinx.serialization.json.Json.encodeToString(cacheToSave)
                                                    File(path).writeText(jsonStr, Charsets.UTF_8)
                                                    statusText = "設定ファイルを書き出しました"
                                                } catch (e: Exception) {
                                                    statusText = "保存エラー: ${e.message}"
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5E5EA)),
                                        contentPadding = PaddingValues(0.dp),
                                        enabled = !isEncoding
                                    ) {
                                        Text("書き出し", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFE5E5EA), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("自動保存と履歴", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Text("現在の作業状態はユーザーフォルダの .fittrimmer_gui_cache.json に自動保存されます。", color = Color(0xFF636366), fontSize = 8.sp, lineHeight = 11.sp)
                                Text("動画を切り替えると、動画ごとのトリム・分割・テロップ等を .fittrimmer_history に保存/復元します。", color = Color(0xFF636366), fontSize = 8.sp, lineHeight = 11.sp)
                                Text("動画開始時刻はキャッシュを信用せず、動画ファイルから読み直します。", color = Color(0xFF636366), fontSize = 8.sp, lineHeight = 11.sp)
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
                            Text(utils.Localizer.get("hud_layout_config", settings.language).uppercase(), color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                            ControlSlider("VAL SIZE", settings.valSize, 10f, 150f, enabled = !isEncoding) { settings = settings.copy(valSize = it) }
                            ControlSlider("TIGHTNESS", settings.tightness, -10f, 40f, enabled = !isEncoding) { settings = settings.copy(tightness = it) }
                            ControlSlider("SPACING", settings.spacing, 0f, 100f, enabled = !isEncoding) { settings = settings.copy(spacing = it) }
                            ControlSlider("X OFFSET", settings.xOffset, 0f, 500f, enabled = !isEncoding) { settings = settings.copy(xOffset = it) }
                            ControlSlider("Y OFFSET", settings.yOffset, 0f, 500f, enabled = !isEncoding) { settings = settings.copy(yOffset = it) }
                            ControlSlider("GRAPH H", settings.graphH, 20f, 300f, enabled = !isEncoding) { settings = settings.copy(graphH = it) }
                            ControlSlider("GRAPH W", settings.graphW, 50f, 800f, enabled = !isEncoding) { settings = settings.copy(graphW = it) }
                            Spacer(Modifier.height(4.dp))
                            Text(utils.Localizer.get("language", settings.language).uppercase(), color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp)
                            var langDropdownExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                val currentLang = utils.Localizer.supportedLanguages.find { it.code == settings.language } ?: utils.Localizer.supportedLanguages.first()
                                OutlinedButton(
                                    onClick = { if (!isEncoding) langDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    border = BorderStroke(1.dp, Color(0xFFE5E5EA)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${currentLang.localName} (${currentLang.englishName})", fontSize = 11.sp)
                                        Text("▼", fontSize = 8.sp, color = Color.Gray)
                                    }
                                }
                                DropdownMenu(
                                    expanded = langDropdownExpanded,
                                    onDismissRequest = { langDropdownExpanded = false }
                                ) {
                                    utils.Localizer.supportedLanguages.forEach { lang ->
                                        DropdownMenuItem(
                                            onClick = {
                                                settings = settings.copy(language = lang.code)
                                                langDropdownExpanded = false
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.width(200.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(lang.localName, fontSize = 12.sp, color = Color(0xFF1C1C1E))
                                                Text(lang.englishName, fontSize = 10.sp, color = Color.Gray)
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !isEncoding) {
                                    settings = settings.copy(useImperialUnits = !settings.useImperialUnits)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = settings.useImperialUnits,
                                    onCheckedChange = { checked ->
                                        if (!isEncoding) {
                                            settings = settings.copy(useImperialUnits = checked)
                                        }
                                    },
                                    enabled = !isEncoding,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF007AFF))
                                )
                                Text(
                                    text = utils.Localizer.get("use_imperial_units", settings.language),
                                    fontSize = 11.sp,
                                    color = Color(0xFF1C1C1E),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !isEncoding) {
                                    if (!isEncoding) {
                                        settings = settings.copy(enableRoadDetection = !settings.enableRoadDetection)
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = settings.enableRoadDetection,
                                    onCheckedChange = { checked ->
                                        if (!isEncoding) {
                                            settings = settings.copy(enableRoadDetection = checked)
                                        }
                                    },
                                    enabled = !isEncoding,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF007AFF))
                                )
                                Text(
                                    text = utils.Localizer.get("enable_road_detection", settings.language),
                                    fontSize = 11.sp,
                                    color = Color(0xFF1C1C1E),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !isEncoding) {
                                    if (!isEncoding) {
                                        viewModel.onBlurLicensePlatesChanged(!settings.blurLicensePlates, scope)
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = settings.blurLicensePlates,
                                    onCheckedChange = { checked ->
                                        if (!isEncoding) {
                                            viewModel.onBlurLicensePlatesChanged(checked, scope)
                                        }
                                    },
                                    enabled = !isEncoding,
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF007AFF))
                                )
                                Text(
                                    text = utils.Localizer.get("blur_license_plates", settings.language),
                                    fontSize = 11.sp,
                                    color = Color(0xFF1C1C1E),
                                    fontWeight = FontWeight.Medium
                                )
                                if (viewModel.isDetectingPlates) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = Color(0xFF007AFF)
                                    )
                                    Text(
                                        text = viewModel.plateDetectionProgress,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                } else if (viewModel.plateCache != null) {
                                    val count = viewModel.plateCache?.records?.size ?: 0
                                    val doneText = if (settings.language == "ja") {
                                        "${count}フレーム検出 (再実行)"
                                    } else {
                                        "${count} frames detected (Re-scan)"
                                    }
                                    Text(
                                        text = "($doneText)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF34C759),
                                        modifier = Modifier.clickable { viewModel.runPlateDetection(scope) }
                                    )
                                }
                            }
                            viewModel.plateDetectionError?.let { err ->
                                Text(
                                    text = err,
                                    fontSize = 9.sp,
                                    color = Color(0xFFFF3B30),
                                    modifier = Modifier.padding(start = 24.dp, top = 2.dp, bottom = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(utils.Localizer.get("power_trend_span", settings.language).uppercase(), color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val spans = listOf(
                                    30 to "30s",
                                    60 to "1m",
                                    180 to "3m",
                                    300 to "5m",
                                    600 to "10m",
                                    1200 to "20m"
                                )
                                spans.forEach { (sec, label) ->
                                    val isSelected = settings.powerTrendSpanSeconds == sec
                                    OutlinedButton(
                                        onClick = { settings = settings.copy(powerTrendSpanSeconds = sec) },
                                        enabled = !isEncoding,
                                        modifier = Modifier.weight(1f).height(28.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            backgroundColor = if (isSelected) Color(0xFF007AFF) else Color.White,
                                            contentColor = if (isSelected) Color.White else Color(0xFF1C1C1E)
                                        ),
                                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF007AFF) else Color(0xFFE5E5EA)),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(label, fontSize = 9.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(utils.Localizer.get("road_caption_position", settings.language).uppercase(), color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val positions = listOf(
                                    "top_right" to "右上",
                                    "top_center" to "中央上",
                                    "top_left" to "左上",
                                    "bottom_center" to "下部中央"
                                )
                                positions.forEach { (posKey, label) ->
                                    val isSelected = settings.captionPosition == posKey
                                    Button(
                                        onClick = { settings = settings.copy(captionPosition = posKey) },
                                        enabled = !isEncoding,
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = if (isSelected) Color(0xFF007AFF) else Color(0xFFE5E5EA),
                                            contentColor = if (isSelected) Color.White else Color(0xFF1C1C1E),
                                            disabledBackgroundColor = if (isSelected) Color(0xFF007AFF).copy(alpha = 0.5f) else Color(0xFFF2F2F7),
                                            disabledContentColor = Color(0xFF8E8E93)
                                        ),
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    }
                    if (showSidebar) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(sidebarScrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isPreviewFullscreen) Color.Black else Color.White)
                        .padding(if (isPreviewFullscreen) 0.dp else 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (isPreviewFullscreen) 0.dp else 8.dp)
                    ) {
                        val isActiveEncoding = isEncoding || viewModel.isBatchRunning
                        if (!isActiveEncoding) {
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
                                modifier = if (isPreviewFullscreen) Modifier.fillMaxSize() else Modifier.weight(1f).fillMaxWidth(),
                                isEncoding = false,
                                previewQualityMode = previewQualityMode,
                                onPreviewQualityModeChange = { previewQualityMode = it },
                                isFullscreen = isPreviewFullscreen,
                                onFullscreenToggle = { viewModel.isPreviewFullscreen = !viewModel.isPreviewFullscreen },
                                plateCache = viewModel.plateCache,
                                videoRotation = viewModel.videoRotation
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
                                progress = if (viewModel.isBatchRunning) viewModel.progress else progress,
                                statusText = if (viewModel.isBatchRunning) viewModel.statusText else statusText,
                                encodingPreviewImage = encodingPreviewImage,
                                showLivePreview = showLivePreview,
                                isPaused = if (viewModel.isBatchRunning) viewModel.isPaused else isPaused,
                                videoLengthMs = trimmedDurationMs,
                                onPauseToggle = {
                                    if (viewModel.isBatchRunning) {
                                        viewModel.isPaused = !viewModel.isPaused
                                    } else {
                                        isPaused = !isPaused
                                    }
                                },
                                onCancel = {
                                    if (viewModel.isBatchRunning) {
                                        viewModel.isCanceled = true
                                    } else {
                                        isCanceled = true
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            val totalParts = viewModel.getSplitRanges().size
                            val outputFileName = buildEncodeOutputFileName(
                                settings = settings,
                                videoPath = videoPath,
                                partIndex = if (totalParts > 1) 0 else -1,
                                numParts = totalParts,
                                isSample = isSampleEncoding
                            ).replace("_part1_", "_part*_")
                            val output = File(outputDir, outputFileName).absolutePath
                            val destInfo = if (moveOutputToSource) {
                                val sourceDir = File(videoPath).parentFile
                                val finalDest = if (sourceDir != null) File(sourceDir, outputFileName).absolutePath else output
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
                        if (showSidebar) {
                            Spacer(Modifier.height(8.dp))
                            TelemetryTimelineGraph(
                                videoLengthMs = videoLengthMs,
                                adjustedStartUtc = adjustedStartUtc,
                                telemetryPoints = telemetryPoints,
                                trimStartSeconds = trimStartSeconds,
                                trimEndSeconds = trimEndSeconds,
                                splitPoints = viewModel.splitPoints,
                                videoCurrentTimeMs = effectiveVideoTimeMs,
                                onTrimStartChange = { if (!isEncoding) trimStartSeconds = it },
                                onTrimEndChange = { if (!isEncoding) trimEndSeconds = it },
                                onSeek = { timeMs ->
                                    if (!isEncoding) {
                                        val ratio = if (videoLengthMs > 0) timeMs.toFloat() / videoLengthMs.toFloat() else 0f
                                        playerState.seekTo(ratio * 1000f)
                                        videoCurrentTimeMs = timeMs
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                isEncoding = isEncoding
                            )
                            val previewLabel = when (settings.exportResolution) {
                                "360p" -> "360p (640x360) Overlay Preview"
                                "720p" -> "720p (1280x720) Overlay Preview"
                                "1080p" -> "1080p (1920x1080) Overlay Preview"
                                "2.7k" -> "2.7k (2704x1520) Overlay Preview"
                                else -> "${settings.exportResolution} Overlay Preview"
                            }
                            Text(previewLabel, color = Color(0xFF1C1C1E), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            val editingIndex = editingCaptionIndex
            if (editingIndex != null && editingIndex in settings.roadCaptions.indices) {
                val segment = settings.roadCaptions[editingIndex]
                RoadCaptionEditDialog(
                    segment = segment,
                    index = editingIndex,
                    videoCurrentTimeMs = videoCurrentTimeMs,
                    videoLengthMs = videoLengthMs,
                    viewModel = viewModel,
                    onClose = { editingCaptionIndex = null },
                    onSeek = { targetMs ->
                        val ratio = if (videoLengthMs > 0) targetMs.toFloat() / videoLengthMs.toFloat() else 0f
                        playerState.seekTo(ratio * 1000f)
                        videoCurrentTimeMs = targetMs
                    }
                )
            }
            
            if (setupStep > 0) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (setupStep == 1) {
                        // Step 1: Language Selection
                        Card(
                            modifier = Modifier.width(420.dp).padding(16.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            elevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "FIT Telemetry Trimmer",
                                    style = MaterialTheme.typography.h6,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF007AFF)
                                )
                                Text(
                                    text = "Please select your language.",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                
                                Box(modifier = Modifier.height(200.dp).border(1.dp, Color(0xFFE5E5EA), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(4.dp)) {
                                    val scrollState = rememberScrollState()
                                    Column(
                                        modifier = Modifier.verticalScroll(scrollState).padding(end = 12.dp)
                                    ) {
                                        utils.Localizer.supportedLanguages.forEach { lang ->
                                            val isSelected = tempSelectedLanguage == lang.code
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { tempSelectedLanguage = lang.code }
                                                    .background(if (isSelected) Color(0xFFE5F1FF) else Color.Transparent)
                                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(lang.localName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = Color(0xFF1C1C1E))
                                                Text(lang.englishName, fontSize = 11.sp, color = Color.Gray)
                                            }
                                        }
                                    }
                                    VerticalScrollbar(
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                        adapter = rememberScrollbarAdapter(scrollState)
                                    )
                                }
                                
                                Button(
                                    onClick = {
                                        setupStep = 2 // Move to privacy consent
                                    },
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF), contentColor = Color.White)
                                ) {
                                    Text(utils.Localizer.get("confirm", tempSelectedLanguage))
                                }
                            }
                        }
                    } else if (setupStep == 2) {
                        // Step 2: Privacy Policy Consent
                        Card(
                            modifier = Modifier.width(480.dp).padding(16.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            elevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = utils.Localizer.get("privacy_title", tempSelectedLanguage),
                                    style = MaterialTheme.typography.h6,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF007AFF),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Text(
                                    text = utils.Localizer.get("privacy_message", tempSelectedLanguage),
                                    fontSize = 12.sp,
                                    color = Color(0xFF1C1C1E)
                                )
                                Divider(color = Color(0xFFE5E5EA))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = utils.Localizer.get("privacy_item_osm", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = utils.Localizer.get("privacy_item_gsi", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = utils.Localizer.get("privacy_item_github", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Divider(color = Color(0xFFE5E5EA))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            setupStep = 1 // Go back
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                                    ) {
                                        Text("Back", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            setupStep = 3 // Move to storage policy consent
                                        },
                                        modifier = Modifier.weight(2f).height(40.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF), contentColor = Color.White)
                                    ) {
                                        Text(
                                            text = utils.Localizer.get("confirm", tempSelectedLanguage),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    } else if (setupStep == 3) {
                        // Step 3: Local Storage & Data Processing Policy Consent
                        Card(
                            modifier = Modifier.width(480.dp).padding(16.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            elevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = utils.Localizer.get("storage_policy_title", tempSelectedLanguage),
                                    style = MaterialTheme.typography.h6,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF007AFF),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Text(
                                    text = utils.Localizer.get("storage_policy_message", tempSelectedLanguage),
                                    fontSize = 12.sp,
                                    color = Color(0xFF1C1C1E)
                                )
                                Divider(color = Color(0xFFE5E5EA))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = utils.Localizer.get("storage_policy_item_trim", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = utils.Localizer.get("storage_policy_item_cache", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = utils.Localizer.get("storage_policy_item_offline", tempSelectedLanguage),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Divider(color = Color(0xFFE5E5EA))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            setupStep = 2 // Go back
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                                    ) {
                                        Text("Back", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            settings = settings.copy(language = tempSelectedLanguage)
                                            setupStep = 0 // Finished setup
                                        },
                                        modifier = Modifier.weight(2f).height(40.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF), contentColor = Color.White)
                                    ) {
                                        Text(
                                            text = utils.Localizer.get("privacy_agree", tempSelectedLanguage),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}


