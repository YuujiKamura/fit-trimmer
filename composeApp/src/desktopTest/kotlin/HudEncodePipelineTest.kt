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
                    progress(42.5f)
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

    private fun uniqueVideoPath(label: String): String {
        return java.io.File(
            System.getProperty("java.io.tmpdir"),
            "fit-trimmer-$label-${System.nanoTime()}.mp4"
        ).absolutePath
    }
}
