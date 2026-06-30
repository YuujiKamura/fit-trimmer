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
import viewmodel.AppViewModel

object HudEncodePipeline {

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
                roadCaptions = s.roadCaptions
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
                    trimEndSeconds = pEnd
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
