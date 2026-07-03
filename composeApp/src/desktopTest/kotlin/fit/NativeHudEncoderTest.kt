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

    @Test
    fun testHudRendererScopeConfigRules() {
        // Create dummy telemetry points for testing scope behavior
        val ptStart = FitParser.TelemetryPoint(timestamp = 100.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 10.0, grade = 0.0)
        val ptMid = FitParser.TelemetryPoint(timestamp = 200.0, speed = 15.0, power = 150.0, cadence = 85.0, heartRate = 160.0, elevation = 50.0, grade = 2.0)
        val ptEnd = FitParser.TelemetryPoint(timestamp = 300.0, speed = 12.0, power = 120.0, cadence = 82.0, heartRate = 140.0, elevation = 20.0, grade = -1.0)
        
        val fullList = listOf(ptStart, ptMid, ptEnd)
        val trimmedList = listOf(ptMid, ptEnd) // Simulation of trimmed video scope
        
        // Define canvas mock to intercept text draw calls
        val textDrawn = mutableListOf<String>()
        val mockCanvas = object : HudCanvas {
            override val width = 1920f
            override val height = 1080f
            override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
                textDrawn.add(text)
            }
            override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {}
            override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {}
            override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {}
            override fun getTextWidth(text: String, size: Float, bold: Boolean) = text.length * 10f
        }

        // Case 1: scope = "video" for elevation, "activity" for heart rate (Default setup)
        val config1 = HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f, xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f,
            elevationGraphScope = "video",
            heartRateAccumulationScope = "activity"
        )
        val renderer1 = HudRenderer(config1)
        textDrawn.clear()
        renderer1.renderFrame(mockCanvas, ptEnd, fullList, trimmedList, emptyList(), 1.0f, true)
        
        // When elevationGraphScope is "video", start alt should be ptMid.elevation (50.0m -> "50m") and end alt ptEnd.elevation (20.0m -> "20m")
        assertTrue(textDrawn.contains("50m"), "Should draw start elevation from the video trimmed range (50m)")
        assertTrue(textDrawn.contains("20m"), "Should draw end elevation from the video trimmed range (20m)")
        assertFalse(textDrawn.contains("10m"), "Should NOT draw start elevation from the activity range when scope is video")

        // Case 2: scope = "activity" for elevation
        val config2 = HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f, xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f,
            elevationGraphScope = "activity",
            heartRateAccumulationScope = "activity"
        )
        val renderer2 = HudRenderer(config2)
        textDrawn.clear()
        renderer2.renderFrame(mockCanvas, ptEnd, fullList, trimmedList, emptyList(), 1.0f, true)
        
        // When elevationGraphScope is "activity", start alt should be ptStart.elevation (10.0m -> "10m")
        assertTrue(textDrawn.contains("10m"), "Should draw start elevation from the full activity range (10m)")
        assertTrue(textDrawn.contains("20m"), "Should draw end elevation from the full activity range (20m)")
        assertFalse(textDrawn.contains("50m") && !textDrawn.contains("10m"), "Should contain full range alt, not just trimmed")
    }

    @Test
    fun testHudRendererComponentVisibilityAndWeightConfigRules() {
        val pt = FitParser.TelemetryPoint(
            timestamp = 100.0, speed = 10.0, power = 180.0, cadence = 80.0, heartRate = 120.0, elevation = 10.0, grade = 0.0
        )
        val list = listOf(pt)
        val textDrawn = mutableListOf<String>()
        val mockCanvas = object : HudCanvas {
            override val width = 1920f
            override val height = 1080f
            override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
                textDrawn.add(text)
            }
            override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {}
            override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {}
            override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {}
            override fun getTextWidth(text: String, size: Float, bold: Boolean) = text.length * 10f
        }

        // Case 1: All items shown, bodyWeightKg = 60.0. 180W / 60kg = 3.0 w/kg.
        val config1 = HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f, xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f,
            showSpeed = true, showCadence = true, showHeartRate = true, showPower = true, showWkg = true, showGrade = true, showElevation = true,
            bodyWeightKg = 60.0
        )
        val renderer1 = HudRenderer(config1)
        textDrawn.clear()
        renderer1.renderFrame(mockCanvas, pt, list, list, emptyList(), 1.0f, true)
        
        assertTrue(textDrawn.contains("SPEED"), "Should draw Speed label")
        assertTrue(textDrawn.contains("W/KG"), "Should draw W/KG label")
        assertTrue(textDrawn.contains("3.0"), "Should draw 3.0 w/kg based on 180W / 60kg")

        // Case 2: W/KG and Cadence hidden
        val config2 = HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f, xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f,
            showSpeed = true, showCadence = false, showHeartRate = true, showPower = true, showWkg = false, showGrade = true, showElevation = true,
            bodyWeightKg = 60.0
        )
        val renderer2 = HudRenderer(config2)
        textDrawn.clear()
        renderer2.renderFrame(mockCanvas, pt, list, list, emptyList(), 1.0f, true)
        
        assertTrue(textDrawn.contains("SPEED"), "Should still draw Speed")
        assertFalse(textDrawn.contains("W/KG"), "Should NOT draw W/KG when showWkg is false")
        assertFalse(textDrawn.contains("CADENCE"), "Should NOT draw Cadence when showCadence is false")
    }
}
