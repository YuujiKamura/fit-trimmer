import java.io.File
import java.awt.image.BufferedImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import fit.HudConfig
import fit.DynamicRendererProxy
import fit.NativeHudEncoder
import fit.HudSettings
import fit.FitParser
import fit.PlateCacheManager
import fit.VideoPlatesCache
import utils.PlateDetectionManager


typealias PlatePreScanner = suspend (
    videoPath: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    adjustedStartUtc: String,
    onProgress: (Float) -> Unit,
    onCancel: () -> Boolean,
    settings: HudSettings
) -> VideoPlatesCache?
object HudEncodePipeline {


    private val defaultPlatePreScanner: PlatePreScanner = { videoPath, telemetryPoints, adjustedStartUtc, onProgress, onCancel, settings ->
        PlateDetectionManager.runDetection(
            videoPath = videoPath,
            telemetryPoints = telemetryPoints,
            adjustedStartUtc = adjustedStartUtc,
            onProgress = onProgress,
            onCancel = onCancel,
            saveCache = true,
            settings = settings
        )
    }

    suspend fun ensurePlateCacheForEncode(
        settings: HudSettings,
        videoPath: String,
        telemetryPoints: List<FitParser.TelemetryPoint> = emptyList(),
        adjustedStartUtc: String = "",
        onProgress: (progress: Float, statusText: String) -> Unit = { _, _ -> },
        cancelSupplier: () -> Boolean = { false },
        platePreScanner: PlatePreScanner = defaultPlatePreScanner
    ): VideoPlatesCache? {
        if (!settings.blurLicensePlates || videoPath.isEmpty()) {
            return PlateCacheManager.loadCache(videoPath)
        }

        PlateCacheManager.loadCache(videoPath)?.let { return it }
        if (cancelSupplier()) throw Exception("Encoding Canceled")

        onProgress(0f, "Scanning license plates before encoding...")
        val cache = platePreScanner(
            videoPath,
            telemetryPoints,
            adjustedStartUtc,
            { percent ->
                onProgress(0f, "Scanning license plates before encoding: ${"%.1f".format(java.util.Locale.US, percent)}%")
            },
            cancelSupplier,
            settings
        )

        if (cancelSupplier()) throw Exception("Encoding Canceled")
        if (cache == null) {
            throw Exception("License plate blur is enabled, but plate scan cache could not be created.")
        }

        PlateCacheManager.saveCache(videoPath, cache)
        onProgress(0f, "Plate scan complete. Starting encode...")
        return cache
    }
    suspend fun execute(
        s: HudSettings,
        fitPath: String,
        videoPath: String,
        outputDir: String,
        videoStartUtc: String,
        ranges: List<Pair<Double, Double>>,
        destFiles: List<File>,
        isSample: Boolean = false,
        shouldResume: Boolean = false,
        moveOutputToSource: Boolean = false,
        plateTelemetryPoints: List<FitParser.TelemetryPoint> = emptyList(),
        platePreScanner: PlatePreScanner = defaultPlatePreScanner,
        onProgress: (progress: Float, statusText: String) -> Unit,
        onFrame: (BufferedImage) -> Unit,
        pauseSupplier: () -> Boolean,
        cancelSupplier: () -> Boolean,
        showLivePreviewSupplier: () -> Boolean,
        onSegmentStart: (start: Double, end: Double) -> Unit = { _, _ -> }
    ): String {
        return withContext(Dispatchers.IO) {
            val config = HudConfig(
                valSize = s.valSize, tightness = s.tightness, spacing = s.spacing,
                xOffset = s.xOffset, yOffset = s.yOffset, graphH = s.graphH, graphW = s.graphW,
                captionPosition = s.captionPosition,
                roadCaptions = s.roadCaptions,
                powerTrendSpanSeconds = s.powerTrendSpanSeconds,
                useImperialUnits = s.useImperialUnits,
                language = s.language
            )
            val proxy = DynamicRendererProxy(config)
            globalRendererProxy = proxy

            val encodePlan = buildEncodePlan(
                settings = s,
                videoPath = videoPath,
                outputDir = outputDir,
                moveOutputToSource = moveOutputToSource,
                ranges = ranges,
                isSample = isSample
            )

            val totalDuration = encodePlan.totalDurationSeconds
            ensurePlateCacheForEncode(
                settings = s,
                videoPath = videoPath,
                telemetryPoints = plateTelemetryPoints,
                adjustedStartUtc = videoStartUtc,
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

                val finalDestFile = destFiles.getOrNull(idx)
                if (shouldResume && finalDestFile != null && finalDestFile.exists() && finalDestFile.length() > 0L) {
                    println("DEBUG: Segment ${idx + 1} already finished. Skipping. File: ${finalDestFile.absolutePath}")
                    completedDuration += partDuration
                    finalOutPath = finalDestFile.absolutePath
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
                    pauseSupplier = pauseSupplier,
                    cancelSupplier = cancelSupplier,
                    customRenderer = { canvas, point, allPoints, pBuf, progressRatio ->
                        proxy.renderFrame(canvas, point, allPoints, pBuf, progressRatio)
                    },
                    showLivePreviewSupplier = showLivePreviewSupplier
                )

                val outputFileName = buildEncodeOutputFileName(
                    settings = s,
                    videoPath = videoPath,
                    partIndex = if (isSample) -1 else idx,
                    numParts = encodePlan.segments.size,
                    isSample = isSample
                )
                val partOutPath = File(outputDir, outputFileName).absolutePath

                encoder.encode(fitPath, videoPath, partOutPath, videoStartUtc,
                    maxDurationSeconds = if (isSample) 5 else -1,
                    trimStartSeconds = pStart,
                    trimEndSeconds = pEnd,
                    shouldResume = shouldResume
                )

                if (destFiles.isNotEmpty() && !cancelSupplier()) {
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
        }
    }
}
