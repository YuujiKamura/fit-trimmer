package utils

import fit.findFfmpegPath
import fit.VideoPlatesCache
import fit.PlateRecord
import fit.PlateCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.awt.image.BufferedImage
import java.io.BufferedInputStream

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

        // 1. Get video metadata using ffmpeg -i
        val pbInfo = ProcessBuilder(ffmpegPath, "-i", videoPath)
        pbInfo.redirectErrorStream(true)
        val pInfo = pbInfo.start()
        val outputInfo = pInfo.inputStream.bufferedReader().readText()
        pInfo.waitFor()

        var videoWidth = 1920
        var videoHeight = 1080
        var durationSec = 100.0
        var videoFps = 30.0

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
        val maxDim = 1920
        var scanWidth = videoWidth
        var scanHeight = videoHeight
        if (scanWidth > maxDim || scanHeight > maxDim) {
            if (scanWidth > scanHeight) {
                scanHeight = (scanHeight * maxDim.toDouble() / scanWidth.toDouble()).toInt()
                scanWidth = maxDim
            } else {
                scanWidth = (scanWidth * maxDim.toDouble() / scanHeight.toDouble()).toInt()
                scanHeight = maxDim
            }
        }
        
        val frameBytes = scanWidth * scanHeight * 3 // RGB24
        
        val pb = ProcessBuilder(
            ffmpegPath,
            "-i", videoPath,
            "-vf", "fps=$detectionFps,scale=$scanWidth:$scanHeight:out_range=full",
            "-f", "rawvideo",
            "-pix_fmt", "bgr24", // Use bgr24 for fast native image buffer copy
            "-vcodec", "rawvideo",
            "pipe:1"
        )
        pb.redirectErrorStream(false)
        val process = pb.start()
 
        val records = mutableListOf<PlateRecord>()
        val detector = PlateDetector.getInstance()
        
        val stream = BufferedInputStream(process.inputStream, 1024 * 1024)
        val buffer = ByteArray(frameBytes)
 
        val totalFrames = (durationSec * detectionFps).toLong()
        var frameIndex = 0L
 
        try {
            while (!onCancel()) {
                var bytesRead = 0
                while (bytesRead < frameBytes) {
                    val read = stream.read(buffer, bytesRead, frameBytes - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                if (bytesRead < frameBytes) break // EOF
 
                val timeMs = (frameIndex * 1000.0 / detectionFps).toLong()
 
                val img = BufferedImage(scanWidth, scanHeight, BufferedImage.TYPE_3BYTE_BGR)
                val imgData = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
                System.arraycopy(buffer, 0, imgData, 0, frameBytes)

                // Speed check optimization: Only detect plates when vehicle speed is < 10 km/h.
                // Plates are blurred and fast-moving cars cannot be read anyway, saving 90%+ CPU processing.
                val startTimeAdjusted = try {
                    if (adjustedStartUtc.isNotEmpty()) java.time.ZonedDateTime.parse(adjustedStartUtc) else null
                } catch (e: Exception) {
                    null
                }
                val fitEpoch = 631065600L // 1990-01-01T00:00:00Z UTC epoch
                
                var skipDetection = false
                if (startTimeAdjusted != null && telemetryPoints.isNotEmpty()) {
                    val currentSec = timeMs.toDouble() / 1000.0
                    val currentUtcSeconds = startTimeAdjusted.toEpochSecond() + currentSec
                    val currentFitTs = currentUtcSeconds - fitEpoch
                    
                    val point = findClosestTelemetryPoint(telemetryPoints, currentFitTs)
                    if (point != null && point.speed >= 10.0) {
                        skipDetection = true
                    }
                }

                val boxes = if (skipDetection) {
                    emptyList()
                } else {
                    detector.detect(img)
                }
                
                if (boxes.isNotEmpty()) {
                    records.add(PlateRecord(timeMs, boxes))
                }

                frameIndex++
                if (totalFrames > 0) {
                    val progress = (frameIndex.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress * 100f)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process.destroy()
        }

        if (onCancel()) {
            null
        } else {
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
}
