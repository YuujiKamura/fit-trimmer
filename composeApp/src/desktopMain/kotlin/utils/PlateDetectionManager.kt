package utils

import fit.PlateBox
import fit.PlateRecord
import fit.PlateScanRange
import fit.VideoPlatesCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale

object PlateDetectionManager : fit.PlateDetector {

    override suspend fun detect(
        videoPath: String,
        telemetryPoints: List<fit.TelemetryPoint>,
        adjustedStartUtc: String,
        onProgress: (Float, String) -> Unit,
        onCancel: () -> Boolean,
        onPartialResult: (VideoPlatesCache) -> Unit,
        maxRecords: Int?,
        saveCache: Boolean,
        settings: fit.HudSettings,
        scanRanges: List<Pair<Double, Double>>?
    ): VideoPlatesCache? = withContext(Dispatchers.IO) {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return@withContext null

        val existingCache = if (saveCache) fit.PlateCacheManager.loadCache(videoPath) else null

        // 1. Get video metadata using ffmpeg -i
        val pbInfo = ProcessBuilder(ffmpegPath, "-i", videoPath)
        pbInfo.redirectErrorStream(true)
        val pInfo = pbInfo.start()
        val outputInfo = pInfo.inputStream.bufferedReader().readText()
        pInfo.waitFor()

        var videoWidth = 1920
        var videoHeight = 1080
        var durationSec = 100.0

        // Parse resolution (e.g., "1920x1080")
        val resMatch = Regex("""\b(\d{3,4})x(\d{3,4})\b""").find(outputInfo)
        if (resMatch != null) {
            videoWidth = resMatch.groupValues[1].toInt()
            videoHeight = resMatch.groupValues[2].toInt()
        }

        // Parse rotation from ffmpeg output (e.g. 'rotate : 90' or 'rotation of -90.00 degrees')
        val rotationMatch = Regex("""(?:rotation of|rotate\s*:)\s*(-?\d+)""").find(outputInfo)
        val rotationVal = rotationMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val isRotated90Or270 = rotationVal == 90 || rotationVal == -90 || rotationVal == 270 || rotationVal == -270
        if (isRotated90Or270) {
            val temp = videoWidth
            videoWidth = videoHeight
            videoHeight = temp
        }

        // Parse duration: "Duration: 00:01:23.45"
        val durMatch = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""").find(outputInfo)
        if (durMatch != null) {
            val h = durMatch.groupValues[1].toDouble()
            val m = durMatch.groupValues[2].toDouble()
            val s = durMatch.groupValues[3].toDouble()
            val ms = durMatch.groupValues[4].toDouble() / 100.0
            durationSec = h * 3600.0 + m * 60.0 + s + ms
        }

        val baseDetectionFps = settings.plateDetectionFps.coerceIn(0.25, 4.0)
        // Multiplier of 3x for active dynamic tracking, capped at 4.0fps to prevent extreme decoding overhead
        val effectiveDetectionFps = (baseDetectionFps * 3.0).coerceAtMost(4.0)
        
        // Downscale to 1280x720 maximum to avoid memory bloat and pipe blocking
        val maxScanDim = 1280
        val (scanWidth, scanHeight) = if (videoWidth > maxScanDim || videoHeight > maxScanDim) {
            val scaleFactor = maxScanDim.toFloat() / maxOf(videoWidth, videoHeight)
            ((videoWidth * scaleFactor).toInt() and -2) to ((videoHeight * scaleFactor).toInt() and -2)
        } else {
            videoWidth to videoHeight
        }
        val filterChain = "scale=$scanWidth:$scanHeight,fps=$effectiveDetectionFps"
        val frameBytes = scanWidth * scanHeight * 3 // RGB24
        
        val normalizedScanRanges = (scanRanges ?: listOf(0.0 to durationSec))
            .map { (start, end) ->
                val s = start.coerceIn(0.0, durationSec)
                val e = end.coerceIn(0.0, durationSec)
                s to e
            }
            .filter { it.second > it.first }
            .ifEmpty { listOf(0.0 to durationSec) }

        val records = mutableListOf<PlateRecord>()
        val detector = PlateDetector.getInstance()
        detector.resetPerfStats()

        val totalDurationToScan = normalizedScanRanges.sumOf { it.second - it.first }
        val totalEstimatedFrames = (totalDurationToScan * effectiveDetectionFps).toLong().coerceAtLeast(1L)
        var processedFramesCount = 0L

        val tempWorkDir = File("temp_work")
        if (!tempWorkDir.exists()) tempWorkDir.mkdirs()
        val scanErrorLog = File(tempWorkDir, "scan_ffmpeg_error.log")
        
        val img = BufferedImage(scanWidth, scanHeight, BufferedImage.TYPE_3BYTE_BGR)
        val imgData = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data

        for ((startSec, endSec) in normalizedScanRanges) {
            if (onCancel()) break

            val durSec = endSec - startSec
            val pb = ProcessBuilder(
                ffmpegPath,
                "-threads", "0",
                "-ss", String.format(Locale.US, "%.3f", startSec),
                "-t", String.format(Locale.US, "%.3f", durSec),
                "-i", videoPath,
                "-vf", filterChain,
                "-f", "rawvideo",
                "-pix_fmt", "bgr24",
                "-vcodec", "rawvideo",
                "pipe:1"
            )
            pb.redirectErrorStream(false)
            pb.redirectError(ProcessBuilder.Redirect.to(scanErrorLog))

            val proc = pb.start()
            val stream = BufferedInputStream(proc.inputStream, 1024 * 1024)
            val segmentFrames = (durSec * effectiveDetectionFps).toInt().coerceAtLeast(1)

            try {
                for (i in 0 until segmentFrames) {
                    if (onCancel()) break

                    val frameBuffer = ByteArray(frameBytes)
                    var bytesRead = 0
                    while (bytesRead < frameBytes) {
                        val read = stream.read(frameBuffer, bytesRead, frameBytes - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }
                    if (bytesRead < frameBytes) {
                        break // Unexpected EOF
                    }

                    System.arraycopy(frameBuffer, 0, imgData, 0, frameBytes)

                    val timeMs = ((startSec + i / effectiveDetectionFps) * 1000.0).toLong()
                    
                    // Skip inference if this timeMs is already within the existing cached scan ranges
                    val isCached = existingCache?.scanRanges?.any { timeMs in it.startMs..it.endMs } == true
                    if (isCached) {
                        val cachedRecord = existingCache?.records?.find { it.timeMs == timeMs }
                        if (cachedRecord != null) {
                            records.add(cachedRecord)
                        }
                    } else {
                        val detectedBoxes = detector.detect(
                            img,
                            confThreshold = 0.25f,
                            detectPedestrians = settings.detectPedestrians,
                            maskMode = settings.plateMaskMode
                        )
                        if (detectedBoxes.isNotEmpty()) {
                            val scaleX = videoWidth.toFloat() / scanWidth.toFloat()
                            val scaleY = videoHeight.toFloat() / scanHeight.toFloat()
                            val scaledBoxes = detectedBoxes.map { box ->
                                fit.PlateBox(
                                    x1 = (box.x1 * scaleX).toInt(),
                                    y1 = (box.y1 * scaleY).toInt(),
                                    x2 = (box.x2 * scaleX).toInt(),
                                    y2 = (box.y2 * scaleY).toInt()
                                )
                            }
                            val filteredBoxes = filterBoxesForMaskSize(scaledBoxes, videoHeight, settings)
                            if (filteredBoxes.isNotEmpty()) {
                                val mergedBoxes = mergeOverlappingBoxes(filteredBoxes)
                                records.add(PlateRecord(timeMs, mergedBoxes))
                            }
                        }
                    }

                    processedFramesCount++
                    val progressPercent = processedFramesCount.toFloat() / totalEstimatedFrames.toFloat()
                    onProgress(progressPercent.coerceIn(0f, 1f), "Scanning plates: ${(progressPercent * 100).toInt()}%")

                    // Trigger periodic partial result updates to populate the telemetry timeline UI
                    if (processedFramesCount % 3L == 0L) {
                        onPartialResult(
                            VideoPlatesCache(
                                videoPath = videoPath,
                                sourceWidth = videoWidth,
                                sourceHeight = videoHeight,
                                records = records.toList(),
                                scanRanges = normalizedScanRanges.map { PlateScanRange((it.first * 1000.0).toLong(), (it.second * 1000.0).toLong()) }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { stream.close() } catch (e: Exception) {}
                try { proc.destroyForcibly() } catch (e: Exception) {}
            }
        }

        val rawCache = VideoPlatesCache(
            videoPath = videoPath,
            sourceWidth = videoWidth,
            sourceHeight = videoHeight,
            records = records,
            scanRanges = normalizedScanRanges.map { PlateScanRange((it.first * 1000.0).toLong(), (it.second * 1000.0).toLong()) }
        )

        val finalCache = if (existingCache != null) existingCache.mergedWith(rawCache) else rawCache

        if (saveCache) {
            fit.PlateCacheManager.saveCache(videoPath, finalCache)
        }
        finalCache
    }

    private fun findFfmpegPath(): String {
        return fit.findFfmpegPath()
    }

    internal fun filterBoxesForMaskSize(
        boxes: List<fit.PlateBox>,
        videoHeight: Int,
        settings: fit.HudSettings
    ): List<fit.PlateBox> {
        val minHeight = (videoHeight.coerceAtLeast(1) * settings.plateMinMaskHeightRatio.coerceAtLeast(0.0))
            .coerceAtLeast(1.0)
        return boxes.filter { box ->
            (box.y2 - box.y1).coerceAtLeast(0) >= minHeight
        }
    }

    fun mergeOverlappingBoxes(boxes: List<fit.PlateBox>): List<fit.PlateBox> {
        if (boxes.size <= 1) return boxes
        
        fun intersects(a: fit.PlateBox, b: fit.PlateBox): Boolean {
            return !(a.x2 < b.x1 || a.x1 > b.x2 || a.y2 < b.y1 || a.y1 > b.y2)
        }

        fun merge(a: fit.PlateBox, b: fit.PlateBox): fit.PlateBox {
            return fit.PlateBox(
                x1 = kotlin.math.min(a.x1, b.x1),
                y1 = kotlin.math.min(a.y1, b.y1),
                x2 = kotlin.math.max(a.x2, b.x2),
                y2 = kotlin.math.max(a.y2, b.y2)
            )
        }

        val result = boxes.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < result.size) {
                var j = i + 1
                while (j < result.size) {
                    if (intersects(result[i], result[j])) {
                        val mergedBox = merge(result[i], result[j])
                        result[i] = mergedBox
                        result.removeAt(j)
                        merged = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return result
    }
}
