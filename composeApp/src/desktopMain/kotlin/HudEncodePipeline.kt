import fit.TelemetryPoint
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
    telemetryPoints: List<TelemetryPoint>,
    adjustedStartUtc: String,
    onProgress: (Float, String) -> Unit,
    onCancel: () -> Boolean,
    settings: HudSettings,
    scanRanges: List<Pair<Double, Double>>?
) -> VideoPlatesCache?

object HudEncodePipeline {

    var hudEncoderFactory: fit.HudEncoderFactory = fit.NativeHudEncoder.Companion

    private val defaultPlatePreScanner: PlatePreScanner = { videoPath, telemetryPoints, adjustedStartUtc, onProgress, onCancel, settings, scanRanges ->
        PlateDetectionManager.detect(
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
        telemetryPoints: List<TelemetryPoint> = emptyList(),
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
        imuTimeOffsetMillis: Long? = null,
        ranges: List<Pair<Double, Double>>,
        destFiles: List<File>,
        shouldResume: Boolean = false,
        moveOutputToSource: Boolean = false,
        plateTelemetryPoints: List<TelemetryPoint> = emptyList(),
        platePreScanner: PlatePreScanner = defaultPlatePreScanner,
        onProgress: (progress: Float, statusText: String) -> Unit,
        onFrame: (BufferedImage) -> Unit,
        cancelSupplier: () -> Boolean,
        showLivePreviewSupplier: () -> Boolean,
        earlyFinishSupplier: () -> Boolean = { false },
        onSegmentStart: (start: Double, end: Double) -> Unit = { _, _ -> },
        skipConcat: Boolean = false,
        mergeOutputFile: File? = null,
        hudTelemetryRange: Pair<Double, Double>? = null
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
                mapRangeMode = s.mapRangeMode,
                textShadowAlpha = s.textShadowAlpha,
                showCumulativeDistanceTime = s.showCumulativeDistanceTime,
                showAnimatedIcons = s.showAnimatedIcons
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
                if (earlyFinishSupplier()) {
                    println("DEBUG: Early finish requested. Breaking encoding loop to merge current progress.")
                    break
                }
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

                val encoder = hudEncoderFactory.create(s,
                    onProgress = { prog, status ->
                        val segmentProgress = prog.toDouble()
                        val overallProg = if (totalDuration > 0.0) {
                            (completedDuration + segmentProgress * partDuration) / totalDuration
                        } else 0.0
                        onProgress(overallProg.toFloat(), "[Part ${idx + 1}/${ranges.size}] $status")
                    },
                    onFrameRendered = { img -> if (img is java.awt.image.BufferedImage) onFrame(img) },
                    cancelSupplier = { cancelSupplier() || earlyFinishSupplier() },
                    customRenderer = { canvas, point, allPoints, trimmedPoints, pBuf, progressRatio ->
                        proxy.renderFrame(canvas, point, allPoints, trimmedPoints, pBuf, progressRatio)
                    },
                    showLivePreviewSupplier = showLivePreviewSupplier
                )

                try {
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
                            timeOffsetMillis = timeOffsetMillis,
                            imuTimeOffsetMillis = imuTimeOffsetMillis
                        ),
                        hudTelemetryStartSeconds = hudTelemetryRange?.first,
                        hudTelemetryEndSeconds = hudTelemetryRange?.second
                    )
                } catch (e: Exception) {
                    if (earlyFinishSupplier()) {
                        println("DEBUG: Encoding interrupted by early finish request. Proceeding to merge completed segments.")
                        if (finalDestFile != null) {
                            val outFile = File(partOutPath)
                            if (outFile.exists() && outFile.length() > 0L) {
                                println("SALVAGE: Moving incomplete early finish part from ${outFile.absolutePath} to ${finalDestFile.absolutePath} (size: ${outFile.length()} bytes)")
                                try {
                                    java.nio.file.Files.copy(outFile.toPath(), finalDestFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                    outFile.delete()
                                } catch(ex: Exception) {
                                    println("SALVAGE ERROR: ${ex.message}")
                                }
                            }
                        }
                        break
                    } else {
                        throw e
                    }
                }

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

            if (earlyFinishSupplier()) {
                for (segment in encodePlan.segments) {
                    val idx = segment.index
                    val outputFileName = buildEncodeOutputFileName(
                        settings = s,
                        videoPath = videoPath,
                        partIndex = idx,
                        numParts = encodePlan.segments.size
                    )
                    val partOutFile = File(outputDir, outputFileName)
                    val finalDestFile = destFiles.getOrNull(idx)
                    if (partOutFile.exists() && partOutFile.length() > 0L && finalDestFile != null && (!finalDestFile.exists() || finalDestFile.length() == 0L)) {
                        println("SALVAGE-LOOP: Rescuing segment $idx from ${partOutFile.absolutePath} to ${finalDestFile.absolutePath}")
                        try {
                            java.nio.file.Files.copy(partOutFile.toPath(), finalDestFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            partOutFile.delete()
                        } catch (e: Exception) {
                            println("SALVAGE-LOOP ERROR: ${e.message}")
                        }
                    }
                }
            }

            val isEarlyFinish = earlyFinishSupplier()
            val finalMergeFile = if (isEarlyFinish && mergeOutputFile != null) {
                val baseDir = mergeOutputFile.parentFile
                val baseName = mergeOutputFile.nameWithoutExtension
                val ext = mergeOutputFile.extension
                File(baseDir, "${baseName}_part.${ext}")
            } else {
                mergeOutputFile
            }

            if (finalMergeFile != null) {
                val completedParts = destFiles.filter { it.exists() && it.length() > 0L }
                if (completedParts.isNotEmpty()) {
                    onProgress(1.0f, if (isEarlyFinish) "Exporting current progress..." else "Merging cut segments...")
                    if (completedParts.size == 1) {
                        try {
                            java.nio.file.Files.copy(
                                completedParts.first().toPath(),
                                finalMergeFile.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (e: Exception) {
                            println("ERROR copying single segment: ${e.message}")
                            throw e
                        }
                    } else {
                        mergeEncodedSegments(completedParts, finalMergeFile)
                    }
                    finalOutPath = finalMergeFile.absolutePath
                }

                if (!isEarlyFinish) {
                    destFiles
                        .filter { it.absolutePath != finalMergeFile.absolutePath }
                        .forEach { it.delete() }
                } else {
                    println("DEBUG: Preserving segment files for future resume since early finish was requested.")
                }
            }

            if (isEarlyFinish) {
                val exportedName = finalMergeFile?.name ?: "video"
                "\u23F8 Intermediate progress exported as '$exportedName'. You can resume later."
            } else if (hasCloudSyncMsg) {
                "\u2728 Copied to Cloud. Drive Desktop is syncing in background (Check system tray)."
            } else {
                "\u2728 Finished Successfully!"
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

    private fun mergeEncodedSegments(segmentFiles: List<File>, outputFile: File) {
        val existingSegments = segmentFiles.filter { it.exists() && it.length() > 0L }
        if (existingSegments.isEmpty()) {
            throw Exception("No encoded cut segments were produced.")
        }
        outputFile.parentFile?.mkdirs()
        if (existingSegments.size == 1) {
            Files.copy(existingSegments.first().toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }

        val ffmpegPath = fit.findFfmpegPath()
        val listFile = File(outputFile.parentFile ?: File("."), outputFile.nameWithoutExtension + "_concat.txt")
        listFile.writeText(
            existingSegments.joinToString(System.lineSeparator()) { file ->
                "file '${file.absolutePath.replace("\\", "/").replace("'", "'\\''")}'"
            },
            Charsets.UTF_8
        )
        try {
            val pb = ProcessBuilder(
                ffmpegPath, "-y",
                "-fflags", "+genpts",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.absolutePath,
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                outputFile.absolutePath
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Exception("Failed to merge cut segments. ffmpeg exited with code $exitCode.\n$output")
            }
        } finally {
            listFile.delete()
        }
    }
}
