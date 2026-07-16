package utils

import fit.NativeHudEncoder
import fit.HudSettings
import java.io.File
import kotlin.test.Test
import utils.PlateDetectionManager

class DemoEncoder {
    @Test
    fun testEncodeDemo() {
        val videoPath = "H:\\マイドライブ\\Insta360\\20260614\\VID_20260614_163204_003.mp4"
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            println("Error: Video file not found: $videoPath")
            return
        }

        val scratchDir = File("temp_work")
        if (!scratchDir.exists()) scratchDir.mkdirs()

        val outputMp4 = File(scratchDir, "demo_blur_150frames.mp4")

        // 0.5秒間 (約15 frames)
        val startTimeSec = 398.0
        val durationSec = 0.5
        val endTimeSec = startTimeSec + durationSec

        println("⚡ Started demo encode for $durationSec seconds (approx 15 frames)")

        // Delete cache explicitly to force full rescanning for this small segment if we want to bypass previous results
        val cacheFile = fit.PlateCacheManager.getPlatesFile(videoPath)
        if (cacheFile != null && cacheFile.exists()) {
            // Force rescan for debugging
            cacheFile.delete()
        }

        val settings = HudSettings(
            blurLicensePlates = true,
            exportResolution = "1080p", // 1080p for fast encoding
            plateDetectionFps = 30.0 // 30fps to test gap filling correctly
        )

        // Run plate detection only for the 5 second range
        println("🔍 Scanning for plates...")
        kotlinx.coroutines.runBlocking {
            PlateDetectionManager.detect(
                videoPath = videoPath,
                telemetryPoints = emptyList(),
                adjustedStartUtc = "2026-06-14T08:02:06Z",
                onProgress = { p, _ -> print("\rProgress: ${p*100}%") },
                onCancel = { false },
                settings = settings,
                scanRanges = listOf(startTimeSec to endTimeSec)
            )
        }
        println("\n🔍 Scan complete")

        val generatedCache = fit.PlateCacheManager.loadCache(videoPath)
        println("Generated Cache Records: ${generatedCache?.records?.size ?: 0}")

        val encoder = NativeHudEncoder(settings)

        try {
            println("🎥 Starting NativeHudEncoder...")
            encoder.encode(
                fitPath = "", // No fit file for this demo
                videoPath = videoPath,
                output = outputMp4.absolutePath,
                startUtc = "2026-06-14T08:02:06Z",
                maxDurationSeconds = Math.ceil(durationSec).toInt(),
                trimStartSeconds = startTimeSec,
                trimEndSeconds = endTimeSec
            )
            println("✅ Demo encode complete: ${outputMp4.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
