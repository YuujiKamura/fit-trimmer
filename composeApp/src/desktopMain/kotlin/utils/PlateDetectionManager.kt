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

    val trackingLogs = java.util.concurrent.ConcurrentLinkedQueue<String>()

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
        trackingLogs.clear()
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

        val baseDetectionFps = settings.plateDetectionFps.coerceIn(0.25, 60.0)
        // Multiplier of 3x for active dynamic tracking, capped at 60.0fps to allow smooth full-frame encoding
        val effectiveDetectionFps = (baseDetectionFps * 3.0).coerceAtMost(60.0)
        
        // Downscale limit depending on mask mode: 1280 for vehicle (640 model) to maintain processing efficiency.
        // For plate mask mode, bypass intermediate downscaling and decode at the original video resolution
        // (e.g. 4K) to preserve high-fidelity plate details for direct 1088px model preprocessing.
        val isVehicleMode = !settings.plateMaskMode.startsWith("plate", ignoreCase = true)
        val (scanWidth, scanHeight) = if (isVehicleMode) {
            val maxScanDim = 1280
            if (videoWidth > maxScanDim || videoHeight > maxScanDim) {
                val scaleFactor = maxScanDim.toFloat() / maxOf(videoWidth, videoHeight)
                ((videoWidth * scaleFactor).toInt() and -2) to ((videoHeight * scaleFactor).toInt() and -2)
            } else {
                videoWidth to videoHeight
            }
        } else {
            videoWidth to videoHeight
        }
        
        val filterChain = if (scanWidth == videoWidth && scanHeight == videoHeight) {
            "fps=$effectiveDetectionFps"
        } else {
            "scale=$scanWidth:$scanHeight,fps=$effectiveDetectionFps"
        }
        val frameBytes = scanWidth * scanHeight * 3 // RGB24
        
        val normalizedScanRanges = (scanRanges ?: listOf(0.0 to durationSec))
            .map { (start, end) ->
                val s = start.coerceIn(0.0, durationSec)
                val e = end.coerceIn(0.0, durationSec)
                s to e
            }
            .filter { it.second > it.first }
            .ifEmpty { listOf(0.0 to durationSec) }

        val tracker = CascadePlateTracker()
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
                        val isCascadeMode = settings.plateMaskMode.equals("plate_cascade", ignoreCase = true)
                        val detectedBoxes = if (isCascadeMode) {
                            detector.detectCascaded(
                                image = img,
                                confThreshold = 0.25f,
                                detectPedestrians = settings.detectPedestrians,
                                tracker = tracker,
                                timeMs = timeMs
                            )
                        } else {
                            // If it's "plate" mode, we use direct fast mode. 
                            val internalMaskMode = if (settings.plateMaskMode.equals("plate", ignoreCase = true)) "plate_direct" else settings.plateMaskMode
                            detector.detect(
                                image = img,
                                confThreshold = 0.25f,
                                detectPedestrians = settings.detectPedestrians,
                                maskMode = internalMaskMode
                            )
                        }
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
                    val timeSec = processedFramesCount.toDouble() / effectiveDetectionFps
                    val timeStr = String.format(java.util.Locale.US, "%.1f", timeSec)
                    onProgress(progressPercent.coerceIn(0f, 1f), "Scan: $processedFramesCount / $totalEstimatedFrames frames (${timeStr}s)")

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

        val finalCache = (if (existingCache != null) existingCache.mergedWith(rawCache) else rawCache).smoothed(alpha = 0.3f)

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

    fun extractSingleFrame(videoPath: String, timeMs: Long, targetWidth: Int = 1280, targetHeight: Int = 720): BufferedImage? {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val startSec = timeMs / 1000.0
        val pb = ProcessBuilder(
            ffmpegPath,
            "-ss", String.format(Locale.US, "%.3f", startSec),
            "-i", videoPath,
            "-vf", "scale=$targetWidth:$targetHeight",
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "rawvideo",
            "-pix_fmt", "rgb24",
            "-"
        )
        val p = pb.start()
        val stream = BufferedInputStream(p.inputStream)
        val frameBytes = targetWidth * targetHeight * 3
        val buffer = ByteArray(frameBytes)
        var bytesRead = 0
        try {
            while (bytesRead < frameBytes) {
                val r = stream.read(buffer, bytesRead, frameBytes - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
        } catch (e: Exception) {
            p.destroy()
            return null
        }
        p.destroy()

        if (bytesRead < frameBytes) return null

        val img = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR)
        val dataBuffer = img.raster.dataBuffer as java.awt.image.DataBufferByte
        val outData = dataBuffer.data
        for (i in 0 until targetWidth * targetHeight) {
            val r = buffer[i * 3]
            val g = buffer[i * 3 + 1]
            val b = buffer[i * 3 + 2]
            outData[i * 3] = b
            outData[i * 3 + 1] = g
            outData[i * 3 + 2] = r
        }
        return img
    }

    fun getVideoResolution(videoPath: String): Pair<Int, Int> {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val pbInfo = ProcessBuilder(ffmpegPath, "-i", videoPath)
        pbInfo.redirectErrorStream(true)
        val pInfo = pbInfo.start()
        val outputInfo = pInfo.inputStream.bufferedReader().readText()
        pInfo.waitFor()

        var videoWidth = 1920
        var videoHeight = 1080

        val resMatch = Regex("""\b(\d{3,5})x(\d{3,5})\b""").find(outputInfo)
        if (resMatch != null) {
            videoWidth = resMatch.groupValues[1].toInt()
            videoHeight = resMatch.groupValues[2].toInt()
        }
        
        val rotationMatch = Regex("""(?:rotation of|rotate\s*:)\s*(-?\d+)""").find(outputInfo)
        val rotationVal = rotationMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val isRotated90Or270 = rotationVal == 90 || rotationVal == -90 || rotationVal == 270 || rotationVal == -270
        if (isRotated90Or270) {
            return videoHeight to videoWidth
        }
        return videoWidth to videoHeight
    }
}
