package utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryAlignerTest {

    private fun runAlignmentTest(method: String, approxStartTs: String, expectedTs: Double, tolerance: Double = 0.01) {
        // 1. Copy resources to temp files
        val tempTelemetryFile = File.createTempFile("test_fit_telemetry_", ".json").apply { deleteOnExit() }
        val tempVibFile = File.createTempFile("test_video_vibration_", ".json").apply { deleteOnExit() }
        val tempScriptFile = File.createTempFile("test_align_telemetry_", ".py").apply { deleteOnExit() }

        // Copy telemetry JSON
        val telemetryStream = javaClass.getResourceAsStream("/fit_telemetry.json")
        assertNotNull(telemetryStream, "fit_telemetry.json not found in resources")
        telemetryStream.use { input ->
            Files.copy(input, tempTelemetryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // Copy vibration JSON
        val vibStream = javaClass.getResourceAsStream("/video_vibration_1hz.json")
        assertNotNull(vibStream, "video_vibration_1hz.json not found in resources")
        vibStream.use { input ->
            Files.copy(input, tempVibFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // Copy python script
        val scriptStream = javaClass.getResourceAsStream("/align_telemetry.py")
        assertNotNull(scriptStream, "align_telemetry.py not found in resources")
        scriptStream.use { input ->
            Files.copy(input, tempScriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // 2. Prepare Python process execution
        val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
        val pythonCmd = if (isWindows) "python" else "python3"

        val pbArgs = mutableListOf(
            pythonCmd,
            tempScriptFile.absolutePath,
            "--video-vib-1hz", tempVibFile.absolutePath,
            "--telemetry", tempTelemetryFile.absolutePath,
            "--approx-start-ts", approxStartTs,
            "--method", method
        )

        println("DEBUG TEST: Running cmd ($method): ${pbArgs.joinToString(" ")}")
        val pb = ProcessBuilder(pbArgs)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        println("DEBUG TEST ($method) exit code: $exitCode, output: $output")

        // 3. Verify assertions
        assertEquals(0, exitCode, "Python script exited with error")
        
        val statusRegex = "\"status\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val tsRegex = "\"video_start_ts\"\\s*:\\s*([\\d.]+)".toRegex()

        val statusMatch = statusRegex.find(output)?.groupValues?.get(1)
        val tsMatch = tsRegex.find(output)?.groupValues?.get(1)

        assertEquals("success", statusMatch)
        assertNotNull(tsMatch)

        val resultTs = tsMatch.toDouble()
        assertTrue(
            Math.abs(resultTs - expectedTs) < tolerance,
            "Aligned timestamp $resultTs was not close enough to expected $expectedTs (diff: ${Math.abs(resultTs - expectedTs)})"
        )
    }

    @Test
    fun testAlignmentWithBinaryMethod() {
        // Binary correlation is expected to yield approximately 1151309997.33551
        runAlignmentTest(
            method = "binary",
            approxStartTs = "1151310000.0",
            expectedTs = 1151309997.33551
        )
    }

    @Test
    fun testAlignmentWithAccelerationMethod() {
        // Acceleration correlation is expected to yield approximately 1151310125.33551
        // (corresponding to 1151310128.0 - 2.664490 fallback offset)
        runAlignmentTest(
            method = "acceleration",
            approxStartTs = "1151310000.0",
            expectedTs = 1151310125.33551
        )
    }
}
