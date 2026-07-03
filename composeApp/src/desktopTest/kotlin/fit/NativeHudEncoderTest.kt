package fit

import org.junit.Test
import kotlin.test.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class NativeHudEncoderTest {

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }

    private fun getTempOutputFile(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        return File(tempDir, "fit_trimmer_test_out_${UUID.randomUUID().toString().take(8)}.mp4")
    }

    @Test
    fun testEncodeSuccess() {
        if (!isWindows()) return

        val videoPath = "C:/Users/yuuji/fit-trimmer/composeApp/scratch/crop_test.mp4"
        val fitPath = "C:/Users/yuuji/fit-trimmer/Lunch_Ride.fit"
        val startUtc = "1904-01-01T00:00:00Z"
        val outFile = getTempOutputFile()

        if (!File(videoPath).exists() || !File(fitPath).exists()) {
            println("Skipping testEncodeSuccess: test resources not found.")
            return
        }

        val settings = HudSettings().copy(
            valSize = 71.8f,
            tightness = -10f,
            spacing = 23f,
            language = "ja",
            enableRoadDetection = false,
            blurLicensePlates = false
        )

        var progressCalled = false
        val encoder = NativeHudEncoder(
            settings = settings,
            onProgress = { prog, status ->
                progressCalled = true
                println("Progress: $prog - $status")
            },
            cancelSupplier = { false }
        )

        try {
            encoder.encode(
                fitPath = fitPath,
                videoPath = videoPath,
                output = outFile.absolutePath,
                startUtc = startUtc,
                maxDurationSeconds = -1,
                trimStartSeconds = 0.0,
                trimEndSeconds = 2.0,
                shouldResume = false
            )
            assertTrue(outFile.exists(), "Output file must be generated upon success")
            assertTrue(outFile.length() > 0L, "Output file must not be empty")
            assertTrue(progressCalled, "Progress callback must be invoked")
        } finally {
            if (outFile.exists()) outFile.delete()
        }
    }

    @Test
    fun testEncodeInvalidTelemetryMismatch() {
        if (!isWindows()) return

        val videoPath = "C:/Users/yuuji/fit-trimmer/composeApp/scratch/crop_test.mp4"
        val fitPath = "C:/Users/yuuji/fit-trimmer/Lunch_Ride.fit"
        // Intentionally provide mismatching start UTC that has no overlapping telemetry points.
        // The encoder must survive this discrepancy (robustness check), fallback gracefully,
        // and still export the video without crashing the JVM.
        val mismatchedStartUtc = "2026-07-02T17:53:06Z"
        val outFile = getTempOutputFile()

        if (!File(videoPath).exists() || !File(fitPath).exists()) {
            return
        }

        val encoder = NativeHudEncoder(
            settings = HudSettings(),
            cancelSupplier = { false }
        )

        try {
            encoder.encode(
                fitPath = fitPath,
                videoPath = videoPath,
                output = outFile.absolutePath,
                startUtc = mismatchedStartUtc,
                maxDurationSeconds = -1,
                trimStartSeconds = 0.0,
                trimEndSeconds = 2.0,
                shouldResume = false
            )
            assertTrue(outFile.exists(), "Output file must be generated even if telemetry mismatched (fallback success)")
            assertTrue(outFile.length() > 0L, "Output video must be valid")
        } finally {
            if (outFile.exists()) outFile.delete()
        }
    }

    @Test
    fun testEncodeMissingVideoFile() {
        if (!isWindows()) return

        val invalidVideoPath = "C:/Users/yuuji/fit-trimmer/composeApp/scratch/non_existent_video.mp4"
        val fitPath = "C:/Users/yuuji/fit-trimmer/Lunch_Ride.fit"
        val outFile = getTempOutputFile()

        if (!File(fitPath).exists()) {
            return
        }

        val encoder = NativeHudEncoder(
            settings = HudSettings(),
            cancelSupplier = { false }
        )

        assertFails {
            encoder.encode(
                fitPath = fitPath,
                videoPath = invalidVideoPath,
                output = outFile.absolutePath,
                startUtc = "1904-01-01T00:00:00Z",
                maxDurationSeconds = -1,
                trimStartSeconds = 0.0,
                trimEndSeconds = 2.0,
                shouldResume = false
            )
        }
        assertFalse(outFile.exists(), "Output file must not exist on missing file exception")
    }

    @Test
    fun testEncodeRapidCancellation() {
        if (!isWindows()) return

        val videoPath = "C:/Users/yuuji/fit-trimmer/composeApp/scratch/crop_test.mp4"
        val fitPath = "C:/Users/yuuji/fit-trimmer/Lunch_Ride.fit"
        val outFile = getTempOutputFile()

        if (!File(videoPath).exists() || !File(fitPath).exists()) {
            return
        }

        // Simulate cancel trigger being flipped shortly after encoding starts
        val isCanceled = AtomicBoolean(false)
        val encoder = NativeHudEncoder(
            settings = HudSettings(),
            cancelSupplier = { isCanceled.get() }
        )

        // Trigger cancellation in 300ms (during encoder execution)
        val cancelThread = Thread {
            Thread.sleep(300)
            isCanceled.set(true)
            println("TEST: Encoding manual cancel triggered!")
        }

        try {
            cancelThread.start()
            assertFails {
                encoder.encode(
                    fitPath = fitPath,
                    videoPath = videoPath,
                    output = outFile.absolutePath,
                    startUtc = "1904-01-01T00:00:00Z",
                    maxDurationSeconds = -1,
                    trimStartSeconds = 0.0,
                    trimEndSeconds = 2.0,
                    shouldResume = false
                )
            }
        } finally {
            try { cancelThread.join(1000) } catch(e: Exception) {}
            if (outFile.exists()) outFile.delete()
        }
    }
}
