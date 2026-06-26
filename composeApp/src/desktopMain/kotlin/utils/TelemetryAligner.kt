package utils

import fit.FitParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

object TelemetryAligner {
    
    /**
     * Aligns video start time with fit telemetry using IMU correlation.
     * Launches the bundled Python script in background.
     * Returns the videoStartUtc String (ISO-8601) if successful, null otherwise.
     */
    suspend fun alignVideoWithTelemetry(
        videoPath: String,
        telemetryPoints: List<FitParser.TelemetryPoint>,
        approxStartUtc: String = ""
    ): String? = withContext(Dispatchers.IO) {
        if (telemetryPoints.isEmpty() || videoPath.isEmpty()) {
            println("DEBUG: Auto alignment skipped (empty inputs)")
            return@withContext null
        }
        
        var tempTelemetryFile: File? = null
        var tempScriptFile: File? = null
        
        try {
            // 1. Dump telemetry points to a temporary JSON file
            tempTelemetryFile = File.createTempFile("fit_telemetry_", ".json")
            
            val jsonBuilder = StringBuilder()
            jsonBuilder.append("[\n")
            for (i in telemetryPoints.indices) {
                val pt = telemetryPoints[i]
                jsonBuilder.append("  {\n")
                jsonBuilder.append("    \"ts\": ").append(pt.timestamp).append(",\n")
                jsonBuilder.append("    \"speedKmh\": ").append(pt.speed).append("\n")
                jsonBuilder.append("  }")
                if (i < telemetryPoints.size - 1) {
                    jsonBuilder.append(",")
                }
                jsonBuilder.append("\n")
            }
            jsonBuilder.append("]")
            tempTelemetryFile.writeText(jsonBuilder.toString())
            
            // 2. Extract align_telemetry.py from resources to a temp file
            tempScriptFile = File.createTempFile("align_telemetry_", ".py")
            val resourceStream = TelemetryAligner::class.java.getResourceAsStream("/align_telemetry.py")
            if (resourceStream == null) {
                println("DEBUG: Could not find /align_telemetry.py in resources. Trying dev file fallback.")
                val devFile = File("composeApp/src/desktopMain/resources/align_telemetry.py")
                if (devFile.exists()) {
                    Files.copy(devFile.toPath(), tempScriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    println("ERROR: Python script not found in resources or dev fallback!")
                    return@withContext null
                }
            } else {
                resourceStream.use { input ->
                    Files.copy(input, tempScriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            
            // 3. Launch Python process
            val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
            val pythonCmd = if (isWindows) "python" else "python3"
            
            val pbArgs = mutableListOf(
                pythonCmd,
                tempScriptFile.absolutePath,
                "--video", videoPath,
                "--telemetry", tempTelemetryFile.absolutePath
            )
            
            if (approxStartUtc.isNotEmpty()) {
                try {
                    val instant = Instant.parse(approxStartUtc)
                    val garminTs = (instant.toEpochMilli() / 1000.0) - 631065600.0
                    pbArgs.add("--approx-start-ts")
                    pbArgs.add(garminTs.toString())
                } catch (e: Exception) {
                    println("DEBUG: Failed to parse approxStartUtc for alignment command: ${e.message}")
                }
            }
            
            println("DEBUG: Launching auto alignment with: ${pbArgs.joinToString(" ")}")
            val pb = ProcessBuilder(pbArgs)
            pb.redirectErrorStream(true)
            
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                println("DEBUG: Auto alignment output: $output")
                
                // Parse stdout JSON manually using regex to avoid dependency issues
                val statusRegex = "\"status\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val tsRegex = "\"video_start_ts\"\\s*:\\s*([\\d.]+)".toRegex()
                
                val statusMatch = statusRegex.find(output)?.groupValues?.get(1)
                val tsMatch = tsRegex.find(output)?.groupValues?.get(1)
                
                if (statusMatch == "success" && tsMatch != null) {
                    val epochSec = tsMatch.toDouble()
                    // Garmin epoch is Dec 31 1989. Unix epoch is Jan 1 1970.
                    // Difference is exactly 631065600 seconds.
                    val unixSec = (epochSec + 631065600.0).toLong()
                    val unixNano = ((epochSec + 631065600.0 - unixSec) * 1_000_000_000).toLong()
                    val instant = Instant.ofEpochSecond(unixSec, unixNano)
                    
                    val utcStr = instant.toString()
                    return@withContext utcStr
                } else {
                    val msgRegex = "\"message\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val msgMatch = msgRegex.find(output)?.groupValues?.get(1)
                    println("ERROR: Auto alignment script reported failure: $msgMatch")
                }
            } else {
                println("ERROR: Auto alignment script exited with non-zero code $exitCode. Output: $output")
            }
            
        } catch (e: Exception) {
            println("ERROR: Exception during auto alignment execution: ${e.message}")
            e.printStackTrace()
        } finally {
            // Safely delete temp files
            try {
                println("DEBUG: Preserving temp telemetry file: ${tempTelemetryFile?.absolutePath}")
                println("DEBUG: Preserving temp script file: ${tempScriptFile?.absolutePath}")
                // tempTelemetryFile?.delete()
                // tempScriptFile?.delete()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return@withContext null
    }
}
