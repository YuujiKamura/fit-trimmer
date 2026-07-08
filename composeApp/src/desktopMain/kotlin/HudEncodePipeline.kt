import java.io.File
import java.awt.image.BufferedImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import fit.HudConfig
import fit.DynamicRendererProxy
import fit.NativeHudEncoder
import fit.EncodeGroundTruthMetadata
import fit.HudSettings
import fit.FitParser
import fit.PlateCacheManager
import fit.VideoPlatesCache
import utils.PlateDetectionManager

typealias PlatePreScanner = suspend (
    videoPath: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    adjustedStartUtc: String,
    onProgress: (Float, String) -> Unit,
    onCancel: () -> Boolean,
    settings: HudSettings,
    scanRanges: List<Pair<Double, Double>>?
) -> VideoPlatesCache?

object HudEncodePipeline {

    private val defaultPlatePreScanner: PlatePreScanner = { videoPath, telemetryPoints, adjustedStartUtc, onProgress, onCancel, settings, scanRanges ->
        PlateDetectionManager.runDetection(
            videoPath = videoPath,
            telemetryPoints = telemetryPoints,
            adjustedStartUtc = adjustedStartUtc,
            onProgress = onProgress,
            onCancel = onCancel,
            saveCache = false,
            settings = settings,
            scanRanges = scanRanges
        )
    }

    suspend fun ensurePlateCacheForEncode(
        settings: HudSettings,
        videoPath: String,
        telemetryPoints: List<FitParser.TelemetryPoint> = emptyList(),
        adjustedStartUtc: String = "",
        ranges: List<Pair<Double, Double>> = emptyList(),
        onProgress: (progress: Float, statusText: String) -> Unit = { _, _ -> },
        cancelSupplier: () -> Boolean = { false },
        platePreScanner: PlatePreScanner = defaultPlatePreScanner
    ): VideoPlatesCache? {
        if (!settings.blurLicensePlates || videoPath.isEmpty()) {
            return PlateCacheManager.loadCache(videoPath)
        }

        val requestedRanges = ranges.filter { it.second > it.first }
        val existingCache = PlateCacheManager.loadCache(videoPath)
        if (existingCache != null && (requestedRanges.isEmpty() || existingCache.coversRanges(requestedRanges))) {
            return existingCache
        }
        if (cancelSupplier()) throw Exception("Encoding Canceled")

        onProgress(0f, "Scanning license plates before encoding...")
        val cache = platePreScanner(
            videoPath,
            telemetryPoints,
            adjustedStartUtc,
            { percent, status ->
                onProgress(0f, "Scanning license plates before encoding: ${"%.1f".format(java.util.Locale.US, percent)}% ($status)")
            },
            cancelSupplier,
            settings,
            requestedRanges.ifEmpty { null }
        )

        if (cancelSupplier()) throw Exception("Encoding Canceled")
        if (cache == null) {
            throw Exception("License plate blur is enabled, but plate scan cache could not be created.")
        }

        val mergedCache = existingCache?.mergedWith(cache) ?: cache
        PlateCacheManager.saveCache(videoPath, mergedCache)
        onProgress(0f, "Plate scan complete. Starting encode...")
        return mergedCache
    }
    suspend fun execute(
        s: HudSettings,
        fitPath: String,
        videoPath: String,
        outputDir: String,
        videoStartUtc: String,
        sourceVideoStartUtc: String = videoStartUtc,
        timeOffsetMillis: Long = 0L,
        ranges: List<Pair<Double, Double>>,
        destFiles: List<File>,
        shouldResume: Boolean = false,
        moveOutputToSource: Boolean = false,
        plateTelemetryPoints: List<FitParser.TelemetryPoint> = emptyList(),
        platePreScanner: PlatePreScanner = defaultPlatePreScanner,
        onProgress: (progress: Float, statusText: String) -> Unit,
        onFrame: (BufferedImage) -> Unit,
        cancelSupplier: () -> Boolean,
        showLivePreviewSupplier: () -> Boolean,
        onSegmentStart: (start: Double, end: Double) -> Unit = { _, _ -> },
        skipConcat: Boolean = false
    ): String {
        return withContext(Dispatchers.IO) {
            val lockFile = File(fit.PathResolver.getProjectRoot(), "temp_work/encoding.lock")
            lockFile.parentFile.mkdirs()
            var lockRaf: java.io.RandomAccessFile? = null
            var fileLock: java.nio.channels.FileLock? = null
            try {
                lockRaf = java.io.RandomAccessFile(lockFile, "rw")
                fileLock = lockRaf.channel.tryLock()
                if (fileLock == null) {
                    println("WARNING: Another encoding job might be active (encoding.lock is locked).")
                }
            val config = HudConfig(
                valSize = s.valSize, tightness = s.tightness, spacing = s.spacing,
                xOffset = s.xOffset, yOffset = s.yOffset, graphH = s.graphH, graphW = s.graphW,
                captionPosition = s.captionPosition,
                roadCaptions = s.roadCaptions,
                powerTrendSpanSeconds = s.powerTrendSpanSeconds,
                useImperialUnits = s.useImperialUnits,
                language = s.language,
                customCaptions = s.customCaptions,
                trimStartSeconds = ranges.firstOrNull()?.first ?: 0.0,
                mapSizeScale = s.mapSizeScale,
                mapType = s.mapType,
                mapPosition = s.mapPosition,
                hudBgAlpha = s.hudBgAlpha,
                mapZoomScale = s.mapZoomScale,
                mapZoomOffset = s.mapZoomOffset,
                fixMapNorthUp = s.fixMapNorthUp,
                mapMarkerSizeScale = s.mapMarkerSizeScale,
                mapTextSizeScale = s.mapTextSizeScale,
                mapRangeMode = s.mapRangeMode
            )
            val proxy = DynamicRendererProxy(config)
            globalRendererProxy = proxy

            val encodePlan = buildEncodePlan(
                settings = s,
                videoPath = videoPath,
                outputDir = outputDir,
                moveOutputToSource = moveOutputToSource,
                ranges = ranges
            )

            val totalDuration = encodePlan.totalDurationSeconds
            ensurePlateCacheForEncode(
                settings = s,
                videoPath = videoPath,
                telemetryPoints = plateTelemetryPoints,
                adjustedStartUtc = videoStartUtc,
                ranges = ranges,
                onProgress = onProgress,
                cancelSupplier = cancelSupplier,
                platePreScanner = platePreScanner
            )
            var completedDuration = 0.0
            var hasCloudSyncMsg = false
            var finalOutPath = ""

            for (segment in encodePlan.segments) {
                if (cancelSupplier()) break
                val idx = segment.index
                val pStart = segment.startSeconds
                val pEnd = segment.endSeconds
                val partDuration = pEnd - pStart

                val outputFileName = buildEncodeOutputFileName(
                    settings = s,
                    videoPath = videoPath,
                    partIndex = idx,
                    numParts = encodePlan.segments.size
                )
                val partOutPath = File(outputDir, outputFileName).absolutePath

                val finalDestFile = destFiles.getOrNull(idx)
                val checkFile = if (skipConcat) File(partOutPath) else finalDestFile
                if (shouldResume && checkFile != null && checkFile.exists() && checkFile.length() > 0L) {
                    println("DEBUG: Segment ${idx + 1} already finished. Skipping. File: ${checkFile.absolutePath}")
                    completedDuration += partDuration
                    finalOutPath = checkFile.absolutePath
                    continue
                }

                onSegmentStart(pStart, pEnd)

                val encoder = NativeHudEncoder(s,
                    onProgress = { prog, status ->
                        val segmentProgress = prog.toDouble()
                        val overallProg = if (totalDuration > 0.0) {
                            (completedDuration + segmentProgress * partDuration) / totalDuration
                        } else 0.0
                        onProgress(overallProg.toFloat(), "[Part ${idx + 1}/${ranges.size}] $status")
                    },
                    onFrameRendered = onFrame,
                    cancelSupplier = cancelSupplier,
                    customRenderer = { canvas, point, allPoints, trimmedPoints, pBuf, progressRatio ->
                        proxy.renderFrame(canvas, point, allPoints, trimmedPoints, pBuf, progressRatio)
                    },
                    showLivePreviewSupplier = showLivePreviewSupplier
                )

                encoder.encode(fitPath, videoPath, partOutPath, videoStartUtc,
                    maxDurationSeconds = -1,
                    trimStartSeconds = pStart,
                    trimEndSeconds = pEnd,
                    shouldResume = shouldResume,
                    skipConcat = skipConcat,
                    groundTruthMetadata = EncodeGroundTruthMetadata(
                        sourceVideoPath = videoPath,
                        sourceVideoStartUtc = sourceVideoStartUtc,
                        alignedVideoStartUtc = videoStartUtc,
                        timeOffsetMillis = timeOffsetMillis
                    )
                )

                if (!skipConcat && destFiles.isNotEmpty() && !cancelSupplier()) {
                    if (finalDestFile != null) {
                        val outFile = File(partOutPath)
                        if (outFile.absolutePath != finalDestFile.absolutePath) {
                            onProgress(1.0f, "[Part ${idx + 1}/${ranges.size}] Moving file to destination...")
                            Files.copy(outFile.toPath(), finalDestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            outFile.delete()
                        }
                        finalOutPath = finalDestFile.absolutePath

                        if (moveOutputToSource) {
                            val normalized = videoPath.replace("\\", "/").lowercase()
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

            if (cancelSupplier()) {
                throw Exception("Encoding Canceled")
            }

            if (hasCloudSyncMsg) {
                "✨ Copied to Cloud. Drive Desktop is syncing in background (Check system tray)."
            } else {
                "✨ Finished Successfully!"
            }
            } finally {
                try {
                    fileLock?.release()
                } catch (e: Exception) {}
                try {
                    lockRaf?.close()
                } catch (e: Exception) {}
                try {
                    if (lockFile.exists()) lockFile.delete()
                } catch (e: Exception) {}
            }
        }
    }
}
