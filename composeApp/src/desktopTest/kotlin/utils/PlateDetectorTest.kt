package utils

import fit.PlateBox
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import fit.FitParser
import kotlinx.coroutines.runBlocking
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

    @Test
    fun testCropAndBlurSamplingVerification() {
        val videoPath = "H:\\\u30DE\u30A4\u30C9\u30E9\u30A4\u30D6\\Insta360\\20260614\\VID_20260614_163204_003.mp4"
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            println("SKIP: Video file not found.")
            return
        }

        val ffmpegPath = fit.findFfmpegPath()
        val scratchDir = File("scratch")
        if (!scratchDir.exists()) scratchDir.mkdirs()

        val cropTestMp4 = File(scratchDir, "crop_test.mp4")
        val cropBlurredMp4 = File(scratchDir, "crop_blurred_output.mp4")

        // 1. Crop 20 seconds from 390.0s to 410.0s using FFmpeg (with copy to preserve rotate metadata)
        println("🎬 Cropping video...")
        val pbCrop = ProcessBuilder(
            ffmpegPath, "-y",
            "-ss", "390.0",
            "-i", videoPath,
            "-t", "20.0",
            "-c", "copy",
            cropTestMp4.absolutePath
        )
        pbCrop.redirectErrorStream(true)
        val pCrop = pbCrop.start()
        val cropOut = pCrop.inputStream.bufferedReader().readText()
        pCrop.waitFor()
        println("🎬 Crop finished: ${cropTestMp4.exists()} (${cropTestMp4.length()} bytes)")

        // 1.5. Generate a dummy .fit file programmatically aligned with the crop video timeline
        val inputFit = File(scratchDir, "crop_dummy.fit")
        val fitEpochSec = 631065600L
        val videoStartUtcSeconds = java.time.Instant.parse("2026-06-14T08:02:06Z").epochSecond
        val baseFitTimestamp = videoStartUtcSeconds - fitEpochSec // Align with crop video timeline start

        val headerSize = 14
        val recordsSize = 12 + (9 * 21) // Definition(12) + 21 Data Records(9 bytes each)
        val totalSize = headerSize + recordsSize + 2
        val bytes = ByteArray(totalSize)

        bytes[0] = headerSize.toByte()
        bytes[1] = 32 // Protocol Version
        bytes[2] = 0xDC.toByte() // Profile Version
        bytes[3] = 0x07.toByte()
        bytes[4] = (recordsSize and 0xFF).toByte()
        bytes[5] = ((recordsSize shr 8) and 0xFF).toByte()
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x00.toByte()
        ".FIT".encodeToByteArray().copyInto(bytes, 8)

        var offset = headerSize
        bytes[offset] = 0x40.toByte() // Definition header
        bytes[offset + 1] = 0
        bytes[offset + 2] = 0 // Little Endian
        bytes[offset + 3] = 0x14.toByte() // Msg 20 (Record)
        bytes[offset + 4] = 0x00.toByte()
        bytes[offset + 5] = 2 // Field count

        bytes[offset + 6] = 253.toByte() // Field 1: timestamp
        bytes[offset + 7] = 4.toByte()
        bytes[offset + 8] = 0x86.toByte()

        bytes[offset + 9] = 5.toByte() // Field 2: distance
        bytes[offset + 10] = 4.toByte()
        bytes[offset + 11] = 0x86.toByte()

        offset += 12

        for (t in 0..20) {
            bytes[offset] = 0x00.toByte()
            val ts = baseFitTimestamp + t
            bytes[offset + 1] = (ts and 0xFF).toByte()
            bytes[offset + 2] = ((ts shr 8) and 0xFF).toByte()
            bytes[offset + 3] = ((ts shr 16) and 0xFF).toByte()
            bytes[offset + 4] = ((ts shr 24) and 0xFF).toByte()

            val dist = 10000 + t * 10
            bytes[offset + 5] = (dist and 0xFF).toByte()
            bytes[offset + 6] = ((dist shr 8) and 0xFF).toByte()
            bytes[offset + 7] = ((dist shr 16) and 0xFF).toByte()
            bytes[offset + 8] = ((dist shr 24) and 0xFF).toByte()
            offset += 9
        }

        // CRC
        val computedCrc = crc.Crc16.calculate(bytes, offset = 0, length = totalSize - 2)
        bytes[totalSize - 2] = (computedCrc and 0xFF).toByte()
        bytes[totalSize - 1] = ((computedCrc shr 8) and 0xFF).toByte()
        inputFit.writeBytes(bytes)
        println("📄 Created test dummy FIT file at: ${inputFit.absolutePath}")

        // 2. Perform plate detection on crop_test.mp4 (starts at 390.0s original time)
        println("🔍 Scanning crop video for plates...")
        val fitBytes = inputFit.readBytes()
        val parser = FitParser(fitBytes)
        parser.parse()
        val telemetryPoints = parser.getTelemetry()

        val cache = runBlocking {
            PlateDetectionManager.runDetection(
                videoPath = cropTestMp4.absolutePath,
                telemetryPoints = telemetryPoints,
                adjustedStartUtc = "2026-06-14T08:02:06Z", // Matches video metadata start
                onProgress = { println("Detection: ${String.format("%.1f", it)}%") },
                onCancel = { false }
            )
        }
        assertTrue(cache != null, "Scan cache must be successfully built")
        println("🔍 Scan finished: Found ${cache.records.size} frames with plates")
        println("📝 DEBUG: Detected Plate Cache Records:")
        for (rec in cache.records) {
            println("  - timeMs: ${rec.timeMs} -> boxes: ${rec.boxes}")
        }

        // 3. Run NativeHudEncoder to render crop_blurred_output.mp4
        println("📹 Encoding blurred video...")
        val settings = fit.HudSettings(
            blurLicensePlates = true,
            exportResolution = "original"
        )
        val encoder = fit.NativeHudEncoder(settings)
        fit.PlateCacheManager.saveCache(cropTestMp4.absolutePath, cache)

        encoder.encode(
            fitPath = inputFit.absolutePath, // Pass aligned dummy FIT file path
            videoPath = cropTestMp4.absolutePath,
            output = cropBlurredMp4.absolutePath,
            startUtc = "2026-06-14T08:02:06Z",
            maxDurationSeconds = 20,
            trimStartSeconds = 0.0,
            trimEndSeconds = 20.0
        )
        assertTrue(cropBlurredMp4.exists(), "Blurred video must be created")
        println("📹 Encode finished: ${cropBlurredMp4.exists()} (${cropBlurredMp4.length()} bytes)")

        // 4. Sample key frames using FFmpeg to verify visual positioning
        // 398.4s original time -> 8.4s in crop video
        // 400.4s original time -> 10.4s in crop video
        // 402.0s original time -> 12.0s in crop video
        val sampleTimes = listOf(8.4, 10.4, 12.0)
        for (st in sampleTimes) {
            val sampleImgFile = File(scratchDir, "sample_output_blur_${String.format("%.1f", st)}.jpg")
            val pbSample = ProcessBuilder(
                ffmpegPath, "-y",
                "-i", cropBlurredMp4.absolutePath,
                "-ss", st.toString(),
                "-vframes", "1",
                sampleImgFile.absolutePath
            )
            pbSample.inheritIO()
            val pSample = pbSample.start()
            pSample.waitFor()
            println("📸 Wrote sample frame at crop time ${st}s to: ${sampleImgFile.absolutePath}")
            assertTrue(sampleImgFile.exists() && sampleImgFile.length() > 0, "Sample frame should be written successfully")
        }
    }

    @Test
    fun testGuiFlowPlateCacheAssignment() {
        val cropTestMp4 = File("C:\\Users\\yuuji\\fit-trimmer\\composeApp\\scratch\\crop_test.mp4")
        if (!cropTestMp4.exists()) {
            println("Skipping GUI flow test: crop_test.mp4 not found")
            return
        }

        // 1. Clear existing cache file to simulate a fresh first-time video load
        val cacheFile = fit.PlateCacheManager.getPlatesFile(cropTestMp4.absolutePath)
        if (cacheFile != null && cacheFile.exists()) {
            cacheFile.delete()
        }

        val viewModel = viewmodel.AppViewModel(initialCache = null)
        
        // Assert initial completely unassigned state
        kotlin.test.assertNull(viewModel.plateCache, "Initial plateCache must be null")
        kotlin.test.assertFalse(viewModel.isDetectingPlates, "Should not be detecting initially")

        // Setting videoPath triggers cache load attempt, which stays null since no cache file exists yet
        viewModel.videoPath = cropTestMp4.absolutePath
        kotlin.test.assertNull(viewModel.plateCache, "plateCache must still be null after path change when no cache file exists")

        // Verify that trying to query mask boxes while unassigned safely returns empty (no crash, safe UI rendering)
        val sampleTimeMs = 12000L
        val initialBoxes = viewModel.plateCache?.shouldBlurAt(sampleTimeMs, true) ?: emptyList()
        kotlin.test.assertTrue(initialBoxes.isEmpty(), "Unassigned plate cache must yield empty boxes")

        // 2. Trigger asynchronous plate detection flow as simulated by GUI's LaunchEffect
        runBlocking {
            viewModel.runPlateDetection(this)
            
            // Wait for the background detection job to complete
            while (viewModel.isDetectingPlates) {
                kotlinx.coroutines.delay(50)
            }
        }

        // 3. Assert assigned state
        kotlin.test.assertNotNull(viewModel.plateCache, "plateCache must be assigned after detection completes")
        kotlin.test.assertFalse(viewModel.isDetectingPlates, "isDetectingPlates must be false after completion")
        
        // Verify that once assigned, correct boxes are successfully returned for the frame
        val assignedBoxes = viewModel.plateCache?.shouldBlurAt(sampleTimeMs, true) ?: emptyList()
        kotlin.test.assertTrue(assignedBoxes.isNotEmpty(), "Assigned plate cache must yield non-empty boxes for active frames")
        println("✅ GUI Flow Plate Cache Assignment Test Completed Successfully!")
    }

    @Test
    fun testPlateDetectionRunsAtHighSpeed() {
        val cropTestMp4 = File("C:\\Users\\yuuji\\fit-trimmer\\composeApp\\scratch\\crop_test.mp4")
        if (!cropTestMp4.exists()) {
            println("Skipping high speed test: crop_test.mp4 not found")
            return
        }

        // Create dummy high-speed telemetry (all points at 25.0 km/h)
        val telemetry = List(21) { idx ->
            fit.FitParser.TelemetryPoint(
                timestamp = 1150022526.0 + idx,
                speed = 25.0, // 25.0 km/h >= 10.0 km/h
                power = 200.0,
                cadence = 90.0,
                heartRate = 150.0,
                elevation = 50.0,
                grade = 0.0
            )
        }

        // Run detection with speed filter active
        val cache = runBlocking {
            PlateDetectionManager.runDetection(
                videoPath = cropTestMp4.absolutePath,
                telemetryPoints = telemetry,
                adjustedStartUtc = "2026-06-14T08:02:06Z",
                onProgress = {},
                onCancel = { false }
            )
        }

        kotlin.test.assertNotNull(cache, "Scan cache must be successfully built")
        kotlin.test.assertTrue(cache.records.isEmpty(), "Should skip all plates at high speeds (>= 10km/h)")
        println("✅ High Speed Plate Detection Test Completed Successfully!")
    }




    private class SGObserver : java.awt.image.ImageObserver {
        override fun imageUpdate(img: java.awt.Image?, infoflags: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
            return false
        }
    }
}
