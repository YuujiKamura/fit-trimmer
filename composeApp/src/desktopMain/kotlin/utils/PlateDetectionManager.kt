package utils

import fit.findFfmpegPath
import fit.VideoPlatesCache
import fit.PlateRecord
import fit.PlateCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.awt.image.BufferedImage
import java.io.BufferedInputStream

private data class DecodedFrame(
    val frameIndex: Long,
    val timeMs: Long,
    val buffer: ByteArray,
    val skipDetection: Boolean,
    val decodeMs: Double
)

object PlateDetectionManager {

    suspend fun runDetection(
        videoPath: String,
        telemetryPoints: List<fit.FitParser.TelemetryPoint> = emptyList(),
        adjustedStartUtc: String = "",
        onProgress: (Float) -> Unit,
        onCancel: () -> Boolean
    ): VideoPlatesCache? = withContext(Dispatchers.IO) {
        val ffmpegPath = findFfmpegPath()
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return@withContext null

        var localVideoPath = videoPath
        var tempLocalVideo: File? = null

        // Google Drive mitigation: pre-copy video locally to avoid network read bottleneck
        if (isGoogleDrivePath(videoPath)) {
            val workDir = fit.PathResolver.getTempWorkDir(videoPath)
            if (!workDir.exists()) workDir.mkdirs()
            val jobHash = kotlin.math.abs(videoPath.hashCode()).toString()
            val jobDir = File(workDir, "scan_$jobHash")
            if (!jobDir.exists()) jobDir.mkdirs()
            
            val tempFile = File(jobDir, "temp_scan_input.mp4")
            if (!tempFile.exists() || tempFile.length() == 0L) {
                println("⚠️ Cloud drive path detected during plate scan: $videoPath")
                println("📥 Pre-copying video to local temp file to avoid network bottlenecks: ${tempFile.absolutePath}")
                
                try {
                    val pbCopy = ProcessBuilder(
                        ffmpegPath,
                        "-y",
                        "-i", videoPath,
                        "-c", "copy",
                        tempFile.absolutePath
                    )
                    pbCopy.redirectError(ProcessBuilder.Redirect.DISCARD)
                    val pCopy = pbCopy.start()
                    
                    val cancelMonitor = launch {
                        while (pCopy.isAlive) {
                            if (onCancel()) {
                                pCopy.destroy()
                                try { pCopy.destroyForcibly() } catch (e: Exception) {}
                                break
                            }
                            kotlinx.coroutines.delay(100)
                        }
                    }
                    val exitCode = pCopy.waitFor()
                    cancelMonitor.cancel()
                    
                    if (onCancel() || exitCode != 0) {
                        try { tempFile.delete() } catch (e: Exception) {}
                        if (onCancel()) {
                            println("DEBUG: Scan canceled during local copy.")
                            return@withContext null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                localVideoPath = tempFile.absolutePath
                tempLocalVideo = tempFile
            }
        }

        // 1. Get video metadata using ffmpeg -i
        val pbInfo = ProcessBuilder(ffmpegPath, "-i", localVideoPath)
        pbInfo.redirectErrorStream(true)
        val pInfo = pbInfo.start()
        val outputInfo = pInfo.inputStream.bufferedReader().readText()
        pInfo.waitFor()

        var videoWidth = 1920
        var videoHeight = 1080
        var durationSec = 100.0
        var videoFps = 30.0
        var videoRotation = 0

        // Parse resolution (e.g., "1920x1080")
        val resMatch = Regex("""\b(\d{3,4})x(\d{3,4})\b""").find(outputInfo)
        if (resMatch != null) {
            videoWidth = resMatch.groupValues[1].toInt()
            videoHeight = resMatch.groupValues[2].toInt()
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

        // Parse FPS
        val fpsMatch = Regex("""([\d\.]+)\s*fps""").find(outputInfo)
        if (fpsMatch != null) {
            videoFps = fpsMatch.groupValues[1].toDouble()
        }

        // To speed up detection without losing tracking accuracy, we decimate frames to 5 fps.
        // Since we have a 1500ms cache padding threshold, 5 fps (200ms intervals) is more than
        // enough to maintain seamless license plate blurring while cutting processing time in half.
        val detectionFps = 5.0
        val scanWidth = 640
        val scanHeight = 640

        // Build filter chain. Inserting "scale=iw:ih" at the very beginning forces FFmpeg's
        // automatic rotation (autorotate) filter to run BEFORE the "fps" filter strips rotation metadata.
        val filterChain = "scale=$scanWidth:$scanHeight:out_range=full,fps=$detectionFps"
        
        val frameBytes = scanWidth * scanHeight * 3 // RGB24
        
        val pb = ProcessBuilder(
            ffmpegPath,
            "-i", localVideoPath,
            "-vf", filterChain,
            "-f", "rawvideo",
            "-pix_fmt", "bgr24", // Use bgr24 for fast native image buffer copy
            "-vcodec", "rawvideo",
            "pipe:1"
        )
        val tempWorkDir = File("temp_work")
        if (!tempWorkDir.exists()) tempWorkDir.mkdirs()
        val scanErrorLog = File(tempWorkDir, "scan_ffmpeg_error.log")
        pb.redirectErrorStream(false)
        pb.redirectError(ProcessBuilder.Redirect.to(scanErrorLog))
        val process = pb.start()

        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancellationHandler = job?.invokeOnCompletion {
            if (job.isCancelled) {
                println("DEBUG: Scanning coroutine cancelled. Force destroying FFmpeg process...")
                try {
                    process.destroyForcibly()
                } catch (e: Exception) {}
            }
        }
 
        val startTimeAdjusted = try {
            if (adjustedStartUtc.isNotEmpty()) {
                val clean = adjustedStartUtc.trim().replace(" ", "T")
                try {
                    java.time.ZonedDateTime.parse(clean)
                } catch (e: Exception) {
                    try {
                        java.time.LocalDateTime.parse(clean).atZone(java.time.ZoneId.of("UTC"))
                    } catch (e2: Exception) {
                        try {
                            java.time.OffsetDateTime.parse(clean).toZonedDateTime()
                        } catch (e3: Exception) {
                            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                            java.time.LocalDateTime.parse(clean, fmt).atZone(java.time.ZoneId.of("UTC"))
                        }
                    }
                }
            } else null
        } catch (e: Exception) {
            println("DEBUG: Failed to parse adjustedStartUtc '$adjustedStartUtc': ${e.message}")
            null
        }

        if (startTimeAdjusted != null) {
            val fitEpoch = 631065600L
            val videoStartFitTs = startTimeAdjusted.toEpochSecond() - fitEpoch
            val fitStart = telemetryPoints.firstOrNull()?.timestamp ?: 0.0
            val fitEnd = telemetryPoints.lastOrNull()?.timestamp ?: 0.0
            val diffStart = videoStartFitTs - fitStart
            println("DEBUG: Speed filter ACTIVE.")
            println("  - Video start UTC: $startTimeAdjusted")
            println("  - Video start FIT Ts (1990 epoch): $videoStartFitTs")
            println("  - FIT range (1990 epoch): $fitStart to $fitEnd")
            println("  - Time diff (VideoStart - FITStart): $diffStart seconds (${String.format(java.util.Locale.US, "%.2f", diffStart / 3600.0)} hours)")
            println("  - Telemetry points count: ${telemetryPoints.size}")
            
            val totalFrames = (durationSec * detectionFps).toLong()
            var estimatedTargetFrames = 0L
            if (telemetryPoints.isNotEmpty()) {
                val fStart = telemetryPoints.first().timestamp
                val fEnd = telemetryPoints.last().timestamp
                for (f in 0 until totalFrames) {
                    val timeMs = (f * 1000.0 / detectionFps).toLong()
                    val currentSec = timeMs.toDouble() / 1000.0
                    val currentUtcSeconds = startTimeAdjusted.toEpochSecond() + currentSec
                    val currentFitTs = currentUtcSeconds - fitEpoch
                    
                    if (currentFitTs in fStart..fEnd) {
                        val point = findClosestTelemetryPoint(telemetryPoints, currentFitTs)
                        if (point == null || point.speed < 10.0) {
                            estimatedTargetFrames++
                        }
                    } else {
                        estimatedTargetFrames++
                    }
                }
            } else {
                estimatedTargetFrames = totalFrames
            }
            val skipped = totalFrames - estimatedTargetFrames
            val ratio = if (totalFrames > 0) skipped.toFloat() / totalFrames.toFloat() * 100f else 0f
            println("  - Pre-scan Estimation:")
            println("    - Total video frames to read: $totalFrames")
            println("    - AI (YOLO) scan target frames (<10km/h): $estimatedTargetFrames")
            println("    - Skipped frames (>=10km/h): $skipped (${String.format(java.util.Locale.US, "%.1f", ratio)}% reduction)")
        } else {
            println("WARNING: Speed filter INACTIVE. Video start UTC '$adjustedStartUtc' could not be parsed.")
        }

        val records = mutableListOf<PlateRecord>()
        val detector = PlateDetector.getInstance()
        detector.resetPerfStats()
        
        val totalFrames = (durationSec * detectionFps).toLong()
        var frameIndex = 0L
        var skippedFrames = 0L
        val startEpochSecond = startTimeAdjusted?.toEpochSecond() ?: 0L
        var lastProgressPercent = 0.0f
        val scanStartTimeMs = System.currentTimeMillis()
        var totalDecodeMs = 0.0
        var totalYoloMs = 0.0
        var yoloCount = 0L
 
        val img = BufferedImage(scanWidth, scanHeight, BufferedImage.TYPE_3BYTE_BGR)
        val imgData = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data

        // Channel for parallel pipelined decoding and inference.
        // Bounded capacity prevents high memory usage while maintaining pre-buffered frames.
        val frameChannel = Channel<DecodedFrame>(capacity = 16)

        // Background Producer: Decodes raw frames from FFmpeg as fast as possible
        val decoderJob = launch(Dispatchers.IO) {
            val stream = BufferedInputStream(process.inputStream, 1024 * 1024)
            val fitEpoch = 631065600L
            var localFrameIndex = 0L
            try {
                while (isActive && !onCancel()) {
                    val tReadStart = System.nanoTime()
                    val frameBuffer = ByteArray(frameBytes)
                    var bytesRead = 0
                    while (bytesRead < frameBytes) {
                        val read = stream.read(frameBuffer, bytesRead, frameBytes - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }
                    val tReadEnd = System.nanoTime()
                    if (bytesRead < frameBytes) break // EOF

                    val timeMs = (localFrameIndex * 1000.0 / detectionFps).toLong()

                    var skip = false
                    if (startTimeAdjusted != null && telemetryPoints.isNotEmpty()) {
                        val currentSec = timeMs.toDouble() / 1000.0
                        val currentUtcSeconds = startEpochSecond + currentSec
                        val currentFitTs = currentUtcSeconds - fitEpoch
                        
                        val fitStart = telemetryPoints.first().timestamp
                        val fitEnd = telemetryPoints.last().timestamp
                        
                        if (currentFitTs in fitStart..fitEnd) {
                            val point = findClosestTelemetryPoint(telemetryPoints, currentFitTs)
                            if (point != null && point.speed >= 10.0) {
                                skip = true
                            }
                        }
                    }

                    val dRead = (tReadEnd - tReadStart) / 1_000_000.0
                    val tSendStart = System.nanoTime()
                    frameChannel.send(DecodedFrame(localFrameIndex, timeMs, frameBuffer, skip, dRead))
                    val tSendEnd = System.nanoTime()

                    val dSend = (tSendEnd - tSendStart) / 1_000_000.0
                    if (localFrameIndex <= 10 || localFrameIndex % 50 == 0L) {
                        println("DEBUG: Producer [Frame $localFrameIndex] - Read: ${String.format(java.util.Locale.US, "%.2f", dRead)}ms, SendWait: ${String.format(java.util.Locale.US, "%.2f", dSend)}ms")
                    }

                    localFrameIndex++
                }
            } catch (e: Exception) {
                // Pipe closed
            } finally {
                frameChannel.close()
                try { stream.close() } catch (e: Exception) {}
            }
        }

        // Consumer Loop: Processes frames as they arrive from the background decoder
        try {
            for (frame in frameChannel) {
                if (onCancel()) break

                val tConsumeStart = System.nanoTime()
                totalDecodeMs += frame.decodeMs
                val boxes = if (frame.skipDetection) {
                    skippedFrames++
                    emptyList()
                } else {
                    // Populate reusable BufferedImage directly from frame buffer and detect
                    System.arraycopy(frame.buffer, 0, imgData, 0, frameBytes)
                    if (frame.frameIndex < 5L || (frame.frameIndex in 48L..54L)) {
                        try {
                            val sd = File("scratch")
                            if (!sd.exists()) sd.mkdirs()
                            javax.imageio.ImageIO.write(img, "jpg", File(sd, "scan_frame_${frame.frameIndex}.jpg"))
                            println("DEBUG: Wrote scratch/scan_frame_${frame.frameIndex}.jpg for visual audit.")
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    val rawBoxes = detector.detect(img, confThreshold = 0.08f)
                    // Scale from 640x640 back to original video resolution
                    rawBoxes.map { box ->
                        val scaleX = videoWidth.toFloat() / 640f
                        val scaleY = videoHeight.toFloat() / 640f
                        fit.PlateBox(
                            x1 = (box.x1 * scaleX).toInt().coerceAtLeast(0),
                            y1 = (box.y1 * scaleY).toInt().coerceAtLeast(0),
                            x2 = (box.x2 * scaleX).toInt().coerceAtMost(videoWidth),
                            y2 = (box.y2 * scaleY).toInt().coerceAtMost(videoHeight)
                        )
                    }
                }
                val tDetectEnd = System.nanoTime()
                val dDetect = (tDetectEnd - tConsumeStart) / 1_000_000.0
                if (!frame.skipDetection) {
                    totalYoloMs += dDetect
                    yoloCount++
                }

                if (boxes.isNotEmpty()) {
                    records.add(PlateRecord(frame.timeMs, boxes))
                }

                frameIndex++
                
                val tProgressStart = System.nanoTime()
                if (totalFrames > 0) {
                    val progress = (frameIndex.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                    val currentPercent = progress * 100f
                    if (currentPercent - lastProgressPercent >= 0.5f || frameIndex == totalFrames) {
                        onProgress(currentPercent)
                        lastProgressPercent = currentPercent
                    }
                }
                val tProgressEnd = System.nanoTime()

                // Progress & ETA estimation logs
                if (frameIndex > 0L && (frameIndex % 50 == 0L || frameIndex == totalFrames)) {
                    val elapsedMs = System.currentTimeMillis() - scanStartTimeMs
                    val elapsedSec = elapsedMs / 1000.0
                    val avgFps = if (elapsedSec > 0.0) frameIndex.toDouble() / elapsedSec else 0.0
                    val remainingFrames = totalFrames - frameIndex
                    val etaSec = if (avgFps > 0.0) (remainingFrames / avgFps).toLong() else 0L
                    val etaMin = etaSec / 60
                    val etaRemainingSec = etaSec % 60
                    val etaStr = if (etaMin > 0) "${etaMin}m ${etaRemainingSec}s" else "${etaRemainingSec}s"
                    val elapsedStr = if (elapsedMs >= 60000) "${elapsedMs / 60000}m ${(elapsedMs % 60000) / 1000}s" else "${String.format(java.util.Locale.US, "%.1f", elapsedSec)}s"
                    val skipRatio = if (frameIndex > 0L) (skippedFrames.toFloat() / frameIndex.toFloat() * 100f) else 0f
                    val progressPercent = if (totalFrames > 0L) (frameIndex.toFloat() / totalFrames.toFloat() * 100f) else 0f
                    
                    val avgDecodeMs = totalDecodeMs / frameIndex.toDouble()
                    val avgYoloMs = if (yoloCount > 0L) totalYoloMs / yoloCount.toDouble() else 0.0
                    val estSavedMs = skippedFrames * (if (yoloCount > 0L) avgYoloMs else 80.0)
                    val estSavedSec = estSavedMs / 1000.0
                    
                    println("DEBUG: Plate Scan Progress: ${String.format(java.util.Locale.US, "%.1f", progressPercent)}% ($frameIndex/$totalFrames) | Speed: ${String.format(java.util.Locale.US, "%.1f", avgFps)} fps | Skipped: $skippedFrames (${String.format(java.util.Locale.US, "%.1f", skipRatio)}%) | Elapsed: $elapsedStr | ETA: $etaStr | AvgDecode: ${String.format(java.util.Locale.US, "%.1f", avgDecodeMs)}ms | AvgYolo: ${String.format(java.util.Locale.US, "%.1f", avgYoloMs)}ms | Saved: ~${String.format(java.util.Locale.US, "%.1f", estSavedSec)}s")
                    
                    val jsonProgress = String.format(java.util.Locale.US, 
                        "{\"percent\":%.1f,\"current\":%d,\"total\":%d,\"fps\":%.1f,\"skipped\":%d,\"elapsed_ms\":%d,\"eta_sec\":%d,\"yolo_count\":%d,\"skipped_count\":%d,\"avg_decode_ms\":%.2f,\"avg_yolo_ms\":%.2f,\"saved_ms\":%.1f}",
                        progressPercent, frameIndex, totalFrames, avgFps, skippedFrames, elapsedMs, etaSec, yoloCount, skippedFrames, avgDecodeMs, avgYoloMs, estSavedMs
                    )
                    println("PROGRESS_METRIC: $jsonProgress")
                }

                val dProgress = (tProgressEnd - tProgressStart) / 1_000_000.0
                if (frameIndex <= 5) {
                    println("DEBUG: Consumer [Frame ${frame.frameIndex}] - Detect: ${String.format(java.util.Locale.US, "%.2f", dDetect)}ms, ProgressUI: ${String.format(java.util.Locale.US, "%.2f", dProgress)}ms")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            decoderJob.cancel()
            cancellationHandler?.dispose()
            
            val exitCode = if (process.isAlive) {
                try {
                    if (process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.exitValue() else -99
                } catch (e: Exception) { -99 }
            } else {
                process.exitValue()
            }
            
            process.destroy()
            
            if (frameIndex == 0L && exitCode != 0) {
                val errLogFile = File("temp_work/scan_ffmpeg_error.log")
                val errMsg = if (errLogFile.exists()) errLogFile.readText() else "No error log found"
                println("\n❌ FFmpeg Scan Process failed with exit code $exitCode. Error Details:\n$errMsg")
            }
            
            tempLocalVideo?.let {
                if (it.exists()) {
                    println("🧹 Cleaning up temp local video scan cache: ${it.absolutePath}")
                    try { it.delete() } catch (e: Exception) {}
                }
            }
        }

        if (onCancel()) {
            println("DEBUG: Scan canceled. Processed $frameIndex frames.")
            null
        } else {
            val ratio = if (frameIndex > 0) skippedFrames.toFloat() / frameIndex.toFloat() * 100f else 0f
            println("DEBUG: Scan complete. Total frames: $frameIndex, Skipped (speed >= 10km/h): $skippedFrames (${String.format(java.util.Locale.US, "%.1f", ratio)}%)")
            detector.printPerfStatsSummary()
            val cache = VideoPlatesCache(videoPath, records)
            PlateCacheManager.saveCache(videoPath, cache)
            cache
        }
    }

    private fun findClosestTelemetryPoint(
        telemetry: List<fit.FitParser.TelemetryPoint>,
        targetFitTs: Double
    ): fit.FitParser.TelemetryPoint? {
        if (telemetry.isEmpty()) return null
        var low = 0
        var high = telemetry.size - 1
        
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = telemetry[mid].timestamp
            
            when {
                midVal < targetFitTs -> low = mid + 1
                midVal > targetFitTs -> high = mid - 1
                else -> return telemetry[mid]
            }
        }
        
        val candidate1 = if (low in telemetry.indices) telemetry[low] else null
        val candidate2 = if (high in telemetry.indices) telemetry[high] else null
        
        return when {
            candidate1 == null -> candidate2
            candidate2 == null -> candidate1
            else -> {
                if (kotlin.math.abs(candidate1.timestamp - targetFitTs) < kotlin.math.abs(candidate2.timestamp - targetFitTs)) {
                    candidate1
                } else {
                    candidate2
                }
            }
        }
    }

    private fun isGoogleDrivePath(path: String): Boolean {
        val normalized = path.replace("\\", "/").lowercase()
        return normalized.contains("google drive") || 
               normalized.contains("マイドライブ") || 
               normalized.contains("my drive") ||
               normalized.startsWith("g:/") || 
               normalized.startsWith("h:/")
    }
}
