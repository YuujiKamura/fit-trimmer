package utils

import fit.PlateBox
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class PlateDetectorTest {

    @Test
    fun testDetectLicensePlateFromScreenshot() {
        val screenshotPath = "C:\\Users\\yuuji\\OneDrive\\Pictures\\Screenshots\\\u30B9\u30AF\u30EA\u30FC\u30F3\u30B7\u30E7\u30C3\u30C8 2026-07-01 081110.png"
        val file = File(screenshotPath)
        assertTrue(file.exists(), "Screenshot file does not exist at $screenshotPath")

        val image = ImageIO.read(file)
        println("Loaded screenshot: ${image.width}x${image.height}")

        val detector = PlateDetector.getInstance()
        val boxes = detector.detect(image, confThreshold = 0.25f)
        
        println("Detected ${boxes.size} license plates:")
        for ((idx, box) in boxes.withIndex()) {
            println("  [$idx] x1=${box.x1}, y1=${box.y1}, x2=${box.x2}, y2=${box.y2} (w=${box.x2 - box.x1}, h=${box.y2 - box.y1})")
        }

        assertTrue(boxes.isNotEmpty(), "Should have detected at least one license plate")
        
        val rightSidePlates = boxes.filter { it.x1 > image.width / 2 }
        assertTrue(rightSidePlates.isNotEmpty(), "Should detect the bus license plate on the right half of the image")
    }

    @Test
    fun testDetectFromActualVideo() {
        val videoPath = "H:\\\u30DE\u30A4\u30C9\u30E9\u30A4\u30D6\\Insta360\\20260614\\VID_20260614_163204_003.mp4"
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            println("Skipping video test: Video file not found on GDrive.")
            return
        }

        val ffmpegPath = fit.findFfmpegPath()
        val videoWidth = 1920
        val videoHeight = 1080
        val detector = PlateDetector.getInstance()
        val scratchDir = File("scratch")
        if (!scratchDir.exists()) scratchDir.mkdirs()

        // Scan time range around 6m 41s (401.0s) to find any frame where the plate is detected
        val startTime = 398.0
        val endTime = 404.0
        val step = 0.2 // Check every 200ms

        println("Scanning video frames from $startTime s to $endTime s...")
        var foundAny = false
        var t = startTime
        while (t <= endTime) {
            try {
                val img = extractFrameAt(ffmpegPath, videoPath, t, videoWidth, videoHeight)
                
                // Try original and slightly contrast-enhanced images
                val trials = listOf(
                    Pair(1.0f, 0.0f),
                    Pair(1.3f, -30f)
                )
                
                for ((factor, offset) in trials) {
                    val adjusted = adjustContrast(img, factor, offset)
                    val boxes = detector.detect(adjusted, confThreshold = 0.20f) // Slightly lower threshold for robust scanning
                    if (boxes.isNotEmpty()) {
                        println("  SUCCESS at timestamp ${String.format("%.1f", t)}s (contrast factor=$factor, offset=$offset): Found ${boxes.size} plates:")
                        for ((idx, box) in boxes.withIndex()) {
                            println("    [$idx] x1=${box.x1}, y1=${box.y1}, x2=${box.x2}, y2=${box.y2}")
                        }
                        foundAny = true
                        ImageIO.write(adjusted, "jpg", File(scratchDir, "test_adjusted_success_${String.format("%.1f", t)}.jpg"))
                    }
                }
            } catch (e: Exception) {
                println("  Failed to process frame at $t: ${e.message}")
            }
            t += step
        }

        assertTrue(foundAny, "Should detect the license plate in at least one frame around 401s")
    }

    private fun extractFrameAt(ffmpegPath: String, videoPath: String, timeSeconds: Double, width: Int, height: Int): BufferedImage {
        val pb = ProcessBuilder(
            ffmpegPath,
            "-ss", timeSeconds.toString(),
            "-i", videoPath,
            "-vf", "scale=$width:$height:out_range=full",
            "-vframes", "1",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-vcodec", "rawvideo",
            "pipe:1"
        )
        pb.redirectErrorStream(false)
        val process = pb.start()

        val frameBytes = width * height * 3
        val buffer = ByteArray(frameBytes)
        val stream = java.io.BufferedInputStream(process.inputStream)
        
        var bytesRead = 0
        while (bytesRead < frameBytes) {
            val read = stream.read(buffer, bytesRead, frameBytes - bytesRead)
            if (read == -1) break
            bytesRead += read
        }
        process.destroy()

        if (bytesRead != frameBytes) {
            throw java.io.IOException("Failed to read full frame from ffmpeg. Read $bytesRead bytes out of $frameBytes")
        }

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer[offset].toInt() and 0xFF
                val g = buffer[offset + 1].toInt() and 0xFF
                val b = buffer[offset + 2].toInt() and 0xFF
                val rgb = (r shl 16) or (g shl 8) or b
                img.setRGB(x, y, rgb)
                offset += 3
            }
        }
        return img
    }

    private fun adjustContrast(src: BufferedImage, factor: Float, offset: Float): BufferedImage {
        if (factor == 1.0f && offset == 0.0f) return src
        val dest = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val rgb = src.getRGB(x, y)
                var r = ((rgb shr 16) and 0xFF).toFloat()
                var g = ((rgb shr 8) and 0xFF).toFloat()
                var b = (rgb and 0xFF).toFloat()
                
                r = (r * factor + offset).coerceIn(0f, 255f)
                g = (g * factor + offset).coerceIn(0f, 255f)
                b = (b * factor + offset).coerceIn(0f, 255f)
                
                val newRgb = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                dest.setRGB(x, y, newRgb)
            }
        }
        return dest
    }

    @Test
    fun testWholeVideoPreScanPipeline() = kotlinx.coroutines.runBlocking {
        // Read actual path from GUI cache file to measure real dataset scan time
        val cacheFile = File(System.getProperty("user.home"), ".fittrimmer_gui_cache.json")
        if (!cacheFile.exists()) {
            println("Skipping benchmark: GUI cache file not found.")
            return@runBlocking
        }
        val content = cacheFile.readText(Charsets.UTF_8)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val cache = json.decodeFromString<utils.GuiPathCache>(content)
        
        val videoPath = cache.videoPath
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            println("Skipping benchmark: Video file '$videoPath' from GUI cache does not exist.")
            return@runBlocking
        }
        
        val fitPath = cache.fitPath
        val fitFile = File(fitPath)
        val telemetryPoints = if (fitFile.exists()) {
            val parser = fit.FitParser(fitFile.readBytes())
            parser.parse()
            parser.getTelemetry()
        } else emptyList()

        println("======================================================================")
        println("🚀 BENCHMARK: MEASURING TOTAL EXECUTION TIME FOR REAL DATASET SCAN")
        println("  - Video Path: $videoPath")
        println("  - Telemetry Points: ${telemetryPoints.size}")
        println("  - Start UTC: ${cache.videoStartUtc}")
        println("======================================================================")

        val tStart = System.currentTimeMillis()
        var totalFramesScanned = 0
        
        val result = PlateDetectionManager.runDetection(
            videoPath = videoPath,
            telemetryPoints = telemetryPoints,
            adjustedStartUtc = cache.videoStartUtc,
            onProgress = { progress ->
                // Collect and output progress metrics
                totalFramesScanned++
            },
            onCancel = {
                // Run full video scan to completion
                false
            }
        )

        val tEnd = System.currentTimeMillis()
        val totalSec = (tEnd - tStart) / 1000.0
        
        println("======================================================================")
        println("🏁 BENCHMARK COMPLETED")
        println("  - Total Measured Execution Time: ${String.format(java.util.Locale.US, "%.2f", totalSec)} seconds")
        println("  - Plates Cache Created: ${result != null}")
        if (result != null) {
            println("  - Total Detected Plates Records: ${result.records.size}")
        }
        println("======================================================================")
        
        assertTrue(result != null, "Should output a valid plates cache for the video")
    }

    @Test
    fun testBypassResizeAndGetRgbForOptimizedImage() {
        val detector = PlateDetector.getInstance()
        detector.lastResizeBypassed = false
        detector.lastGetRgbBypassed = false
        
        // 640x640 3BYTE_BGR image (simulating FFmpeg output)
        val img = BufferedImage(640, 640, BufferedImage.TYPE_3BYTE_BGR)
        
        detector.detect(img)
        
        assertTrue(detector.lastResizeBypassed, "Resize should be bypassed for 640x640 images")
        assertTrue(detector.lastGetRgbBypassed, "getRGB should be bypassed for TYPE_3BYTE_BGR images")
    }

    private class SGObserver : java.awt.image.ImageObserver {
        override fun imageUpdate(img: java.awt.Image?, infoflags: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
            return false
        }
    }
}
