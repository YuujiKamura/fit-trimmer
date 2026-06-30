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

        // To speed up detection without losing tracking accuracy, we decimate frames to 10 fps
        val detectionFps = 10.0
        val frameBytes = videoWidth * videoHeight * 3 // RGB24
        
        val pb = ProcessBuilder(
            ffmpegPath,
            "-i", videoPath,
            "-vf", "fps=$detectionFps,scale=$videoWidth:$videoHeight",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
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

                val img = BufferedImage(videoWidth, videoHeight, BufferedImage.TYPE_INT_RGB)
                var offset = 0
                for (y in 0 until videoHeight) {
                    for (x in 0 until videoWidth) {
                        val r = buffer[offset].toInt() and 0xFF
                        val g = buffer[offset + 1].toInt() and 0xFF
                        val b = buffer[offset + 2].toInt() and 0xFF
                        val rgb = (r shl 16) or (g shl 8) or b
                        img.setRGB(x, y, rgb)
                        offset += 3
                    }
                }

                val boxes = detector.detect(img)
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
}
