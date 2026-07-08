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

    private fun uniqueVideoPath(label: String): String {
        return java.io.File(
            System.getProperty("java.io.tmpdir"),
            "fit-trimmer-$label-${System.nanoTime()}.mp4"
        ).absolutePath
    }
}
