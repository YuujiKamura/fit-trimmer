import fit.HudSettings
import fit.PlateBox
import fit.PlateCacheManager
import fit.PlateRecord
import fit.PlateScanRange
import fit.VideoPlatesCache
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HudEncodePipelineTest {
    @Test
    fun ensurePlateCacheForEncodeDoesNothingWhenBlurIsDisabled() = runBlocking {
        val videoPath = uniqueVideoPath("no-blur")
        var scannerCalled = false

        try {
            val cache = HudEncodePipeline.ensurePlateCacheForEncode(
                settings = HudSettings(blurLicensePlates = false),
                videoPath = videoPath,
                platePreScanner = { _, _, _, _, _, _, _ ->
                    scannerCalled = true
                    VideoPlatesCache(videoPath = videoPath, records = emptyList())
                }
            )

            assertFalse(scannerCalled, "Plate scanner must not run when blurLicensePlates is false")
            assertEquals(null, cache)
        } finally {
            PlateCacheManager.deleteCache(videoPath)
        }
    }

    @Test
    fun ensurePlateCacheForEncodeRunsScannerAndSavesCacheOnMiss() = runBlocking {
        val videoPath = uniqueVideoPath("cache-miss")
        PlateCacheManager.deleteCache(videoPath)
        var scannerCalls = 0
        val statuses = mutableListOf<String>()

        try {
            val cache = HudEncodePipeline.ensurePlateCacheForEncode(
                settings = HudSettings(blurLicensePlates = true),
                videoPath = videoPath,
                onProgress = { _, status -> statuses.add(status) },
                platePreScanner = { path, _, _, progress, _, settings, scanRanges ->
                    scannerCalls++
                    assertEquals(videoPath, path)
                    assertEquals(null, scanRanges)
                    assertEquals(30.0, settings.plateMaxSpeedKmh)
                    assertEquals(1.0, settings.plateDetectionFps)
                    assertEquals(2.0, settings.platePaddingSeconds)
                    assertEquals(5.0, settings.plateMergeGapSeconds)
                    progress(42.5f, "Mock status")
                    VideoPlatesCache(
                        videoPath = path,
                        records = listOf(PlateRecord(1000L, listOf(PlateBox(1, 2, 30, 12)))),
                        sourceWidth = 640,
                        sourceHeight = 360
                    )
                }
            )

            assertEquals(1, scannerCalls)
            assertNotNull(cache)
            assertTrue(PlateCacheManager.cacheExists(videoPath), "Pre-scan result must be saved for NativeHudEncoder")
            assertNotNull(PlateCacheManager.loadCache(videoPath))
            assertTrue(statuses.any { it.contains("Scanning license plates before encoding") })
            assertTrue(statuses.any { it.contains("Plate scan complete") })
        } finally {
            PlateCacheManager.deleteCache(videoPath)
        }
    }

    @Test
    fun ensurePlateCacheForEncodeUsesExistingCacheWithoutScanner() = runBlocking {
        val videoPath = uniqueVideoPath("cache-hit")
        val existing = VideoPlatesCache(
            videoPath = videoPath,
            records = listOf(PlateRecord(2000L, listOf(PlateBox(4, 5, 40, 18)))),
            sourceWidth = 1280,
            sourceHeight = 720
        )
        PlateCacheManager.saveCache(videoPath, existing)
        var scannerCalled = false

        try {
            val cache = HudEncodePipeline.ensurePlateCacheForEncode(
                settings = HudSettings(blurLicensePlates = true),
                videoPath = videoPath,
                platePreScanner = { _, _, _, _, _, _, _ ->
                    scannerCalled = true
                    VideoPlatesCache(videoPath = videoPath, records = emptyList())
                }
            )

            assertFalse(scannerCalled, "Existing plate cache must be reused without pre-scan")
            assertEquals(1, cache?.records?.size)
            assertEquals(1280, cache?.sourceWidth)
            assertEquals(720, cache?.sourceHeight)
        } finally {
            PlateCacheManager.deleteCache(videoPath)
        }
    }

    @Test
    fun ensurePlateCacheForEncodeScansAndMergesMissingRequestedRange() = runBlocking {
        val videoPath = uniqueVideoPath("partial-cache")
        val existing = VideoPlatesCache(
            videoPath = videoPath,
            records = listOf(PlateRecord(1000L, listOf(PlateBox(1, 2, 30, 12)))),
            sourceWidth = 1280,
            sourceHeight = 720,
            scanRanges = listOf(PlateScanRange(0L, 5_000L))
        )
        PlateCacheManager.saveCache(videoPath, existing)
        var requestedRanges: List<Pair<Double, Double>>? = null

        try {
            val cache = HudEncodePipeline.ensurePlateCacheForEncode(
                settings = HudSettings(blurLicensePlates = true),
                videoPath = videoPath,
                ranges = listOf(10.0 to 20.0),
                platePreScanner = { path, _, _, _, _, _, scanRanges ->
                    requestedRanges = scanRanges
                    VideoPlatesCache(
                        videoPath = path,
                        records = listOf(PlateRecord(10_000L, listOf(PlateBox(4, 5, 40, 18)))),
                        sourceWidth = 1280,
                        sourceHeight = 720,
                        scanRanges = listOf(PlateScanRange(10_000L, 20_000L))
                    )
                }
            )

            assertEquals(listOf(10.0 to 20.0), requestedRanges)
            assertEquals(2, cache?.records?.size)
            assertTrue(cache?.coversRange(0.0, 5.0) == true)
            assertTrue(cache?.coversRange(10.0, 20.0) == true)
            val saved = PlateCacheManager.loadCache(videoPath)
            assertEquals(2, saved?.records?.size)
            assertTrue(saved?.coversRange(10.0, 20.0) == true)
        } finally {
            PlateCacheManager.deleteCache(videoPath)
        }
    }

    @Test
    fun testExecuteShouldResumeTrueSkipsExistingDestFiles() = runBlocking {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val dummyOutFile = java.io.File(tempDir, "fit-trimmer-dummy-out-${System.nanoTime()}.mp4")
        dummyOutFile.parentFile?.mkdirs()
        dummyOutFile.writeText("dummy_segment_content")

        try {
            // Under shouldResume = true, execute should skip encoding entirely since dummyOutFile exists,
            // and return successfully without trying to open or parse the invalid input files.
            val result = HudEncodePipeline.execute(
                s = HudSettings(blurLicensePlates = false),
                fitPath = "invalid_fit_path.fit",
                videoPath = "invalid_video_path.mp4",
                outputDir = tempDir.absolutePath,
                videoStartUtc = "2026-07-02T17:53:06Z",
                ranges = listOf(0.0 to 10.0),
                destFiles = listOf(dummyOutFile),
                shouldResume = true,
                onProgress = { _, _ -> },
                onFrame = {},
                cancelSupplier = { false },
                showLivePreviewSupplier = { false }
            )
            assertTrue(result.contains("Finished Successfully") || result.contains("Copied to Cloud"))
            assertEquals("dummy_segment_content", dummyOutFile.readText(), "Existing output file must remain untouched")
        } finally {
            if (dummyOutFile.exists()) dummyOutFile.delete()
        }
    }

    @Test
    fun testExecuteShouldResumeFalseAttemptsOverwrite() = runBlocking {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val dummyOutFile = java.io.File(tempDir, "fit-trimmer-dummy-out-${System.nanoTime()}.mp4")
        dummyOutFile.parentFile?.mkdirs()
        dummyOutFile.writeText("dummy_segment_content")

        try {
            // Under shouldResume = false, execute must NOT skip encoding. It will attempt to run NativeHudEncoder,
            // which will throw an exception immediately due to invalid/missing video files.
            var threwException = false
            try {
                HudEncodePipeline.execute(
                    s = HudSettings(blurLicensePlates = false),
                    fitPath = "invalid_fit_path.fit",
                    videoPath = "invalid_video_path.mp4",
                    outputDir = tempDir.absolutePath,
                    videoStartUtc = "2026-07-02T17:53:06Z",
                    ranges = listOf(0.0 to 10.0),
                    destFiles = listOf(dummyOutFile),
                    shouldResume = false,
                    onProgress = { _, _ -> },
                    onFrame = {},
                    cancelSupplier = { false },
                    showLivePreviewSupplier = { false }
                )
            } catch (e: Exception) {
                threwException = true
            }
            assertTrue(threwException, "Should attempt overwrite and throw exception due to invalid inputs instead of silently skipping")
        } finally {
            if (dummyOutFile.exists()) dummyOutFile.delete()
        }
    }

    @Test
    fun testPlateMaskingRealDatasetTenSeconds() = runBlocking {
        val videoPath = "F:\\Insta360\\20260708\\VID_20260708_184458_001.mp4"
        val fitPath = "F:\\Insta360\\20260708\\Evening_Ride.fit"
        val videoStartUtc = "2026-07-08T09:44:58Z"

        val video = java.io.File(videoPath)
        val fitFile = java.io.File(fitPath)
        if (!video.exists() || !fitFile.exists()) {
            println("Skipping test: Real dataset files not found locally.")
            return@runBlocking
        }

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val outFileName = "test_masking_real_dataset_${System.nanoTime()}"
        val destFile = java.io.File(tempDir, "$outFileName.mp4")

        // Clean cache before running to ensure fresh detection
        fit.PlateCacheManager.deleteCache(videoPath)

        try {
            val settings = HudSettings(
                blurLicensePlates = true,
                plateMaxSpeedKmh = 100.0,
                plateDetectionFps = 1.0,
                platePaddingSeconds = 1.0,
                plateMergeGapSeconds = 2.0
            )

            val result = HudEncodePipeline.execute(
                s = settings,
                fitPath = fitPath,
                videoPath = videoPath,
                outputDir = tempDir.absolutePath,
                videoStartUtc = videoStartUtc,
                ranges = listOf(180.0 to 190.0),
                destFiles = listOf(destFile),
                shouldResume = false,
                onProgress = { percent, status ->
                    println("TEST_PROGRESS: ${percent}% - $status")
                },
                onFrame = {},
                cancelSupplier = { false },
                showLivePreviewSupplier = { false }
            )

            assertTrue(result.contains("Finished Successfully") || result.contains("Copied to Cloud"), "Encode should complete successfully")
            assertTrue(destFile.exists(), "Output file must be created")
            assertTrue(destFile.length() > 0, "Output file must not be empty")

            // Verify that the plate cache was created and populated
            val cache = fit.PlateCacheManager.loadCache(videoPath)
            assertNotNull(cache, "Plate cache must be created after detection")
            println("TEST_RESULT: Scan complete. Total plate records found: ${cache.records.size}")
            cache.records.forEach { record ->
                println("TEST_RECORD_FOUND: timeMs=${record.timeMs} boxes=${record.boxes}")
            }
        } finally {
            if (destFile.exists()) destFile.delete()
            fit.PlateCacheManager.deleteCache(videoPath)
        }
    }

    private fun decodeFrameAt(videoPath: String, timeSeconds: Double, width: Int, height: Int): java.awt.image.BufferedImage {
        val ffmpegPath = fit.findFfmpegPath()
        val pb = ProcessBuilder(
            ffmpegPath,
            "-threads", "1",
            "-ss", String.format(java.util.Locale.US, "%.3f", timeSeconds),
            "-i", videoPath,
            "-vf", "scale=$width:$height",
            "-f", "rawvideo",
            "-pix_fmt", "bgr24",
            "-vcodec", "rawvideo",
            "-vframes", "1",
            "pipe:1"
        )
        pb.redirectError(ProcessBuilder.Redirect.to(java.io.File("NUL")))
        val proc = pb.start()
        val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
        val imgData = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
        val stream = java.io.BufferedInputStream(proc.inputStream)
        
        var bytesRead = 0
        val totalBytes = width * height * 3
        while (bytesRead < totalBytes) {
            val read = stream.read(imgData, bytesRead, totalBytes - bytesRead)
            if (read == -1) break
            bytesRead += read
        }
        proc.destroy()
        return img
    }

    private fun calculateEdgeStrength(img: java.awt.image.BufferedImage, x: Int, y: Int, w: Int, h: Int): Double {
        var sum = 0.0
        var count = 0
        val safeX = x.coerceIn(0, img.width - 2)
        val safeY = y.coerceIn(0, img.height - 2)
        val safeW = w.coerceAtMost(img.width - safeX - 1)
        val safeH = h.coerceAtMost(img.height - safeY - 1)

        for (j in safeY until (safeY + safeH)) {
            for (i in safeX until (safeX + safeW)) {
                val rgbX1 = img.getRGB(i, j)
                val rgbX2 = img.getRGB(i + 1, j)
                val rgbY2 = img.getRGB(i, j + 1)
                
                val lumX1 = ((rgbX1 shr 16 and 0xFF) * 0.299 + (rgbX1 shr 8 and 0xFF) * 0.587 + (rgbX1 and 0xFF) * 0.114)
                val lumX2 = ((rgbX2 shr 16 and 0xFF) * 0.299 + (rgbX2 shr 8 and 0xFF) * 0.587 + (rgbX2 and 0xFF) * 0.114)
                val lumY2 = ((rgbY2 shr 16 and 0xFF) * 0.299 + (rgbY2 shr 8 and 0xFF) * 0.587 + (rgbY2 and 0xFF) * 0.114)
                
                val dx = lumX2 - lumX1
                val dy = lumY2 - lumX1
                sum += kotlin.math.sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    private fun calculateIdentityRate(img: java.awt.image.BufferedImage, x: Int, y: Int, w: Int, h: Int): Double {
        var identicalCount = 0
        var totalCount = 0
        val safeX = x.coerceIn(0, img.width - 2)
        val safeY = y.coerceIn(0, img.height - 2)
        val safeW = w.coerceAtMost(img.width - safeX - 1)
        val safeH = h.coerceAtMost(img.height - safeY - 1)

        for (j in safeY until (safeY + safeH)) {
            for (i in safeX until (safeX + safeW)) {
                val rgb1 = img.getRGB(i, j)
                val rgb2 = img.getRGB(i + 1, j)
                if (rgb1 == rgb2) {
                    identicalCount++
                }
                totalCount++
            }
        }
        return if (totalCount > 0) identicalCount.toDouble() / totalCount else 0.0
    }

    @Test
    fun testPlateMaskingGroundTruthAccuracy() = runBlocking {
        val videoPath = "F:\\Insta360\\20260708\\VID_20260708_184458_001.mp4"
        val fitPath = "F:\\Insta360\\20260708\\Evening_Ride.fit"
        val videoStartUtc = "2026-07-08T09:44:58Z"

        val video = java.io.File(videoPath)
        val fitFile = java.io.File(fitPath)
        if (!video.exists() || !fitFile.exists()) {
            println("Skipping test: Real dataset files not found locally.")
            return@runBlocking
        }

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val outFileName = "test_masking_ground_truth_${System.nanoTime()}"
        val destFile = java.io.File(tempDir, "$outFileName.mp4")

        // Reset cache to ensure clean detection
        fit.PlateCacheManager.deleteCache(videoPath)

        try {
            // Configure settings to disable Padding and Merge Gap for strict ground truth mapping
            // Also disable HUD overlays to avoid overlapping clean road pixels with HUD widgets
            val settings = HudSettings(
                blurLicensePlates = true,
                plateMaxSpeedKmh = 100.0,
                plateDetectionFps = 1.0,
                platePaddingSeconds = 0.0,
                plateMergeGapSeconds = 0.0,
                showSpeed = false,
                showCadence = false,
                showHeartRate = false,
                showPower = false,
                showWkg = false,
                showPowerTrend = false,
                showGrade = false,
                showElevation = false,
                showDistanceTime = false,
                mapPosition = "top_right",
                mapType = "auto"
            )

            // 1. Encode 10s video (180.0s to 190.0s)
            val result = HudEncodePipeline.execute(
                s = settings,
                fitPath = fitPath,
                videoPath = videoPath,
                outputDir = tempDir.absolutePath,
                videoStartUtc = videoStartUtc,
                ranges = listOf(180.0 to 190.0),
                destFiles = listOf(destFile),
                shouldResume = false,
                onProgress = { _, _ -> },
                onFrame = {},
                cancelSupplier = { false },
                showLivePreviewSupplier = { false }
            )

            assertTrue(result.contains("Finished Successfully") || result.contains("Copied to Cloud"), "Encode must succeed")
            assertTrue(destFile.exists(), "Output video file must be generated")

            val dbgCache = fit.PlateCacheManager.loadCache(videoPath)
            if (dbgCache != null) {
                println("GROUND_TRUTH_DEBUG: Total records loaded = ${dbgCache.records.size}")
                for (rec in dbgCache.records) {
                    println("  - Record: timeMs=${rec.timeMs}, boxes=${rec.boxes}")
                }
            }

            val width = 2704
            val height = 1520

            // ----------------------------------------------------
            // CASE 1: VERIFY CORRECT BLUR (Plate must be masked)
            // ----------------------------------------------------
            // Timestamp: 187.333s (7.333s in output)
            // Black Car Plate Area (2.7K): x=830, y=965, w=60, h=30
            val imgOrig187 = decodeFrameAt(videoPath, 187.333, width, height)
            val imgMasked187 = decodeFrameAt(destFile.absolutePath, 7.333, width, height)

            val idOrigCar187 = calculateIdentityRate(imgOrig187, 830, 965, 60, 30)
            val idMaskedCar187 = calculateIdentityRate(imgMasked187, 830, 965, 60, 30)

            println("GROUND_TRUTH_TEST: [Correct Blur Verification at 187.333s]")
            println("  - Black Car (187.333s): OriginalIdentity=$idOrigCar187, MaskedIdentity=$idMaskedCar187")
            
            // Masked identity rate must show significant increase due to mosaic injection (typically >70%)
            assertTrue(idMaskedCar187 > 0.60, "At 187.333s, black car license plate MUST be masked. MaskedIdentity ($idMaskedCar187) must be > 0.60.")

            // ----------------------------------------------------
            // CASE 2: VERIFY NO BLUR (No plates, must NOT be masked)
            // ----------------------------------------------------
            // Timestamp: 185.0s (5.0s in output)
            // The black car has not arrived yet. The plate region should be standard road texture.
            val imgOrig185 = decodeFrameAt(videoPath, 185.0, width, height)
            val imgMasked185 = decodeFrameAt(destFile.absolutePath, 5.0, width, height)

            val idOrigCar185 = calculateIdentityRate(imgOrig185, 842, 975, 35, 14)
            val idMaskedCar185 = calculateIdentityRate(imgMasked185, 842, 975, 35, 14)

            println("GROUND_TRUTH_TEST: [No Blur Verification at 185.0s]")
            println("  - Road Area (185.0s): OriginalIdentity=$idOrigCar185, MaskedIdentity=$idMaskedCar185")
            
            // Masked identity rate must remain low (typically < 30%), accounting for normal video compression smoothing
            assertTrue(idMaskedCar185 < 0.40, "At 185.0s, road area MUST NOT be masked. MaskedIdentity ($idMaskedCar185) must be < 0.40.")

        } finally {
            if (destFile.exists()) destFile.delete()
            fit.PlateCacheManager.deleteCache(videoPath)
        }
    }

    @Test
    fun testPlateMaskingPixelEdgeVariance() = runBlocking {
        val videoPath = "F:\\Insta360\\20260708\\VID_20260708_184458_001.mp4"
        val fitPath = "F:\\Insta360\\20260708\\Evening_Ride.fit"
        val videoStartUtc = "2026-07-08T09:44:58Z"

        val video = java.io.File(videoPath)
        val fitFile = java.io.File(fitPath)
        if (!video.exists() || !fitFile.exists()) {
            println("Skipping test: Real dataset files not found locally.")
            return@runBlocking
        }

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val outFileName = "test_masking_pixel_variance_${System.nanoTime()}"
        val destFile = java.io.File(tempDir, "$outFileName.mp4")

        // Clean cache before running to ensure fresh detection
        fit.PlateCacheManager.deleteCache(videoPath)

        try {
            val settings = HudSettings(
                blurLicensePlates = true,
                plateMaxSpeedKmh = 100.0,
                plateDetectionFps = 1.0,
                platePaddingSeconds = 1.0,
                plateMergeGapSeconds = 2.0
            )

            // 1. Execute masking encode pipeline (10s duration: 180s to 190s)
            val result = HudEncodePipeline.execute(
                s = settings,
                fitPath = fitPath,
                videoPath = videoPath,
                outputDir = tempDir.absolutePath,
                videoStartUtc = videoStartUtc,
                ranges = listOf(180.0 to 190.0),
                destFiles = listOf(destFile),
                shouldResume = false,
                onProgress = { _, _ -> },
                onFrame = {},
                cancelSupplier = { false },
                showLivePreviewSupplier = { false }
            )

            assertTrue(result.contains("Finished Successfully") || result.contains("Copied to Cloud"), "Encode should complete successfully")
            assertTrue(destFile.exists(), "Output file must be created")

            // 2. Decode the frame at 7.0s (original 187.0s) from both original and masked videos
            val width = 2704
            val height = 1520
            val imgOrig = decodeFrameAt(videoPath, 187.0, width, height)
            val imgMasked = decodeFrameAt(destFile.absolutePath, 7.0, width, height)

            // Test target coordinates (scaled 2.7K):
            // Red Truck Plate Area (inner margin): x=2089, y=831, w=25, h=18
            // Black Car Plate Area (inner margin): x=842, y=975, w=35, h=14
            val edgeOrigTruck = calculateEdgeStrength(imgOrig, 2089, 831, 25, 18)
            val edgeMaskedTruck = calculateEdgeStrength(imgMasked, 2089, 831, 25, 18)
            val idOrigTruck = calculateIdentityRate(imgOrig, 2089, 831, 25, 18)
            val idMaskedTruck = calculateIdentityRate(imgMasked, 2089, 831, 25, 18)

            val edgeOrigCar = calculateEdgeStrength(imgOrig, 842, 975, 35, 14)
            val edgeMaskedCar = calculateEdgeStrength(imgMasked, 842, 975, 35, 14)
            val idOrigCar = calculateIdentityRate(imgOrig, 842, 975, 35, 14)
            val idMaskedCar = calculateIdentityRate(imgMasked, 842, 975, 35, 14)

            println("DEBUG: Physical Pixel Analysis:")
            println("  - Red Truck Plate: OriginalEdge=$edgeOrigTruck, MaskedEdge=$edgeMaskedTruck, Ratio=${edgeMaskedTruck / edgeOrigTruck}")
            println("    * IdentityRate: Original=$idOrigTruck, Masked=$idMaskedTruck")
            println("  - Black Car Plate: OriginalEdge=$edgeOrigCar, MaskedEdge=$edgeMaskedCar, Ratio=${edgeMaskedCar / edgeOrigCar}")
            println("    * IdentityRate: Original=$idOrigCar, Masked=$idMaskedCar")

            // Assert that the masked area has significantly higher pixel identity rate due to mosaic block color injection
            assertTrue(idOrigTruck < 0.25, "Original truck area should have texture variation")
            assertTrue(idMaskedTruck > idOrigTruck + 0.20, "Masked truck identity rate ($idMaskedTruck) must be significantly higher than original ($idOrigTruck)")
            assertTrue(idMaskedCar > idOrigCar + 0.20, "Masked car identity rate ($idMaskedCar) must be significantly higher than original ($idOrigCar)")
        } finally {
            if (destFile.exists()) destFile.delete()
            fit.PlateCacheManager.deleteCache(videoPath)
        }
    }


    private fun uniqueVideoPath(label: String): String {
        return java.io.File(
            System.getProperty("java.io.tmpdir"),
            "fit-trimmer-$label-${System.nanoTime()}.mp4"
        ).absolutePath
    }

    @Test
    fun testGenerateVisualAuditDashboard() = runBlocking {
        val videoPath = "F:\\Insta360\\20260708\\VID_20260708_184458_001.mp4"
        val fitPath = "F:\\Insta360\\20260708\\Evening_Ride.fit"
        val videoStartUtc = "2026-07-08T09:44:58Z"

        val video = java.io.File(videoPath)
        val fitFile = java.io.File(fitPath)
        if (!video.exists() || !fitFile.exists()) {
            println("Skipping dashboard generation: Real dataset files not found locally.")
            return@runBlocking
        }

        val artifactDir = java.io.File("C:\\Users\\yuuji\\.gemini\\antigravity-cli\\brain\\e4dc6085-fa2e-4009-821d-e5810fdfb8cb")
        val imageDir = java.io.File(artifactDir, "visual_audit_images")
        if (!imageDir.exists()) imageDir.mkdirs()

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val destFile = java.io.File(tempDir, "temp_audit_dashboard.mp4")

        try {
            val settings = HudSettings(
                blurLicensePlates = true,
                plateMaxSpeedKmh = 100.0,
                plateDetectionFps = 1.0,
                platePaddingSeconds = 1.0,
                plateMergeGapSeconds = 2.0
            )

            // 1. Encode
            HudEncodePipeline.execute(
                s = settings,
                fitPath = fitPath,
                videoPath = videoPath,
                outputDir = tempDir.absolutePath,
                videoStartUtc = videoStartUtc,
                ranges = listOf(180.0 to 190.0),
                destFiles = listOf(destFile),
                shouldResume = false,
                onProgress = { _, _ -> },
                onFrame = {},
                cancelSupplier = { false },
                showLivePreviewSupplier = { false }
            )

            val cache = fit.PlateCacheManager.loadCache(videoPath)
            assertNotNull(cache, "Cache must exist")

            val ffmpegPath = fit.findFfmpegPath()
            val htmlBuilder = java.lang.StringBuilder()
            htmlBuilder.append("<!DOCTYPE html>\n<html>\n<head>\n")
            htmlBuilder.append("<meta charset='utf-8' />\n")
            htmlBuilder.append("<title>Mask Visual Audit Dashboard</title>\n")
            htmlBuilder.append("<style>\n")
            htmlBuilder.append("body { font-family: sans-serif; background: #121212; color: #e0e0e0; margin: 20px; }\n")
            htmlBuilder.append("h1 { color: #ffffff; }\n")
            htmlBuilder.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(350px, 1fr)); gap: 20px; }\n")
            htmlBuilder.append(".card { background: #1e1e1e; border: 1px solid #333; padding: 10px; border-radius: 8px; }\n")
            htmlBuilder.append(".card-title { font-weight: bold; margin-bottom: 8px; color: #4fc3f7; }\n")
            htmlBuilder.append(".comparison { display: flex; gap: 10px; }\n")
            htmlBuilder.append(".img-box { flex: 1; text-align: center; }\n")
            htmlBuilder.append("img { width: 100%; border-radius: 4px; border: 1px solid #444; }\n")
            htmlBuilder.append(".label { font-size: 12px; color: #aaa; margin-top: 4px; }\n")
            htmlBuilder.append("</style>\n</head>\n<body>\n")
            htmlBuilder.append("<h1>Mask Visual Audit Dashboard (180.0s - 190.0s, 0.3s steps)</h1>\n")
            htmlBuilder.append("<p>Verifies plate masking across 34 temporal coordinates comparing original vs masked output.</p>\n")
            htmlBuilder.append("<div class='grid'>\n")

            // 2. Sample 34 points (180.0s to 190.0s in 0.3s steps)
            var t = 180.0
            val width = 2704
            val height = 1520

            while (t <= 190.0) {
                val timeMs = (t * 1000).toLong()
                // Find matching cache record within 1s window, or find closest
                val record = cache.records.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
                
                // Scale coordinates or fallback
                var cropX = 832
                var cropY = 970
                var cropW = 120
                var cropH = 80

                if (record != null && record.boxes.isNotEmpty() && kotlin.math.abs(record.timeMs - timeMs) <= 1000) {
                    val box = record.boxes.first()
                    val scaledX = (box.x1 * (width.toDouble() / 3840.0)).toInt()
                    val scaledY = (box.y1 * (height.toDouble() / 2160.0)).toInt()
                    val scaledW = ((box.x2 - box.x1) * (width.toDouble() / 3840.0)).toInt()
                    val scaledH = ((box.y2 - box.y1) * (height.toDouble() / 2160.0)).toInt()

                    cropX = (scaledX - 30).coerceIn(0, width - 150)
                    cropY = (scaledY - 20).coerceIn(0, height - 100)
                    cropW = (scaledW + 60).coerceAtMost(width - cropX)
                    cropH = (scaledH + 40).coerceAtMost(height - cropY)
                } else {
                    // Fallback to black car target if time is near 187s, else center
                    if (t >= 186.0) {
                        cropX = 800
                        cropY = 940
                    } else if (t <= 183.0) {
                        cropX = 350
                        cropY = 1500
                    } else {
                        cropX = width / 2 - 100
                        cropY = height / 2 - 100
                    }
                    cropX = cropX.coerceIn(0, width - 150)
                    cropY = cropY.coerceIn(0, height - 100)
                }

                val label = String.format(java.util.Locale.US, "%.1fs", t)
                val origName = "crop_orig_${label}.jpg"
                val maskedName = "crop_masked_${label}.jpg"

                val origImgFile = java.io.File(imageDir, origName)
                val maskedImgFile = java.io.File(imageDir, maskedName)

                // Run FFmpeg crop
                val pbOrig = ProcessBuilder(
                    ffmpegPath, "-y", "-ss", String.format(java.util.Locale.US, "%.3f", t),
                    "-i", videoPath, "-vf", "scale=$width:$height,crop=$cropW:$cropH:$cropX:$cropY",
                    "-vframes", "1", origImgFile.absolutePath
                )
                pbOrig.redirectError(java.io.File("NUL")).start().waitFor()

                // Masked crop
                val elapsedInOutput = t - 180.0
                val pbMasked = ProcessBuilder(
                    ffmpegPath, "-y", "-ss", String.format(java.util.Locale.US, "%.3f", elapsedInOutput),
                    "-i", destFile.absolutePath, "-vf", "scale=$width:$height,crop=$cropW:$cropH:$cropX:$cropY",
                    "-vframes", "1", maskedImgFile.absolutePath
                )
                pbMasked.redirectError(java.io.File("NUL")).start().waitFor()

                // Append card
                htmlBuilder.append("  <div class='card'>\n")
                htmlBuilder.append("    <div class='card-title'>Timestamp: $label</div>\n")
                htmlBuilder.append("    <div class='comparison'>\n")
                htmlBuilder.append("      <div class='img-box'>\n")
                htmlBuilder.append("        <img src='visual_audit_images/$origName' />\n")
                htmlBuilder.append("        <div class='label'>Original</div>\n")
                htmlBuilder.append("      </div>\n")
                htmlBuilder.append("      <div class='img-box'>\n")
                htmlBuilder.append("        <img src='visual_audit_images/$maskedName' />\n")
                htmlBuilder.append("        <div class='label'>Masked</div>\n")
                htmlBuilder.append("      </div>\n")
                htmlBuilder.append("    </div>\n")
                htmlBuilder.append("  </div>\n")

                t += 0.3
            }

            htmlBuilder.append("</div>\n</body>\n</html>")
            val reportFile = java.io.File(artifactDir, "visual_audit_report.html")
            reportFile.writeText(htmlBuilder.toString())
            println("DASHBOARD_CREATED: ${reportFile.absolutePath}")

        } finally {
            if (destFile.exists()) destFile.delete()
        }
    }
}
