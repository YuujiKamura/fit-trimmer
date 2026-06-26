package utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryAlignerTest {

    // --- Helper for Python-based test ---
    private fun runPythonAlignmentTest(method: String, approxStartTs: String, expectedTs: Double, tolerance: Double = 0.01) {
        val tempTelemetryFile = File.createTempFile("test_fit_telemetry_", ".json").apply { deleteOnExit() }
        val tempVibFile = File.createTempFile("test_video_vibration_", ".json").apply { deleteOnExit() }
        val tempScriptFile = File.createTempFile("test_align_telemetry_", ".py").apply { deleteOnExit() }

        val telemetryStream = javaClass.getResourceAsStream("/fit_telemetry.json")
        assertNotNull(telemetryStream)
        telemetryStream.use { input ->
            Files.copy(input, tempTelemetryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val vibStream = javaClass.getResourceAsStream("/video_vibration_1hz.json")
        assertNotNull(vibStream)
        vibStream.use { input ->
            Files.copy(input, tempVibFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val scriptStream = javaClass.getResourceAsStream("/align_telemetry.py")
        assertNotNull(scriptStream)
        scriptStream.use { input ->
            Files.copy(input, tempScriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

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

        val pb = ProcessBuilder(pbArgs)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode)
        val statusRegex = "\"status\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val tsRegex = "\"video_start_ts\"\\s*:\\s*([\\d.]+)".toRegex()

        val statusMatch = statusRegex.find(output)?.groupValues?.get(1)
        val tsMatch = tsRegex.find(output)?.groupValues?.get(1)

        assertEquals("success", statusMatch)
        assertNotNull(tsMatch)

        val resultTs = tsMatch.toDouble()
        assertTrue(Math.abs(resultTs - expectedTs) < tolerance)
    }

    @Test
    fun testAlignmentWithBinaryMethodPython() {
        runPythonAlignmentTest("binary", "1151310000.0", 1151309997.33551)
    }

    @Test
    fun testAlignmentWithAccelerationMethodPython() {
        runPythonAlignmentTest("acceleration", "1151310000.0", 1151310125.33551)
    }

    // --- Kotlin Native Port Validation ---

    private fun gaussianFilter1D(input: DoubleArray, sigma: Double): DoubleArray {
        val radius = Math.ceil(4.0 * sigma).toInt()
        val size = 2 * radius + 1
        val kernel = DoubleArray(size)
        var sum = 0.0
        for (i in -radius..radius) {
            val x = i.toDouble()
            val v = Math.exp(-(x * x) / (2.0 * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in 0 until size) {
            kernel[i] /= sum
        }

        val n = input.size
        val output = DoubleArray(n)
        for (i in 0 until n) {
            var value = 0.0
            for (k in -radius..radius) {
                val j = i + k
                val refIdx = when {
                    j < 0 -> -j
                    j >= n -> 2 * n - 2 - j
                    else -> j
                }
                val safeIdx = Math.max(0, Math.min(n - 1, refIdx))
                value += input[safeIdx] * kernel[k + radius]
            }
            output[i] = value
        }
        return output
    }

    private fun correlateValid(a: DoubleArray, v: DoubleArray): DoubleArray {
        val n = a.size
        val m = v.size
        if (n < m) return DoubleArray(0)
        val outSize = n - m + 1
        val output = DoubleArray(outSize)
        for (i in 0 until outSize) {
            var sum = 0.0
            for (j in 0 until m) {
                sum += a[i + j] * v[j]
            }
            output[i] = sum
        }
        return output
    }

    private fun normalize(arr: DoubleArray): DoubleArray {
        val mean = arr.average()
        val variance = arr.map { (it - mean) * (it - mean) }.average()
        val std = Math.sqrt(variance)
        val output = DoubleArray(arr.size)
        for (i in arr.indices) {
            output[i] = (arr[i] - mean) / (std + 1e-6)
        }
        return output
    }

    private fun interpolate(x: DoubleArray, xp: DoubleArray, fp: DoubleArray): DoubleArray {
        val output = DoubleArray(x.size)
        for (i in x.indices) {
            val target = x[i]
            if (target <= xp.first()) {
                output[i] = fp.first()
                continue
            }
            if (target >= xp.last()) {
                output[i] = fp.last()
                continue
            }
            val idx = xp.binarySearch(target)
            if (idx >= 0) {
                output[i] = fp[idx]
            } else {
                val insertIdx = -idx - 1
                val idx0 = insertIdx - 1
                val idx1 = insertIdx
                val t = (target - xp[idx0]) / (xp[idx1] - xp[idx0])
                output[i] = fp[idx0] + t * (fp[idx1] - fp[idx0])
            }
        }
        return output
    }

    private fun runKotlinNativeAlignment(method: String, approxStartTs: Double): Double {
        // Load resources
        val telemetryStream = javaClass.getResourceAsStream("/fit_telemetry.json")
        assertNotNull(telemetryStream)
        val telemetryJsonText = telemetryStream.bufferedReader().use { it.readText() }
        
        val vibStream = javaClass.getResourceAsStream("/video_vibration_1hz.json")
        assertNotNull(vibStream)
        val vibJsonText = vibStream.bufferedReader().use { it.readText() }

        // Parse JSON manually using kotlinx.serialization
        val telemetryArray = Json.parseToJsonElement(telemetryJsonText).jsonArray
        val fitTs = DoubleArray(telemetryArray.size)
        val fitSpeed = DoubleArray(telemetryArray.size)
        for (i in telemetryArray.indices) {
            val obj = telemetryArray[i].jsonObject
            fitTs[i] = obj["ts"]!!.jsonPrimitive.double
            fitSpeed[i] = obj["speedKmh"]!!.jsonPrimitive.double
        }

        val vibArray = Json.parseToJsonElement(vibJsonText).jsonArray
        val vVib = DoubleArray(vibArray.size) { vibArray[it].jsonPrimitive.double }

        // 1. Resample FIT speed to 1Hz grid
        val startTs = fitTs.first()
        val endTs = fitTs.last()
        val fitGridSize = (endTs - startTs).toInt() + 1
        val fitGrid = DoubleArray(fitGridSize) { startTs + it }
        val fitSpeedGrid = interpolate(fitGrid, fitTs, fitSpeed)

        val fitSigNorm: DoubleArray
        val vSigNorm: DoubleArray

        if (method == "binary") {
            val vSig = gaussianFilter1D(vVib, 3.0)
            
            // Calculate 10th percentile
            val sortedVSig = vSig.sorted()
            val pct10Idx = (sortedVSig.size * 0.10).toInt()
            val pct10 = sortedVSig[Math.max(0, Math.min(sortedVSig.size - 1, pct10Idx))]
            val vThresh = Math.max(20.0, pct10 * 1.5)
            
            val vMov = DoubleArray(vSig.size) { if (vSig[it] > vThresh) 1.0 else 0.0 }
            val fitMov = DoubleArray(fitSpeedGrid.size) { if (fitSpeedGrid[it] > 2.0) 1.0 else 0.0 }

            val fitSigSmooth = gaussianFilter1D(fitMov, 3.0)
            val vSigSmooth = gaussianFilter1D(vMov, 3.0)

            fitSigNorm = normalize(fitSigSmooth)
            vSigNorm = normalize(vSigSmooth)
        } else {
            // Acceleration Method
            val fitAcc = DoubleArray(fitSpeedGrid.size)
            for (i in 0 until fitSpeedGrid.size - 1) {
                fitAcc[i] = Math.abs(fitSpeedGrid[i + 1] - fitSpeedGrid[i])
            }
            fitAcc[fitSpeedGrid.size - 1] = 0.0

            val vSig = gaussianFilter1D(vVib, 3.0)
            val vAcc = DoubleArray(vSig.size)
            for (i in 0 until vSig.size - 1) {
                vAcc[i] = Math.abs(vSig[i + 1] - vSig[i])
            }
            vAcc[vSig.size - 1] = 0.0

            val fitSigSmooth = gaussianFilter1D(fitAcc, 3.0)
            val vSigSmooth = gaussianFilter1D(vAcc, 3.0)

            fitSigNorm = normalize(fitSigSmooth)
            vSigNorm = normalize(vSigSmooth)
        }

        // Cross-correlate
        val corr = correlateValid(fitSigNorm, vSigNorm)
        assertTrue(corr.isNotEmpty(), "Correlation output is empty")

        // Search window limit
        val window = 900.0
        val minTs = approxStartTs - window
        val maxTs = approxStartTs + window

        var bestIdx = -1
        var maxCorr = Double.NEGATIVE_INFINITY

        for (i in corr.indices) {
            val ts = fitGrid[i]
            if (ts in minTs..maxTs) {
                if (corr[i] > maxCorr) {
                    maxCorr = corr[i]
                    bestIdx = i
                }
            }
        }

        if (bestIdx == -1) {
            // fallback to global argmax
            for (i in corr.indices) {
                if (corr[i] > maxCorr) {
                    maxCorr = corr[i]
                    bestIdx = i
                }
            }
        }

        val videoStartTs = fitGrid[bestIdx]
        val firstFileImuOffset = 2.664490
        return videoStartTs - firstFileImuOffset
    }

    @Test
    fun testKotlinNativeBinaryAlignment() {
        val result = runKotlinNativeAlignment("binary", 1151310000.0)
        val expected = 1151309997.33551
        val tolerance = 0.01
        println("DEBUG TEST KOTLIN (binary) result: $result, expected: $expected")
        assertTrue(
            Math.abs(result - expected) < tolerance,
            "Kotlin native binary alignment $result was not close to expected $expected"
        )
    }

    @Test
    fun testKotlinNativeAccelerationAlignment() {
        val result = runKotlinNativeAlignment("acceleration", 1151310000.0)
        val expected = 1151310125.33551
        val tolerance = 0.01
        println("DEBUG TEST KOTLIN (acceleration) result: $result, expected: $expected")
        assertTrue(
            Math.abs(result - expected) < tolerance,
            "Kotlin native acceleration alignment $result was not close to expected $expected"
        )
    }
}

