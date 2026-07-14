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

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlinx.coroutines.isActive

import kotlinx.coroutines.Job

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.MainScope

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

import androidx.compose.foundation.gestures.detectVerticalDragGestures

import androidx.compose.ui.input.pointer.PointerEventType

import androidx.compose.ui.input.pointer.onPointerEvent

import androidx.compose.ui.ExperimentalComposeUiApi

// VLC dependency removed

import utils.*

import components.*

import viewmodel.*

import fit.APP_VERSION

private const val PLAYBACK_PREVIEW_INTERVAL_MS = 250L

const val MAX_ROAD_SNAP_DISTANCE_METERS = 15.0

@OptIn(ExperimentalTextApi::class)

fun main(args: Array<String>) {
    utils.AppMetadata.setAppUserModelId()
    utils.TelemetryAligner.loadConfig(File("imu_align_config.json"))

    // Eagerly load and initialize DesktopLog on application startup to prevent class loader issues on native/AWT threads

    DesktopLog.info("FitTrimmer starting... Version: $APP_VERSION")



    if (args.contains("--simulate-crash")) {
        utils.CrashSimulator.setupSimulation()
    }
    if (args.contains("--test-e2e") || args.contains("--auto-sample") || args.contains("--no-cache")) {

        utils.GuiCache.useTestCache = true

    }

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->

        try {

            DesktopLog.exception("Uncaught exception on ${thread.name}", throwable)

        } catch (t: Throwable) {

            System.err.println("Fatal: uncaught exception handler failed to write log:")

            t.printStackTrace()

        }

    }

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



    if (args.contains("--verify-imu-gt")) {
        val idx = args.indexOf("--verify-imu-gt")
        val baseDirStr = if (idx != -1 && idx + 1 < args.size) args[idx + 1] else null
        if (baseDirStr == null) {
            println("ERROR: Missing base directory path for --verify-imu-gt")
            System.exit(1)
            return
        }
        runImuGtVerification(baseDirStr)
        return
    }

    if (args.contains("--test-e2e")) {

        runE2ETest(args)

        return

    }

    if (args.contains("--test-gui")) {

        startGui(args)

        return

    }

    if (args.contains("--simulate-crash")) {
        startGui(args)
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

@Serializable
private data class GtDatasetRecord(
    val fit_relative_path: String,
    val video_relative_path: String,
    val trimmed_fit_relative_path: String? = null,
    val approx_start_utc: String,
    val expected_offset_seconds: Double? = null,
    val description: String = "",
    val tolerance_seconds: Double = 3.0,
    val window_seconds: Double = 60.0
)

private fun runImuGtVerification(baseDirStr: String) {
    println("=== STARTING IMU GROUND TRUTH VERIFICATION ===")
    println("Base Directory: $baseDirStr")

    val stream = AppViewModel::class.java.getResourceAsStream("/imu_gt_dataset.json")
    if (stream == null) {
        println("ERROR: imu_gt_dataset.json not found in resources.")
        System.exit(1)
        return
    }

    val jsonText = stream.bufferedReader().use { it.readText() }
    val records = try {
        Json.decodeFromString<List<GtDatasetRecord>>(jsonText)
    } catch (e: Exception) {
        println("ERROR: Failed to parse imu_gt_dataset.json: ${e.message}")
        System.exit(1)
        return
    }

    val baseDir = File(baseDirStr)
    var successCount = 0
    var failCount = 0
    var skipCount = 0

    utils.TelemetryAligner.loadConfig(File("imu_align_config.json"))
    val activeConfig = utils.TelemetryAligner.config
    println("🔍 Active Alignment Configuration:")
    println("  - speed_threshold: ${activeConfig.speed_threshold}")
    println("  - vib_threshold_factor: ${activeConfig.vib_threshold_factor}")
    println("  - min_vib_threshold: ${activeConfig.min_vib_threshold}")
    println("  - power_min_threshold: ${activeConfig.power_min_threshold}")
    println("  - power_max_threshold: ${activeConfig.power_max_threshold}")
    println("  - power_threshold_ratio: ${activeConfig.power_threshold_ratio}")
    println("  - power_active_weight: ${activeConfig.power_active_weight}")
    println("  - power_edge_weight: ${activeConfig.power_edge_weight}")
    println("  - gaussian_sigma_speed: ${activeConfig.gaussian_sigma_speed}")
    println("  - gaussian_sigma_power: ${activeConfig.gaussian_sigma_power}")
    println("  - gaussian_sigma_vib: ${activeConfig.gaussian_sigma_vib}")
    println("  - window_seconds: ${activeConfig.window_seconds}")

    kotlinx.coroutines.runBlocking {
        for (record in records) {
            val fitFile = File(baseDir, record.fit_relative_path)
            val videoFile = File(baseDir, record.video_relative_path)

            println("\n--------------------------------------------------")
            println("Case: ${record.description}")
            println("FIT:   ${fitFile.absolutePath}")
            println("VIDEO: ${videoFile.absolutePath}")

            if (!fitFile.exists() || !videoFile.exists()) {
                println("⚠️ SKIP: Source files not found on storage. Skipped.")
                skipCount++
                continue
            }

            try {
                // Determine Ground Truth Offset
                var expectedOffsetSec = record.expected_offset_seconds
                if (expectedOffsetSec == null) {
                    val trimmedFitFile = if (record.trimmed_fit_relative_path != null) {
                        File(baseDir, record.trimmed_fit_relative_path)
                    } else {
                        val baseName = videoFile.name.replace(Regex("""\.(mp4|mov)$""", RegexOption.IGNORE_CASE), "")
                        val candidates = listOf(
                            File(videoFile.parentFile, "${baseName}_KMP_HUD_2.7k.fit"),
                            File(videoFile.parentFile, "${baseName}_KMP_HUD_orig.fit"),
                            File(videoFile.parentFile, "${baseName}_KMP_HUD_1080p.fit"),
                            File(System.getProperty("java.io.tmpdir"), "${baseName}_KMP_HUD_2.7k.fit"),
                            File(System.getProperty("java.io.tmpdir"), "${baseName}_KMP_HUD_orig.fit"),
                            File(System.getProperty("java.io.tmpdir"), "${baseName}_KMP_HUD_1080p.fit")
                        )
                        candidates.firstOrNull { it.exists() }
                    }

                    if (trimmedFitFile == null || !trimmedFitFile.exists()) {
                        println("❌ FAIL: expected_offset_seconds is null, and no trimmed FIT file found to reverse-engineer.")
                        failCount++
                        continue
                    }

                    println("Reverse-engineering expected offset from trimmed FIT: ${trimmedFitFile.absolutePath}")

                    var creationTimeSec: Long? = null
                    val mp4Parser = mp4.Mp4Parser()
                    val scanSize = minOf(videoFile.length(), 100L * 1024 * 1024).toInt()
                    val headBytes = videoFile.inputStream().use { it.readNBytes(scanSize) }
                    val meta = mp4Parser.parse(headBytes)
                    if (meta != null) {
                        creationTimeSec = meta.creationTimeSeconds - 2082844800L
                    } else {
                        try {
                            val ffprobePath = try { fit.findFfprobePath() } catch (e: Exception) { "ffprobe" }
                            val pb = ProcessBuilder(
                                ffprobePath, "-v", "error",
                                "-select_streams", "v:0",
                                "-show_entries", "format_tags=creation_time",
                                "-of", "default=noprint_wrappers=1:nokey=1",
                                videoFile.absolutePath
                            )
                            val p = pb.start()
                            val output = p.inputStream.bufferedReader().readText().trim()
                            p.waitFor()
                            if (output.isNotEmpty()) {
                                var normalizedOutput = output.replace(" ", "T")
                                if (!normalizedOutput.endsWith("Z") && !normalizedOutput.contains("+")) {
                                    normalizedOutput += "Z"
                                }
                                val instant = java.time.Instant.parse(normalizedOutput)
                                creationTimeSec = instant.epochSecond
                                println("  [ffprobe creation_time fallback]: $normalizedOutput ($creationTimeSec)")
                            }
                        } catch (e: Exception) {
                            println("  ⚠️ ffprobe fallback failed: ${e.message}")
                        }
                    }

                    if (creationTimeSec == null) {
                        println("❌ FAIL: Failed to parse video metadata for creation time.")
                        failCount++
                        continue
                    }

                    val trimmedBytes = trimmedFitFile.readBytes()
                    val trimmedParser = fit.FitParser(trimmedBytes)
                    trimmedParser.parse()
                    val embeddedCorrection = trimmedParser.getClockCorrection()

                    if (embeddedCorrection != null) {
                        val videoSuffix = if (embeddedCorrection.videoName != null) " (video: ${embeddedCorrection.videoName})" else ""
                        println("  [Embedded Ground Truth detected in FIT]: userOffset=${embeddedCorrection.userOffset}s, imuOffset=${embeddedCorrection.imuOffset}s$videoSuffix")
                        expectedOffsetSec = embeddedCorrection.userOffset.toDouble()
                    } else {
                        val trimmedFirstTs = getTrimmedFitFirstTs(trimmedFitFile)
                        if (trimmedFirstTs == null) {
                            println("❌ FAIL: Failed to read first record timestamp from trimmed FIT.")
                            failCount++
                            continue
                        }

                        val origFirstTs = getTrimmedFitFirstTs(fitFile)
                        if (origFirstTs != null && trimmedFirstTs <= origFirstTs) {
                            println("❌ FAIL: Trimmed FIT starts at the very beginning of the original FIT (clipped). Cannot reverse-engineer clock offset safely. Please specify expected_offset_seconds in JSON.")
                            failCount++
                            continue
                        }

                        val trimStartSec = maxOf(parseTrimStartSeconds(trimmedFitFile.name), parseTrimStartSeconds(videoFile.name))
                        val reverseEngineeredOffset = trimmedFirstTs.toDouble() - creationTimeSec.toDouble() - trimStartSec
                        println("  Calculated Ground Truth offset: ${reverseEngineeredOffset}s (creationTime=$creationTimeSec, trimmedFirstTs=$trimmedFirstTs, trimStartSeconds=$trimStartSec)")
                        expectedOffsetSec = reverseEngineeredOffset
                    }
                }

                val fitBytes = fitFile.readBytes()
                val parser = fit.FitParser(fitBytes)
                parser.parse()
                val telemetryPoints = parser.getTelemetry()

                println("Running auto alignment (binary method, window = ${record.window_seconds}s)...")
                val alignedUtc = TelemetryAligner.alignVideoWithTelemetry(
                    videoPath = videoFile.absolutePath,
                    telemetryPoints = telemetryPoints,
                    approxStartUtc = record.approx_start_utc,
                    method = "binary",
                    windowSeconds = record.window_seconds
                )

                if (alignedUtc == null) {
                    println("❌ FAIL: Auto alignment returned null.")
                    failCount++
                    continue
                }

                val alignedInstant = java.time.Instant.parse(alignedUtc)
                val approxInstant = java.time.Instant.parse(record.approx_start_utc)

                val detectedOffsetSec = (alignedInstant.toEpochMilli() - approxInstant.toEpochMilli()) / 1000.0
                val diffSec = Math.abs(detectedOffsetSec - expectedOffsetSec)

                println("Expected Offset: ${expectedOffsetSec}s")
                println("Detected Offset: ${detectedOffsetSec}s (Aligned: $alignedUtc)")
                val diffSecStr = String.format(java.util.Locale.US, "%.3f", diffSec)
                println("Absolute Diff:   ${diffSecStr}s (Tolerance: ${record.tolerance_seconds}s)")

                if (diffSec <= record.tolerance_seconds) {
                    println("✅ PASS: Synced clock offset matches Ground Truth within tolerance.")
                    successCount++
                } else {
                    println("❌ FAIL: Clock offset drift (${diffSec}s) exceeded tolerance.")
                    failCount++
                }
            } catch (e: Exception) {
                println("❌ FAIL: Exception occurred: ${e.message}")
                e.printStackTrace()
                failCount++
            }
        }
    }

    println("\n==================================================")
    println("Verification Summary:")
    println("  PASSED:  $successCount")
    println("  FAILED:  $failCount")
    println("  SKIPPED: $skipCount")
    println("==================================================")

    if (failCount > 0) {
        System.exit(1)
    } else {
        System.exit(0)
    }
}

private fun getTrimmedFitFirstTs(trimmedFitFile: File): Long? {
    if (!trimmedFitFile.exists()) return null
    val fitBytes = trimmedFitFile.readBytes()
    val parser = fit.FitParser(fitBytes)
    parser.parse()
    for (r in parser.records) {
        if (r is fit.FitParser.FitRecord.Data && r.globalMessageNumber == 20) {
            val ts = r.data.fields[253]?.value
            if (ts != null) {
                return ts.toLong() + 631065600L
            }
        }
    }
    return null
}

private fun parseTrimStartSeconds(fileName: String): Double {
    val regex = Regex("""_(\d+)m(\d+)s-""")
    val match = regex.find(fileName)
    if (match != null) {
        val m = match.groupValues[1].toInt()
        val s = match.groupValues[2].toInt()
        return m * 60.0 + s.toDouble()
    }
    return 0.0
}

fun showSystemNotification(title: String, message: String) {

    if (!java.awt.SystemTray.isSupported()) return

    java.awt.EventQueue.invokeLater {

        var trayIcon: java.awt.TrayIcon? = null

        try {

            val tray = java.awt.SystemTray.getSystemTray()

            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)

            trayIcon = java.awt.TrayIcon(image, "HUD エンコーダー")

            trayIcon.isImageAutoSize = true

            tray.add(trayIcon)

            trayIcon.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO)



            // Use simple lambda thread to clean up tray icon after 10s to avoid anonymous inner class file deletion issue

            val cleaner = java.lang.Thread {

                try {

                    java.lang.Thread.sleep(10000)

                    java.awt.EventQueue.invokeLater {

                        try {

                            trayIcon?.let { tray.remove(it) }

                        } catch (t: Throwable) {

                            println("WARNING: Failed to remove tray notification icon: ${t.message}")

                        }

                    }

                } catch (e: InterruptedException) {

                    // Ignore

                }

            }

            cleaner.isDaemon = true

            cleaner.start()

        } catch (t: Throwable) {

            trayIcon?.let {

                try {

                    java.awt.SystemTray.getSystemTray().remove(it)

                } catch (_: Throwable) {

                }

            }

            println("WARNING: Failed to show system notification: ${t.message}")

        }

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

            width = (initialCache?.windowWidth ?: 1300f).coerceIn(1000f, 1600f).dp,

            height = (initialCache?.windowHeight ?: 780f).coerceIn(750f, 950f).dp

        )

    )

    val viewModel = remember { AppViewModel(initialCache) }

    var composeWindow: java.awt.Window? by remember { mutableStateOf(null) }

    Window(

        onCloseRequest = {

            System.exit(0)

        },

        title = "HUD エンコーダー",

        state = windowState,

        icon = androidx.compose.ui.res.painterResource("icon.png")

    ) {

        LaunchedEffect(window) {

            viewModel.composeWindow = window

            composeWindow = window

            kotlinx.coroutines.delay(800)

            try {

                window.toFront()

                window.requestFocus()

            } catch (e: Exception) {

                e.printStackTrace()

            }

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

        LaunchedEffect(viewModel.isEncoding, viewModel.progress, viewModel.statusText) {

            if (taskbar != null && isProgressSupported) {

                try {

                    val targetState = when {

                        !viewModel.isEncoding -> java.awt.Taskbar.State.OFF

                        viewModel.statusText.contains("Merging", ignoreCase = true) -> java.awt.Taskbar.State.INDETERMINATE

                        else -> java.awt.Taskbar.State.NORMAL

                    }

                    val targetPercent = if (viewModel.isEncoding) (viewModel.progress * 100).toInt().coerceIn(0, 100) else 0

                    if (targetState != lastState) {

                        taskbar.setWindowProgressState(window, targetState)

                        lastState = targetState

                    }

                    if (targetState == java.awt.Taskbar.State.NORMAL) {

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

        if (args.contains("--test-gui")) {

            LaunchedEffect(Unit) {

                DesktopLog.info("🧪 GUI Smoke Test: Verifying UI initialization and rendering stability for 5 seconds...")

                try {

                    kotlinx.coroutines.delay(5000)

                    DesktopLog.info("🧪 GUI Smoke Test: Startup stability verified. Exiting application.")

                    System.exit(0)

                } catch (e: Exception) {

                    DesktopLog.exception("❌ GUI Smoke Test: Exception during delay/exit", e)

                    throw e

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

fun buildPlateCutSpans(
    plateCache: fit.VideoPlatesCache?,
    trimStartSeconds: Double,
    trimEndSeconds: Double,
    bufferMs: Long
): List<fit.CutSpan> {
    val cache = plateCache ?: return emptyList()
    val bufferSeconds = bufferMs.coerceAtLeast(0L).toDouble() / 1000.0
    val rawSpans = cache.records
        .filter { it.boxes.isNotEmpty() }
        .mapNotNull { record ->
            val center = record.timeMs.toDouble() / 1000.0
            val start = (center - bufferSeconds).coerceAtLeast(trimStartSeconds)
            val end = (center + bufferSeconds).coerceAtMost(trimEndSeconds)
            if (end > start) fit.CutSpan(start, end) else null
        }
        .sortedBy { it.startSec }

    return rawSpans.fold(mutableListOf()) { acc, span ->
        val last = acc.lastOrNull()
        if (last != null && span.startSec <= last.endSec) {
            acc[acc.lastIndex] = fit.CutSpan(last.startSec, maxOf(last.endSec, span.endSec))
        } else {
            acc.add(span)
        }
        acc
    }
}

fun subtractCutSpansFromRanges(
    ranges: List<Pair<Double, Double>>,
    cutSpans: List<fit.CutSpan>,
    minKeepSeconds: Double = 0.05
): List<Pair<Double, Double>> {
    if (cutSpans.isEmpty()) return ranges
    val sortedCuts = cutSpans.sortedBy { it.startSec }
    val keepRanges = mutableListOf<Pair<Double, Double>>()

    for ((rangeStart, rangeEnd) in ranges) {
        var cursor = rangeStart
        for (cut in sortedCuts) {
            if (cut.endSec <= cursor) continue
            if (cut.startSec >= rangeEnd) break
            val keepEnd = cut.startSec.coerceAtMost(rangeEnd)
            if (keepEnd - cursor >= minKeepSeconds) {
                keepRanges.add(cursor to keepEnd)
            }
            cursor = maxOf(cursor, cut.endSec.coerceAtMost(rangeEnd))
        }
        if (rangeEnd - cursor >= minKeepSeconds) {
            keepRanges.add(cursor to rangeEnd)
        }
    }

    return keepRanges
}

fun buildEncodeRangesWithPlatePolicy(
    trimStartSeconds: Double,
    trimEndSeconds: Double,
    splitPoints: List<Double>,
    settings: HudSettings,
    plateCache: fit.VideoPlatesCache?
): List<Pair<Double, Double>> {
    val actualEnd = if (settings.exportResolution == "strava") {
        minOf(trimEndSeconds, trimStartSeconds + 30.0)
    } else {
        trimEndSeconds
    }
    val baseRanges = buildEncodeRanges(trimStartSeconds, actualEnd, splitPoints)
    if (settings.plateMaskMode != "cut") return baseRanges
    val cutSpans = buildPlateCutSpans(
        plateCache = plateCache,
        trimStartSeconds = trimStartSeconds,
        trimEndSeconds = trimEndSeconds,
        bufferMs = settings.plateMaskTimeBufferMs
    )
    val subtracted = subtractCutSpansFromRanges(baseRanges, cutSpans)
    val totalRemaining = subtracted.sumOf { it.second - it.first }
    if (totalRemaining < settings.minRemainingSecondsForCut) {
        return emptyList()
    }
    return subtracted.ifEmpty { baseRanges }
}



fun buildEncodeOutputFileName(

    settings: HudSettings,

    videoPath: String,

    partIndex: Int = -1,

    numParts: Int = 1,

    trimStartSeconds: Double? = null,

    trimEndSeconds: Double? = null,

    dateTag: String? = null

): String {

    return fit.HudFileNameFormatter.buildEncodeOutputFileName(

        settings = settings,

        videoPath = videoPath,

        partIndex = partIndex,

        numParts = numParts,

        trimStartSeconds = trimStartSeconds,

        trimEndSeconds = trimEndSeconds,

        dateTag = dateTag

    )

}



fun hasTrimmedRange(trimStartSeconds: Double, trimEndSeconds: Double, videoDurationSeconds: Double?): Boolean {

    val epsilon = 0.01

    if (trimStartSeconds > epsilon) return true

    if (videoDurationSeconds == null || videoDurationSeconds <= 0.0) return trimEndSeconds > epsilon

    return trimEndSeconds > epsilon && trimEndSeconds < videoDurationSeconds - epsilon

}



fun buildEncodePlan(

    settings: HudSettings,

    videoPath: String,

    outputDir: String,

    moveOutputToSource: Boolean,

    ranges: List<Pair<Double, Double>>,

    includeTrimRangeInFileName: Boolean = false,

    dateTag: String? = null,

    outputFileNames: List<String>? = null

): EncodePlan {

    val videoFile = File(videoPath)

    val segments = ranges.mapIndexed { idx, (start, end) ->

        val outputFileName = outputFileNames?.getOrNull(idx) ?: buildEncodeOutputFileName(

            settings = settings,

            videoPath = videoPath,

            partIndex = idx,

            numParts = ranges.size,

            trimStartSeconds = if (includeTrimRangeInFileName) start else null,

            trimEndSeconds = if (includeTrimRangeInFileName) end else null,

            dateTag = if (includeTrimRangeInFileName) dateTag else null

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



fun buildDateTagFromUtc(utc: String): String? {

    val date = utc.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null

    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return null

    return date.replace("-", "")

}



suspend fun prepareRoadCaptionSettingsForEncode(

    baseSettings: HudSettings,

    autoDetectRoadCaptionsOnEncode: Boolean,

    context: RoadCaptionDetectionContext,

    cancelCheck: () -> Boolean = { false },

    onStatus: (String) -> Unit,

    onSettingsPrepared: (HudSettings) -> Unit = {}

): HudSettings {

    if (!autoDetectRoadCaptionsOnEncode) return baseSettings

    val clearedSettings = baseSettings.copy(roadCaptions = emptyList())

    onSettingsPrepared(clearedSettings)

    onStatus("路線名テロップをクリアして再検出中...")

    if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

    val detected = detectRoadSegments(

        points = context.points,

        videoStartUtc = context.videoStartUtc,

        timeOffsetMillis = context.timeOffsetMillis,

        videoDurationSeconds = context.videoDurationSeconds,

        language = baseSettings.language,

        enableRoadDetection = baseSettings.enableRoadDetection,

        cancelCheck = cancelCheck,

        onProgress = onStatus

    )

    val updatedSettings = clearedSettings.copy(roadCaptions = detected)

    onSettingsPrepared(updatedSettings)

    onStatus("路線名テロップを ${detected.size} 件アサインしました")

    return updatedSettings

}



suspend fun loadTelemetryPointsForRoadDetection(

    fitPath: String,

    cancelCheck: () -> Boolean = { false }

): List<TelemetryPoint> = withContext(Dispatchers.IO) {

    if (fitPath.isEmpty()) return@withContext emptyList()

    try {

        val parser = FitParser(File(fitPath).readBytes())

        if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

        parser.parse(cancelCheck)

        if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

        parser.getTelemetry(cancelCheck)

    } catch (e: Exception) {

        if (cancelCheck()) throw e

        e.printStackTrace()

        emptyList()

    }

}



object BatchJobRunner {

    private var lastProgressUpdateTime = 0L

    private var lastPreviewUpdateTime = 0L



    fun updateOverallJobProgress(job: BatchJob, activePhases: List<BatchJobPhase>) {

        if (activePhases.isEmpty()) return

        val totalProgress = activePhases.sumOf { it.progress.toDouble() }

        job.progress = (totalProgress / activePhases.size).toFloat()

    }



    suspend fun runBatchJobs(

        viewModel: AppViewModel,

        outputDir: String,

        moveOutputToSource: Boolean,

        onProgressUpdate: () -> Unit

    ) {

        if (viewModel.isBatchRunning) return

        val mainScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)

        viewModel.isBatchRunning = true

        viewModel.batchStatusText = "バッチ処理を開始します..."

        viewModel.isCanceled = false

        lastProgressUpdateTime = 0L

        lastPreviewUpdateTime = 0L

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

                viewModel.saveBatchQueue()

                onProgressUpdate()

                

                var jobFailed = false

                var jobErrorMessage: String? = null

                

                // Reset phases to WAITING if they were failed or running

                job.phases.forEach {

                    if (it.status == BatchJobPhaseStatus.FAILED || it.status == BatchJobPhaseStatus.RUNNING) {

                        it.status = BatchJobPhaseStatus.WAITING

                        it.progress = 0f

                    }

                }

                

                val activePhases = job.phases.filter { it.enabled }

                for ((phaseIdx, phase) in activePhases.withIndex()) {

                    if (viewModel.isCanceled) break

                    

                    // If phase is already completed, skip it

                    if (phase.status == BatchJobPhaseStatus.COMPLETED) {

                        continue

                    }

                    

                    phase.status = BatchJobPhaseStatus.RUNNING

                    phase.progress = 0f

                    viewModel.saveBatchQueue()

                    onProgressUpdate()

                    

                    try {

                        executePhase(

                            viewModel, job, phase, activePhases, 

                            jobIdx, jobs.size, outputDir, moveOutputToSource, 

                            mainScope, onProgressUpdate

                        )

                    } catch (e: Exception) {

                        phase.status = BatchJobPhaseStatus.FAILED

                        jobFailed = true

                        jobErrorMessage = e.message ?: "フェーズの実行に失敗しました"

                        break

                    }

                }

                

                if (jobFailed) {

                    if (viewModel.isCanceled) {

                        job.status = BatchJobStatus.WAITING

                        job.progress = 0f

                    } else {

                        job.status = BatchJobStatus.FAILED

                        job.errorMessage = jobErrorMessage

                    }

                } else {

                    job.status = BatchJobStatus.COMPLETED

                    job.progress = 1.0f

                }

                viewModel.saveBatchQueue()

                onProgressUpdate()

            }

            viewModel.batchStatusText = if (viewModel.isCanceled) "バッチ処理をキャンセルしました" else "✨ すべてのバッチ処理が完了しました！"

            if (!viewModel.isCanceled) {

                showSystemNotification("HUD エンコーダー", "すべてのバッチエンコードが完了しました！")

                // Clear successfully completed jobs from queue to keep it temporary

                viewModel.batchQueue.removeAll { it.status == BatchJobStatus.COMPLETED }

                viewModel.saveBatchQueue()

            }

        } finally {

            viewModel.isBatchRunning = false

            viewModel.isCanceled = false

            viewModel.encodingSegmentStart = null

            viewModel.encodingSegmentEnd = null

        }

    }



    private suspend fun executePhase(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        activePhases: List<BatchJobPhase>,

        jobIdx: Int,

        totalJobs: Int,

        outputDir: String,

        moveOutputToSource: Boolean,

        mainScope: CoroutineScope,

        onProgressUpdate: () -> Unit

    ) {

        when (phase.type) {

            BatchJobPhaseType.PLATE_SCAN -> executePlateScan(viewModel, job, phase, activePhases, jobIdx, totalJobs, mainScope, onProgressUpdate)

            BatchJobPhaseType.ROAD_SCAN -> executeRoadScan(viewModel, job, phase, jobIdx, totalJobs, onProgressUpdate)

            BatchJobPhaseType.HUD_ENCODE -> executeHudEncode(viewModel, job, phase, activePhases, jobIdx, totalJobs, outputDir, moveOutputToSource, mainScope, onProgressUpdate)

            BatchJobPhaseType.CONCAT_MERGE -> executeConcatMerge(viewModel, job, phase, activePhases, jobIdx, totalJobs, outputDir, moveOutputToSource, mainScope, onProgressUpdate)

            BatchJobPhaseType.FAST_TRIM -> executeFastTrim(viewModel, job, phase, activePhases, jobIdx, totalJobs, outputDir, moveOutputToSource, mainScope, onProgressUpdate)

        }

    }



    private suspend fun executePlateScan(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        activePhases: List<BatchJobPhase>,

        jobIdx: Int,

        totalJobs: Int,

        mainScope: CoroutineScope,

        onProgressUpdate: () -> Unit

    ) {

        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] プレートスキャンを実行中..."

        val points = loadTelemetryPointsForRoadDetection(job.fitPath) { viewModel.isCanceled }

        val cache = viewModel.executePlateScanCore(

            trimStart = job.trimStartSeconds,
            trimEnd = job.trimEndSeconds,
            videoPath = job.videoPath,

            telemetryPoints = points,

            adjustedStartUtc = job.adjustedStartUtc,

            onProgress = { percent, status ->

                val now = System.currentTimeMillis()

                if (percent >= 100f || now - lastProgressUpdateTime >= 100) {

                    lastProgressUpdateTime = now

                    mainScope.launch {

                        phase.progress = percent / 100f

                        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] プレートスキャンを実行中: ${"%.1f".format(java.util.Locale.US, percent)}% ($status)"

                        updateOverallJobProgress(job, activePhases)

                        viewModel.progress = job.progress

                        onProgressUpdate()

                    }

                }

            },

            onCancel = { viewModel.isCanceled || !mainScope.isActive },



            settings = job.settings

        )

        if (viewModel.isCanceled) throw Exception("Canceled")

        if (cache == null) throw Exception("License plate scan failed")

        phase.status = BatchJobPhaseStatus.COMPLETED

        phase.progress = 1.0f

        viewModel.saveBatchQueue()

        onProgressUpdate()

    }



    private suspend fun executeRoadScan(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        jobIdx: Int,

        totalJobs: Int,

        onProgressUpdate: () -> Unit

    ) {

        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] 路線名検出を実行中..."

        val trimDuration = job.trimEndSeconds - job.trimStartSeconds

        val trimStartUtc = try {

            if (job.videoStartUtc.isNotEmpty()) {

                java.time.Instant.parse(job.videoStartUtc).plusMillis((job.trimStartSeconds * 1000).toLong()).toString()

            } else ""

        } catch (e: Exception) {

            job.videoStartUtc

        }

        val detectionContext = RoadCaptionDetectionContext(

            points = loadTelemetryPointsForRoadDetection(job.fitPath) { viewModel.isCanceled },

            videoStartUtc = trimStartUtc,

            timeOffsetMillis = job.timeOffsetMillis,

            videoDurationSeconds = trimDuration

        )

        val encodeSettings = prepareRoadCaptionSettingsForEncode(

            baseSettings = job.settings,

            autoDetectRoadCaptionsOnEncode = true,

            context = detectionContext,

            cancelCheck = { viewModel.isCanceled },

            onStatus = { progressText ->

                viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] $progressText"

            },

            onSettingsPrepared = { updated ->

                job.settings = updated

                viewModel.saveBatchQueue()

            }

        )

        phase.status = BatchJobPhaseStatus.COMPLETED

        phase.progress = 1.0f

        viewModel.saveBatchQueue()

        onProgressUpdate()

    }



    private suspend fun executeHudEncode(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        activePhases: List<BatchJobPhase>,

        jobIdx: Int,

        totalJobs: Int,

        outputDir: String,

        moveOutputToSource: Boolean,

        mainScope: CoroutineScope,

        onProgressUpdate: () -> Unit

    ) {

        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] HUDエンコードを実行中..."

        val detectedVideoDurationSeconds = getVideoDuration(job.videoPath)?.toDouble()?.div(1000.0)

        

        val roadScanPhase = job.phases.find { it.type == BatchJobPhaseType.ROAD_SCAN }
        val runRoadDetection = job.autoDetectRoadCaptionsOnEncode &&
                (roadScanPhase?.enabled == true) &&
                (roadScanPhase.status != BatchJobPhaseStatus.COMPLETED)

        

        val encodeSettings = if (runRoadDetection) {

            val trimDuration = job.trimEndSeconds - job.trimStartSeconds

            val trimStartUtc = try {

                if (job.videoStartUtc.isNotEmpty()) {

                    java.time.Instant.parse(job.videoStartUtc).plusMillis((job.trimStartSeconds * 1000).toLong()).toString()

                } else ""

            } catch (e: Exception) {

                job.videoStartUtc

            }

            val detectionContext = RoadCaptionDetectionContext(

                points = loadTelemetryPointsForRoadDetection(job.fitPath) { viewModel.isCanceled },

                videoStartUtc = trimStartUtc,

                timeOffsetMillis = job.timeOffsetMillis,

                videoDurationSeconds = trimDuration

            )

            prepareRoadCaptionSettingsForEncode(

                baseSettings = job.settings,

                autoDetectRoadCaptionsOnEncode = true,

                context = detectionContext,

                cancelCheck = { viewModel.isCanceled },

                onStatus = { progressText ->

                    viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] $progressText"

                }

            )

        } else {

            job.settings

        }

        val plateCache = if (job.settings.plateMaskMode == "cut") {
            fit.PlateCacheManager.loadCache(job.videoPath)
        } else {
            null
        }
        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = job.trimStartSeconds,
            trimEndSeconds = job.trimEndSeconds,
            splitPoints = job.splitPoints,
            settings = job.settings,
            plateCache = plateCache
        )
        if (job.settings.plateMaskMode == "cut" && ranges.isEmpty()) {
            phase.status = BatchJobPhaseStatus.SKIPPED
            phase.progress = 1.0f
            updateOverallJobProgress(job, activePhases)
            viewModel.progress = job.progress
            viewModel.saveBatchQueue()
            onProgressUpdate()
            return
        }
        val pipelineSettings = if (encodeSettings.plateMaskMode == "cut") {
            encodeSettings.copy(blurLicensePlates = false)
        } else {
            encodeSettings
        }

        val encodePlan = buildEncodePlan(

            settings = pipelineSettings,

            videoPath = job.videoPath,

            outputDir = outputDir,

            moveOutputToSource = moveOutputToSource,

            ranges = ranges,

            includeTrimRangeInFileName = hasTrimmedRange(

                trimStartSeconds = job.trimStartSeconds,

                trimEndSeconds = job.trimEndSeconds,

                videoDurationSeconds = detectedVideoDurationSeconds

            ),

            dateTag = buildDateTagFromUtc(job.adjustedStartUtc),

            outputFileNames = job.outputFileNames

        )

        val shouldMergePlateCut = job.settings.plateMaskMode == "cut" &&
            job.splitPoints.isEmpty() &&
            ranges.size > 1
        val mergeOutputFile = if (shouldMergePlateCut) {
            buildEncodePlan(
                settings = pipelineSettings,
                videoPath = job.videoPath,
                outputDir = outputDir,
                moveOutputToSource = moveOutputToSource,
                ranges = buildEncodeRanges(job.trimStartSeconds, job.trimEndSeconds, emptyList()),
                includeTrimRangeInFileName = hasTrimmedRange(
                    trimStartSeconds = job.trimStartSeconds,
                    trimEndSeconds = job.trimEndSeconds,
                    videoDurationSeconds = detectedVideoDurationSeconds
                ),
                dateTag = buildDateTagFromUtc(job.adjustedStartUtc),
                outputFileNames = job.outputFileNames?.takeIf { it.size == 1 }
            ).segments.firstOrNull()?.finalOutputFile
        } else {
            null
        }
        val destFiles = if (mergeOutputFile != null) {
            val partDir = File(outputDir, ".fittrimmer_cut_parts").apply { mkdirs() }
            encodePlan.segments.mapIndexed { idx, segment ->
                File(partDir, "${mergeOutputFile.nameWithoutExtension}_cutpart${idx + 1}_${segment.startSeconds}-${segment.endSeconds}.mp4")
            }
        } else {
            encodePlan.segments.map { it.finalOutputFile }
        }

        

        // Check if next phase (CONCAT_MERGE) exists and is enabled

        val nextConcatMerge = activePhases.find { it.type == BatchJobPhaseType.CONCAT_MERGE }

        val skipConcat = nextConcatMerge != null && job.settings.plateMaskMode != "cut"

        

        HudEncodePipeline.execute(

            s = pipelineSettings,

            fitPath = job.fitPath,

            videoPath = job.videoPath,

            outputDir = outputDir,

            videoStartUtc = job.adjustedStartUtc,

            sourceVideoStartUtc = job.videoStartUtc,

            timeOffsetMillis = job.timeOffsetMillis,

            ranges = ranges,

            destFiles = destFiles,

            shouldResume = phase.progress > 0f,

            moveOutputToSource = moveOutputToSource,

            onProgress = { prog, status ->

                val now = System.currentTimeMillis()

                if (prog >= 1.0f || now - lastProgressUpdateTime >= 100) {

                    lastProgressUpdateTime = now

                    mainScope.launch {

                        phase.progress = prog

                        viewModel.progress = prog

                        viewModel.statusText = status

                        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] $status"

                        updateOverallJobProgress(job, activePhases)

                        viewModel.progress = job.progress

                        onProgressUpdate()

                    }

                }

            },

            onFrame = { bufferedImg ->

                val now = System.currentTimeMillis()

                if (now - lastPreviewUpdateTime >= 33) {

                    lastPreviewUpdateTime = now

                    val composeBitmap = bufferedImg.toComposeImageBitmap()

                    mainScope.launch {

                        viewModel.encodingPreviewImage = composeBitmap

                    }

                }

            },

            cancelSupplier = { viewModel.isCanceled },
            earlyFinishSupplier = { viewModel.isEarlyFinish },
            showLivePreviewSupplier = { viewModel.showLivePreview },

            onSegmentStart = { pStart, pEnd ->

                mainScope.launch {

                    viewModel.encodingSegmentStart = pStart

                    viewModel.encodingSegmentEnd = pEnd

                }

            },

            skipConcat = skipConcat,
            mergeOutputFile = mergeOutputFile,
            hudTelemetryRange = mergeOutputFile?.let { job.trimStartSeconds to job.trimEndSeconds }

        )

        phase.status = BatchJobPhaseStatus.COMPLETED

        phase.progress = 1.0f

        viewModel.saveBatchQueue()

        onProgressUpdate()

    }



    private suspend fun executeConcatMerge(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        activePhases: List<BatchJobPhase>,

        jobIdx: Int,

        totalJobs: Int,

        outputDir: String,

        moveOutputToSource: Boolean,

        mainScope: CoroutineScope,

        onProgressUpdate: () -> Unit

    ) {

        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] 動画を結合マージ中..."

        if (job.settings.plateMaskMode == "cut") {
            phase.status = BatchJobPhaseStatus.SKIPPED
            phase.progress = 1.0f
            updateOverallJobProgress(job, activePhases)
            viewModel.progress = job.progress
            viewModel.saveBatchQueue()
            onProgressUpdate()
            return
        }

        val detectedVideoDurationSeconds = getVideoDuration(job.videoPath)?.toDouble()?.div(1000.0)
        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = job.trimStartSeconds,
            trimEndSeconds = job.trimEndSeconds,
            splitPoints = job.splitPoints,
            settings = job.settings,
            plateCache = if (job.settings.plateMaskMode == "cut") fit.PlateCacheManager.loadCache(job.videoPath) else null
        )

        val encodePlan = buildEncodePlan(

            settings = job.settings,

            videoPath = job.videoPath,

            outputDir = outputDir,

            moveOutputToSource = moveOutputToSource,

            ranges = ranges,

            includeTrimRangeInFileName = hasTrimmedRange(

                trimStartSeconds = job.trimStartSeconds,

                trimEndSeconds = job.trimEndSeconds,

                videoDurationSeconds = detectedVideoDurationSeconds

            ),

            dateTag = buildDateTagFromUtc(job.videoStartUtc),

            outputFileNames = job.outputFileNames

        )

        
        val finalDestFile = encodePlan.segments.first().finalOutputFile

        val availableJobs = fit.CacheJobManager.getInstance().scanJobs(job.videoPath)

        val targetJob = if (availableJobs.isEmpty()) {
            if (finalDestFile.exists() && finalDestFile.length() > 0L) {
                println("✨ Output file already exists and no cache jobs found. Bypassing merge phase.")
                phase.status = BatchJobPhaseStatus.COMPLETED
                phase.progress = 1.0f
                viewModel.saveBatchQueue()
                onProgressUpdate()
                return
            }
            throw Exception("結合する一時エンコードデータ（キャッシュ）が見つかりません。")
        } else {
            availableJobs.first()
        }

        targetJob.salvageAndMerge(
            outputFile = finalDestFile,

            onProgress = { prog, status ->

                mainScope.launch {

                    phase.progress = prog

                    viewModel.progress = prog

                    viewModel.statusText = status

                    viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] $status"

                    updateOverallJobProgress(job, activePhases)

                    viewModel.progress = job.progress

                    onProgressUpdate()

                }

            }

        )

        

        if (moveOutputToSource) {

            val normalized = job.videoPath.replace("\\", "/").lowercase()

            if (normalized.contains("google drive") ||

                normalized.contains("マイドライブ") ||

                normalized.contains("my drive") ||

                normalized.startsWith("g:/") ||

                normalized.startsWith("h:/")) {

                viewModel.batchStatusText = "✨ クラウドストレージ同期中 (Google Drive)..."

            }

        }

        

        phase.status = BatchJobPhaseStatus.COMPLETED

        phase.progress = 1.0f

        viewModel.saveBatchQueue()

        onProgressUpdate()

    }



    private suspend fun executeFastTrim(

        viewModel: AppViewModel,

        job: BatchJob,

        phase: BatchJobPhase,

        activePhases: List<BatchJobPhase>,

        jobIdx: Int,

        totalJobs: Int,

        outputDir: String,

        moveOutputToSource: Boolean,

        mainScope: CoroutineScope,

        onProgressUpdate: () -> Unit

    ) {

        viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] 高速トリミングを実行中..."

        val ranges = buildEncodeRanges(job.trimStartSeconds, job.trimEndSeconds, job.splitPoints)

        val detectedVideoDurationSeconds = getVideoDuration(job.videoPath)?.toDouble()?.div(1000.0)

        val encodePlan = buildEncodePlan(

            settings = job.settings,

            videoPath = job.videoPath,

            outputDir = outputDir,

            moveOutputToSource = moveOutputToSource,

            ranges = ranges,

            includeTrimRangeInFileName = hasTrimmedRange(

                trimStartSeconds = job.trimStartSeconds,

                trimEndSeconds = job.trimEndSeconds,

                videoDurationSeconds = detectedVideoDurationSeconds

            ),

            dateTag = buildDateTagFromUtc(job.videoStartUtc),

            outputFileNames = job.outputFileNames

        )

        val destFile = encodePlan.segments.first().finalOutputFile

        

        fit.HudEncoderSegmentRegistry.activeSegments = viewModel.detectedSegments.toList()
        val encoder = NativeHudEncoder(job.settings,

            onProgress = { prog, status ->

                mainScope.launch {

                    phase.progress = prog

                    viewModel.progress = prog

                    viewModel.statusText = status

                    viewModel.batchStatusText = "[${jobIdx + 1}/$totalJobs] $status"

                    updateOverallJobProgress(job, activePhases)

                    viewModel.progress = job.progress

                    onProgressUpdate()

                }

            },

            cancelSupplier = { viewModel.isCanceled }

        )

        encoder.encode(

            fitPath = "",

            videoPath = job.videoPath,

            output = destFile.absolutePath,

            startUtc = job.videoStartUtc,

            maxDurationSeconds = -1,

            trimStartSeconds = job.trimStartSeconds,

            trimEndSeconds = job.trimEndSeconds,

            shouldResume = false

        )

        phase.status = BatchJobPhaseStatus.COMPLETED

        phase.progress = 1.0f

        viewModel.saveBatchQueue()

        onProgressUpdate()

    }

}



suspend fun runBatchJobs(

    viewModel: AppViewModel,

    outputDir: String,

    moveOutputToSource: Boolean,

    onProgressUpdate: () -> Unit

) = BatchJobRunner.runBatchJobs(viewModel, outputDir, moveOutputToSource, onProgressUpdate)



suspend fun queryRoadName(

    lat: Double,

    lon: Double,

    heading: Double? = null,

    language: String = "",

    cancelCheck: () -> Boolean = { false }

): String? {

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

                val gsiResponse = awaitHttpResponse(

                    client.sendAsync(gsiRequest, java.net.http.HttpResponse.BodyHandlers.ofString()),

                    cancelCheck = cancelCheck

                )

                if (gsiResponse.statusCode() == 200) {

                    val roadInfo = fit.GsiRoadDetector.findClosestRoad(lat, lon, gsiResponse.body(), carHeading = heading, maxDistanceMeters = MAX_ROAD_SNAP_DISTANCE_METERS)

                    if (roadInfo != null && roadInfo.distanceMeters <= 50.0) {

                        rdCtg = roadInfo.rdCtg

                        gsiRoadName = roadInfo.name ?: roadInfo.comName

                    }

                }

        } catch (e: Exception) {

            if (cancelCheck()) throw e

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

            val response = awaitHttpResponse(

                client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()),

                cancelCheck = cancelCheck

            )

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

            if (cancelCheck()) throw e

            e.printStackTrace()

        }

        null

    }

}



suspend fun <T> awaitHttpResponse(

    future: java.util.concurrent.CompletableFuture<T>,

    cancelCheck: () -> Boolean

): T {

    while (true) {

        if (cancelCheck()) {

            future.cancel(true)

            throw IllegalStateException("Encoding Canceled")

        }

        if (future.isDone) {

            return try {

                future.get()

            } catch (e: java.util.concurrent.ExecutionException) {

                throw (e.cause ?: e)

            }

        }

        kotlinx.coroutines.delay(50)

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

    points: List<fit.TelemetryPoint>,

    videoStartUtc: String,

    timeOffsetMillis: Long,

    videoDurationSeconds: Double,

    language: String = "",

    enableRoadDetection: Boolean = true,

    cancelCheck: () -> Boolean = { false },

    onProgress: (String) -> Unit

): List<RoadCaptionSegment> {

    if (!enableRoadDetection) return emptyList()

    if (points.isEmpty() || videoStartUtc.isEmpty()) return emptyList()

    if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

    val fitEpoch = 631065600L

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

        if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

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

        if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

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

        if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

        val targetFitTime = startFitTime + currentOffset

        val point = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - targetFitTime) } ?: continue

        val progressPercent = ((index.toDouble() / totalQueries) * 100).toInt()

        onProgress("GPS解析中 (${progressPercent}%): 座標 (${"%.4f".format(point.lat)}, ${"%.4f".format(point.lon)}) 付近で道路判定中...")

        // Safe rate limiting (1s wait) only for active queries

        repeat(20) {

            if (cancelCheck()) throw IllegalStateException("Encoding Canceled")

            kotlinx.coroutines.delay(50)

        }

        val headingVal = headings.getOrNull(currentOffset.toInt()) ?: -1.0

        val roadName = queryRoadName(

            point.lat,

            point.lon,

            if (headingVal >= 0.0) headingVal else null,

            language = language,

            cancelCheck = cancelCheck

        )

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



@OptIn(ExperimentalComposeUiApi::class)

@Composable

fun TimeSpinnerField(

    valueText: String,

    onValueChange: (String) -> Unit,

    onIncrement: () -> Unit,

    onDecrement: () -> Unit,

    label: String,

    enabled: Boolean

) {

    var dragAccumulator = 0f

    Row(

        verticalAlignment = Alignment.CenterVertically,

        horizontalArrangement = Arrangement.spacedBy(4.dp)

    ) {

        OutlinedTextField(

            value = valueText,

            onValueChange = onValueChange,

            enabled = enabled,

            modifier = Modifier

                .width(42.dp)

                .height(45.dp)

                .onPointerEvent(PointerEventType.Scroll) { event ->

                    if (enabled) {

                        val delta = event.changes.first().scrollDelta.y

                        if (delta > 0) onDecrement() else if (delta < 0) onIncrement()

                    }

                }

                .pointerInput(enabled) {

                    if (enabled) {

                        detectVerticalDragGestures { change, dragAmount ->

                            change.consume()

                            dragAccumulator += dragAmount

                            if (dragAccumulator > 12f) {

                                onDecrement()

                                dragAccumulator = 0f

                            } else if (dragAccumulator < -12f) {

                                onIncrement()

                                dragAccumulator = 0f

                            }

                        }

                    }

                },

            textStyle = TextStyle(fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold),

            singleLine = true,

            colors = TextFieldDefaults.outlinedTextFieldColors(

                backgroundColor = Color(0xFFF2F2F7),

                focusedBorderColor = Color(0xFF007AFF),

                unfocusedBorderColor = Color(0xFFD1D1D6),

                textColor = Color(0xFF1C1C1E)

            )

        )

        Text(label, fontSize = 9.sp, color = Color(0xFF1C1C1E))

    }

}



@Composable

fun SourceRangeSummary(

    fitStartInstant: java.time.Instant?,

    fitEndInstant: java.time.Instant?,

    videoStartInstant: java.time.Instant?,

    videoEndInstant: java.time.Instant?,

    isVideoInFitRange: Boolean,

    isHudBurned: Boolean,

    videoStartUtc: String = "",

    timeOffsetState: TimeAlignmentState? = null,

    isEncoding: Boolean = false

) {

    if (fitStartInstant == null || fitEndInstant == null) return



    val formatter = remember {

        java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")

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

    val videoStartSecInFit = videoStartInstant?.let {

        java.time.Duration.between(fitStartInstant, it).toMillis() / 1000.0

    }

    val videoEndSecInFit = videoEndInstant?.let {

        java.time.Duration.between(fitStartInstant, it).toMillis() / 1000.0

    }

    fun formatSignedSeconds(seconds: Float): String {

        return "${if (seconds >= 0f) "+" else ""}${String.format(java.util.Locale.US, "%.3f", seconds)} s"

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

                if (videoStartSecInFit != null && videoEndSecInFit != null) {

                    Text(

                        "FIT内の動画範囲: %.3fs - %.3fs".format(java.util.Locale.US, videoStartSecInFit, videoEndSecInFit),

                        color = Color(0xFF636366),

                        fontSize = 9.sp,

                        maxLines = 1

                    )

                }

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

        if (timeOffsetState != null && videoStartUtc.isNotEmpty()) {

            Divider(color = Color(0xFFE5E5EA))

            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.SpaceBetween,

                verticalAlignment = Alignment.CenterVertically

            ) {

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {

                    Text("テレメトリ範囲の秒調整", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 10.sp)

                    Text("現在の補正: ${formatSignedSeconds(timeOffsetState.seconds)}", color = Color(0xFF007AFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)

                }

                OutlinedButton(

                    onClick = { timeOffsetState.update(0) },

                    enabled = !isEncoding,

                    modifier = Modifier.height(28.dp),

                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF636366)),

                    border = BorderStroke(1.dp, Color(0xFFD1D1D6)),

                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

                ) {

                    Text("0", fontSize = 10.sp)

                }

            }

            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(4.dp),

                verticalAlignment = Alignment.CenterVertically

            ) {

                listOf(

                    "-10s" to -10000,

                    "-1s" to -1000,

                    "+1s" to 1000,

                    "+10s" to 10000

                ).forEach { (label, deltaMs) ->

                    OutlinedButton(

                        onClick = { timeOffsetState.update(timeOffsetState.millis + deltaMs) },

                        enabled = !isEncoding,

                        modifier = Modifier.weight(1f).height(28.dp),

                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E)),

                        border = BorderStroke(1.dp, Color(0xFFD1D1D6)),

                        contentPadding = PaddingValues(0.dp)

                    ) {

                        Text(label, fontSize = 10.sp)

                    }

                }

            }

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

    telemetryPoints: List<TelemetryPoint>,

    isAligning: Boolean,

    onAlignTelemetryClick: () -> Unit,

    isEncoding: Boolean,

    videoStartUtc: String = "",

    syncCandidates: List<TelemetryAligner.AlignmentCandidate> = emptyList(),

    onApplySyncCandidate: ((TelemetryAligner.AlignmentCandidate) -> Unit)? = null,

    onOpenSyncPanelClick: (() -> Unit)? = null,

    onOpenManualJstSyncClick: (() -> Unit)? = null

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

            

            // 補正後開始時刻(JST)の表示

            val currentOffsetSec = state.seconds

            val originalInstant = try { java.time.Instant.parse(videoStartUtc) } catch(e: Exception) { null }

            val adjustedJstStr = if (originalInstant != null) {

                val adjustedInstant = originalInstant.plusMillis(state.millis.toLong())

                val jst = java.time.ZonedDateTime.ofInstant(adjustedInstant, java.time.ZoneId.of("Asia/Tokyo"))

                jst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            } else {

                null

            }

            if (adjustedJstStr != null) {

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.SpaceBetween,

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Text("補正後開始時刻 (JST)", color = Color(0xFF636366), fontSize = 10.sp)

                    Text(

                        adjustedJstStr,

                        color = Color(0xFF34C759),

                        fontSize = 11.sp,

                        fontWeight = FontWeight.Bold

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

                    "${if (state.seconds >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.3f", state.seconds)} s",

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

            

            // 数値直接入力 & 同期管理ダイアログ起動

            Row(

                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically,

                horizontalArrangement = Arrangement.spacedBy(8.dp)

            ) {

                Text("数値直接入力 (秒):", color = Color(0xFF636366), fontSize = 10.sp, modifier = Modifier.alignByBaseline())

                var textVal by remember(state.millis) { mutableStateOf(String.format(java.util.Locale.US, "%.3f", state.seconds)) }

                OutlinedTextField(

                    value = textVal,

                    onValueChange = { newValue ->

                        textVal = newValue

                        val parsed = newValue.toDoubleOrNull()

                        if (parsed != null) {

                            state.update((parsed * 1000).roundToInt())

                        }

                    },

                    enabled = !isEncoding,

                    modifier = Modifier.width(90.dp).height(45.dp),

                    textStyle = TextStyle(fontSize = 11.sp),

                    singleLine = true,

                    colors = TextFieldDefaults.outlinedTextFieldColors(

                        backgroundColor = Color.White,

                        focusedBorderColor = Color(0xFF007AFF),

                        unfocusedBorderColor = Color(0xFFE5E5EA)

                    )

                )

                

                if (onOpenSyncPanelClick != null && videoPath.isNotEmpty() && telemetryPoints.isNotEmpty()) {

                    OutlinedButton(

                        onClick = onOpenSyncPanelClick,

                        enabled = !isEncoding,

                        modifier = Modifier.height(28.dp).weight(1f),

                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF007AFF)),

                        border = BorderStroke(1.dp, Color(0xFF007AFF)),

                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)

                    ) {

                        Text("詳細同期パネル", fontSize = 10.sp)

                    }

                }

                

                if (onOpenManualJstSyncClick != null && videoStartUtc.isNotEmpty()) {

                    OutlinedButton(

                        onClick = onOpenManualJstSyncClick,

                        enabled = !isEncoding,

                        modifier = Modifier.height(28.dp).weight(1f),

                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF007AFF)),

                        border = BorderStroke(1.dp, Color(0xFF007AFF)),

                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)

                    ) {

                        Text("開始時刻 (JST) 補正", fontSize = 10.sp)

                    }

                }

            }



            // 微調整ボタン（2段構成）

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                val buttonModifier = Modifier.height(24.dp)

                val buttonColors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))

                

                // 1段目: 大まかな移動

                val specs1 = listOf(

                    "-60s" to -60000,

                    "-10s" to -10000,

                    "-1s" to -1000,

                    "0" to 0,

                    "+1s" to 1000,

                    "+10s" to 10000,

                    "+60s" to 60000

                )

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.spacedBy(4.dp)

                ) {

                    for ((label, delta) in specs1) {

                        OutlinedButton(

                            onClick = {

                                val nextVal = if (delta == 0) 0 else state.millis + delta

                                state.update(nextVal)

                            },

                            enabled = !isEncoding,

                            modifier = buttonModifier.weight(1f),

                            colors = buttonColors,

                            contentPadding = PaddingValues(0.dp)

                        ) {

                            Text(label, fontSize = 9.sp)

                        }

                    }

                }

                

                // 2段目: 精密な移動

                val specs2 = listOf(

                    "-0.5s" to -500,

                    "-0.1s" to -100,

                    "+0.1s" to 100,

                    "+0.5s" to 500

                )

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.spacedBy(4.dp)

                ) {

                    for ((label, delta) in specs2) {

                        OutlinedButton(

                            onClick = {

                                val nextVal = state.millis + delta

                                state.update(nextVal)

                            },

                            enabled = !isEncoding,

                            modifier = buttonModifier.weight(1f),

                            colors = buttonColors,

                            contentPadding = PaddingValues(0.dp)

                        ) {

                            Text(label, fontSize = 9.sp)

                        }

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

            if (syncCandidates.isNotEmpty()) {

                Divider(color = Color(0xFFE5E5EA))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                    Text("IMU同期候補", color = Color(0xFF1C1C1E), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                    syncCandidates.take(5).forEach { candidate ->

                        Row(

                            modifier = Modifier

                                .fillMaxWidth()

                                .background(Color(0xFFF2F2F7), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))

                                .padding(horizontal = 8.dp, vertical = 5.dp),

                            horizontalArrangement = Arrangement.spacedBy(6.dp),

                            verticalAlignment = Alignment.CenterVertically

                        ) {

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {

                                val offsetText = candidate.offsetSeconds?.let {

                                    "${if (it >= 0.0) "+" else ""}${String.format(java.util.Locale.US, "%.3f", it)}s"

                                } ?: "--"

                                Text(

                                    "#${candidate.rank}  $offsetText  r=${String.format(java.util.Locale.US, "%.3f", candidate.correlation)}",

                                    color = Color(0xFF1C1C1E),

                                    fontSize = 10.sp,

                                    fontWeight = FontWeight.Bold

                                )

                                Text(

                                    "FIT ${String.format(java.util.Locale.US, "%.1f", candidate.fitStartSeconds)}s / ${candidate.alignedUtc}",

                                    color = Color(0xFF636366),

                                    fontSize = 9.sp,

                                    maxLines = 1

                                )

                            }

                            OutlinedButton(

                                onClick = { onApplySyncCandidate?.invoke(candidate) },

                                enabled = !isEncoding && onApplySyncCandidate != null,

                                modifier = Modifier.height(28.dp),

                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF007AFF)),

                                border = BorderStroke(1.dp, Color(0xFF007AFF)),

                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

                            ) {

                                Text("適用", fontSize = 10.sp)

                            }

                        }

                    }

                }

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



@Composable

fun JstManualSyncDialog(

    videoStartUtc: String,

    videoStartInstant: java.time.Instant?,

    timeOffsetState: TimeAlignmentState,

    isEncoding: Boolean,

    onClose: () -> Unit

) {

    var dragAccumulator = 0f

    Box(

        modifier = Modifier

            .fillMaxSize()

            .background(Color.Black.copy(alpha = 0.5f))

            .clickable(enabled = true, onClick = onClose),

        contentAlignment = Alignment.Center

    ) {

        Card(

            modifier = Modifier

                .width(360.dp)

                .wrapContentHeight()

                .clickable(

                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },

                    indication = null

                ) { /* クリック伝播防止 */ },

            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),

            elevation = 8.dp,

            backgroundColor = Color.White

        ) {

            Column(

                modifier = Modifier.padding(20.dp),

                verticalArrangement = Arrangement.spacedBy(16.dp)

            ) {

                Text(

                    text = "動画開始時刻 (JST) の手動調整",

                    color = Color(0xFF1C1C1E),

                    fontWeight = FontWeight.Bold,

                    fontSize = 14.sp

                )

                

                Text(

                    text = "動画の記録開始時刻を直接入力、またはマウスホイール/ドラッグで微調整して、GPSテレメトリとの同期ズレを解消します。",

                    color = Color(0xFF636366),

                    fontSize = 11.sp,

                    lineHeight = 14.sp

                )

                

                val currentJst = if (videoStartInstant != null) {

                    java.time.ZonedDateTime.ofInstant(videoStartInstant, java.time.ZoneId.of("Asia/Tokyo"))

                } else null

                

                if (currentJst != null) {

                    var hourText by remember(videoStartInstant) { mutableStateOf(currentJst.hour.toString()) }

                    var minText by remember(videoStartInstant) { mutableStateOf(currentJst.minute.toString()) }

                    var secText by remember(videoStartInstant) { mutableStateOf(currentJst.second.toString()) }

                    

                    val toHalfWidth = { s: String ->

                        s.map { c ->

                            if (c in '０'..'９') (c - '０' + '0'.code).toChar() else c

                        }.joinToString("").filter { it.isDigit() }

                    }



                    val updateTime = {

                        val h = hourText.toIntOrNull()

                        val m = minText.toIntOrNull()

                        val s = secText.toIntOrNull()

                        if (h != null && m != null && s != null) {

                            timeOffsetState.updateTimeComponents(h, m, s, videoStartUtc)

                        }

                    }

                    

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.Center,

                        verticalAlignment = Alignment.CenterVertically

                    ) {

                        // 時

                        TimeSpinnerField(

                            valueText = hourText,

                            onValueChange = { newValue ->

                                val clean = toHalfWidth(newValue)

                                if (clean.length <= 2) {

                                    hourText = clean

                                    updateTime()

                                }

                            },

                            onIncrement = {

                                val next = ((hourText.toIntOrNull() ?: currentJst.hour) + 1) % 24

                                hourText = next.toString()

                                updateTime()

                            },

                            onDecrement = {

                                val cur = hourText.toIntOrNull() ?: currentJst.hour

                                val next = if (cur - 1 < 0) 23 else cur - 1

                                hourText = next.toString()

                                updateTime()

                            },

                            label = "時",

                            enabled = !isEncoding

                        )

                        

                        Spacer(Modifier.width(12.dp))



                        // 分

                        TimeSpinnerField(

                            valueText = minText,

                            onValueChange = { newValue ->

                                val clean = toHalfWidth(newValue)

                                if (clean.length <= 2) {

                                    minText = clean

                                    updateTime()

                                }

                            },

                            onIncrement = {

                                val next = ((minText.toIntOrNull() ?: currentJst.minute) + 1) % 60

                                minText = next.toString()

                                updateTime()

                            },

                            onDecrement = {

                                val cur = minText.toIntOrNull() ?: currentJst.minute

                                val next = if (cur - 1 < 0) 59 else cur - 1

                                minText = next.toString()

                                updateTime()

                            },

                            label = "分",

                            enabled = !isEncoding

                        )

                        

                        Spacer(Modifier.width(12.dp))



                        // 秒

                        TimeSpinnerField(

                            valueText = secText,

                            onValueChange = { newValue ->

                                val clean = toHalfWidth(newValue)

                                if (clean.length <= 2) {

                                    secText = clean

                                    updateTime()

                                }

                            },

                            onIncrement = {

                                val next = ((secText.toIntOrNull() ?: currentJst.second) + 1) % 60

                                secText = next.toString()

                                updateTime()

                            },

                            onDecrement = {

                                val cur = secText.toIntOrNull() ?: currentJst.second

                                val next = if (cur - 1 < 0) 59 else cur - 1

                                secText = next.toString()

                                updateTime()

                            },

                            label = "秒",

                            enabled = !isEncoding

                        )

                    }

                }

                

                // オフセット計算結果の表示

                val currentOffsetSec = timeOffsetState.seconds

                val originalInstant = try { java.time.Instant.parse(videoStartUtc) } catch(e: Exception) { null }

                val adjustedJstStr = if (originalInstant != null) {

                    val adjustedInstant = originalInstant.plusMillis(timeOffsetState.millis.toLong())

                    val jst = java.time.ZonedDateTime.ofInstant(adjustedInstant, java.time.ZoneId.of("Asia/Tokyo"))

                    jst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                } else null

                

                if (adjustedJstStr != null) {

                    Column(

                        modifier = Modifier

                            .fillMaxWidth()

                            .background(Color(0xFFF2F2F7), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))

                            .padding(10.dp),

                        verticalArrangement = Arrangement.spacedBy(4.dp)

                    ) {

                        Row(

                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement = Arrangement.SpaceBetween

                        ) {

                            Text("補正前の開始 (JST):", color = Color(0xFF636366), fontSize = 10.sp)

                            val origJst = originalInstant?.let { java.time.ZonedDateTime.ofInstant(it, java.time.ZoneId.of("Asia/Tokyo")) }

                            Text(origJst?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "", color = Color(0xFF1C1C1E), fontSize = 10.sp)

                        }

                        Row(

                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement = Arrangement.SpaceBetween

                        ) {

                            Text("補正後の開始 (JST):", color = Color(0xFF636366), fontSize = 10.sp)

                            Text(adjustedJstStr.substringAfter(" "), color = Color(0xFF34C759), fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        }

                        Row(

                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement = Arrangement.SpaceBetween

                        ) {

                            Text("HUD表示ズレ (オフセット):", color = Color(0xFF636366), fontSize = 10.sp)

                            Text(

                                "${if (currentOffsetSec >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.3f", currentOffsetSec)} 秒",

                                color = Color(0xFF007AFF),

                                fontSize = 11.sp,

                                fontWeight = FontWeight.Bold

                            )

                        }

                    }

                }

                

                // 操作のヒント

                Text(

                    text = "ヒント: 各入力ボックスの上でマウスホイールをスクロールするか、上下にドラッグすることで、数値を素早く微調整できます。",

                    color = Color(0xFF8E8E93),

                    fontSize = 9.sp,

                    lineHeight = 12.sp

                )

                

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.End

                ) {

                    Button(

                        onClick = onClose,

                        colors = ButtonDefaults.buttonColors(

                            backgroundColor = Color(0xFF007AFF),

                            contentColor = Color.White

                        ),

                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

                    ) {

                        Text("閉じる", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    }

                }

            }

        }

    }

}

