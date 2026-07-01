package fit

import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.InputStreamReader
import java.io.FileOutputStream

var globalActiveJobDir: java.io.File? = null

fun findFfmpegPath(): String {
    val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    val isMac = os.contains("mac")
    
    // 1. Try bundled resource FFmpeg (Highest priority to guarantee stable behavior)
    try {
        val isZip = isWindows
        val resourcePath = when {
            isWindows -> "/bin/win/ffmpeg.zip"
            isMac -> "/bin/mac/ffmpeg"
            else -> "/bin/linux/ffmpeg"
        }
        
        // Use class loader of NativeHudEncoder to load the resource
        val resourceUrl = NativeHudEncoder::class.java.getResource(resourcePath)
        if (resourceUrl != null) {
            val workDir = PathResolver.getTempWorkDir()
            val binDir = File(workDir, "bin")
            if (!binDir.exists()) binDir.mkdirs()
            
            val destFile = File(binDir, if (isWindows) "ffmpeg.exe" else "ffmpeg")
            
            // Find correct expected size (uncompressed size if ZIP, raw size otherwise)
            val expectedLength = if (isZip) {
                try {
                    resourceUrl.openStream().use { s ->
                        java.util.zip.ZipInputStream(s).use { zip ->
                            var entry = zip.nextEntry
                            var len = -1L
                            while (entry != null) {
                                if (entry.name == "ffmpeg.exe" || entry.name == "ffmpeg") {
                                    len = entry.size
                                    break
                                }
                                entry = zip.nextEntry
                            }
                            len
                        }
                    }
                } catch (e: Exception) {
                    -1L
                }
            } else {
                try {
                    val conn = resourceUrl.openConnection()
                    val len = conn.contentLengthLong
                    conn.getInputStream().close()
                    len
                } catch (e: Exception) {
                    -1L
                }
            }
            
            // Extract if not exists, empty, or size mismatches (e.g. upgraded from GPL to LGPL)
            if (!destFile.exists() || destFile.length() == 0L || (expectedLength > 0L && destFile.length() != expectedLength)) {
                println("📥 Extracting bundled FFmpeg to: ${destFile.absolutePath} (expected size: $expectedLength, local size: ${destFile.length()})")
                
                resourceUrl.openStream().use { stream ->
                    if (isZip) {
                        java.util.zip.ZipInputStream(stream).use { zipStream ->
                            var entry = zipStream.nextEntry
                            while (entry != null) {
                                if (entry.name == "ffmpeg.exe" || entry.name == "ffmpeg") {
                                    FileOutputStream(destFile).use { output ->
                                        zipStream.copyTo(output)
                                    }
                                    break
                                }
                                entry = zipStream.nextEntry
                            }
                        }
                    } else {
                        FileOutputStream(destFile).use { output ->
                            stream.copyTo(output)
                        }
                    }
                }
                destFile.setExecutable(true)
                println("✅ Bundle FFmpeg extracted successfully.")
            }
            
            if (destFile.exists() && destFile.length() > 0L) {
                return destFile.absolutePath
            }
        }
    } catch (e: Exception) {
        println("⚠️ Failed to extract bundled FFmpeg: ${e.message}. Falling back to system detection.")
        e.printStackTrace()
    }

    // 2. Query Python's imageio_ffmpeg (Fallback)
    try {
        val pythonCmd = if (isWindows) "python.exe" else "python"
        val pb = ProcessBuilder(pythonCmd, "-c", "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())")
        val p = pb.start()
        val path = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (p.exitValue() == 0 && path.isNotEmpty() && File(path).exists()) {
            return path
        }
    } catch (e: Exception) {}

    // 3. Check system PATH (Fallback)
    val ffmpegCmd = if (isWindows) "ffmpeg.exe" else "ffmpeg"
    try {
        val pb = ProcessBuilder(ffmpegCmd, "-version")
        val p = pb.start()
        p.waitFor()
        if (p.exitValue() == 0) {
            return ffmpegCmd
        }
    } catch (e: Exception) {}

    // 4. Default hardcoded fallback path
    val defaultPaths = listOf(
        "C:\\Users\\yuuji\\AppData\\Local\\Programs\\Python\\Python313\\Lib\\site-packages\\imageio_ffmpeg\\binaries\\ffmpeg-win-x86_64-v7.1.exe",
        "ffmpeg"
    )
    for (path in defaultPaths) {
        if (File(path).exists()) {
            return path
        }
    }
    
    return "ffmpeg"
}

private fun getSegmentDuration(ffmpegPath: String, file: File): Double {
    var process: Process? = null
    try {
        val pb = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
        pb.redirectErrorStream(true)
        val p = pb.start()
        process = p
        
        // Spawn a thread to read output to avoid blocking pipe buffer
        var output = ""
        val reader = Thread {
            try {
                output = p.inputStream.bufferedReader().readText()
            } catch (e: Exception) {}
        }
        reader.start()
        
        val completed = p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!completed) {
            println("⚠️ Timeout probing duration for: ${file.name}")
            p.destroyForcibly()
        }
        try { reader.join(1000) } catch (e: Exception) {}
        
        val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
        val durMatch = durRegex.find(output)
        if (durMatch != null) {
            val h = durMatch.groupValues[1].toInt()
            val m = durMatch.groupValues[2].toInt()
            val s = durMatch.groupValues[3].toInt()
            val msVal = durMatch.groupValues[4]
            val msDouble = msVal.toDouble() / java.lang.Math.pow(10.0, msVal.length.toDouble())
            return h * 3600.0 + m * 60.0 + s.toDouble() + msDouble
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        process?.let { if (it.isAlive) try { it.destroyForcibly() } catch (e: Exception) {} }
    }
    return 60.0
}

fun findTelemetryLerp(telemetry: List<fit.FitParser.TelemetryPoint>, targetFitTs: Double): fit.FitParser.TelemetryPoint {
    if (telemetry.isEmpty()) return fit.FitParser.TelemetryPoint(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    if (targetFitTs <= telemetry.first().timestamp) return telemetry.first()
    if (targetFitTs >= telemetry.last().timestamp) return telemetry.last()

    var low = 0
    var high = telemetry.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (telemetry[mid].timestamp <= targetFitTs) {
            low = mid
        } else {
            high = mid - 1
        }
    }

    val p0 = telemetry[low]
    if (low >= telemetry.size - 1) return p0

    val p1 = telemetry[low + 1]
    val t0 = p0.timestamp
    val t1 = p1.timestamp
    val alpha = if (t1 - t0 > 0) (targetFitTs - t0) / (t1 - t0) else 0.0

    val lerp = { a: Double, b: Double -> a + (b - a) * alpha }

    return fit.FitParser.TelemetryPoint(
        timestamp = targetFitTs,
        speed = lerp(p0.speed, p1.speed),
        power = lerp(p0.power, p1.power),
        cadence = lerp(p0.cadence, p1.cadence),
        heartRate = lerp(p0.heartRate, p1.heartRate),
        elevation = lerp(p0.elevation, p1.elevation),
        grade = lerp(p0.grade, p1.grade),
        lat = lerp(p0.lat, p1.lat),
        lon = lerp(p0.lon, p1.lon),
        distance = lerp(p0.distance, p1.distance),
        elapsedSeconds = lerp(p0.elapsedSeconds.toDouble(), p1.elapsedSeconds.toDouble()).toInt()
    )
}

class NativeHudEncoder(
    val settings: HudSettings, 
    val onProgress: (Float, String) -> Unit = { _, _ -> },
    val onFrameRendered: (BufferedImage) -> Unit = {},
    val pauseSupplier: () -> Boolean = { false },
    val cancelSupplier: () -> Boolean = { false },
    val customRenderer: ((HudCanvas, fit.FitParser.TelemetryPoint, List<fit.FitParser.TelemetryPoint>, List<Double>, Float) -> Unit)? = null,
    val showLivePreviewSupplier: () -> Boolean = { true }
) {

    class DesktopHudCanvas(val g: Graphics2D, val scale: Float, val logicalWidth: Float, val logicalHeight: Float) : HudCanvas {
        override val width: Float get() = logicalWidth
        override val height: Float get() = logicalHeight
        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val scaledSize = (size * scale).toInt().coerceAtLeast(1)
            val font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, scaledSize)
            g.font = font
            
            val metrics = g.getFontMetrics(font)
            val stringWidth = metrics.stringWidth(text).toFloat()
            val stringHeight = metrics.height.toFloat()
            
            // Adjust x based on anchor
            val drawX = x * scale - when (anchor) {
                "center" -> stringWidth / 2f
                "top-right", "bottom-right" -> stringWidth
                else -> 0f
            }
            
            // Adjust y based on anchor
            val drawY = y * scale - when (anchor) {
                "center" -> stringHeight / 2f
                "bottom-left", "bottom-right" -> stringHeight
                else -> 0f
            }
            val sy = drawY + metrics.ascent
            val shadowOffset = (1f * scale).coerceAtLeast(1f).toInt()
            
            g.color = Color(0, 0, 0, 180)
            for (dx in -shadowOffset..shadowOffset) {
                for (dy in -shadowOffset..shadowOffset) {
                    if (dx == 0 && dy == 0) continue
                    g.drawString(text, drawX + dx, sy + dy)
                }
            }
            
            g.color = Color.decode(color)
            g.drawString(text, drawX, sy)
        }

        override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            val sx = (x * scale).toInt()
            val sy = (y * scale).toInt()
            val sw = (w * scale).toInt()
            val sh = (h * scale).toInt()
            if (outline) {
                g.drawRect(sx, sy, sw, sh)
            } else {
                g.fillRect(sx, sy, sw, sh)
            }
        }

        override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {
            if (points.isEmpty()) return
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            g.stroke = BasicStroke(width * scale)
            if (points.size == 2) {
                g.drawLine(
                    (points[0].first * scale).toInt(), (points[0].second * scale).toInt(),
                    (points[1].first * scale).toInt(), (points[1].second * scale).toInt()
                )
            } else {
                val xPoints = points.map { (it.first * scale).toInt() }.toIntArray()
                val yPoints = points.map { (it.second * scale).toInt() }.toIntArray()
                g.drawPolyline(xPoints, yPoints, points.size)
            }
        }

        override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            val xPoints = points.map { (it.first * scale).toInt() }.toIntArray()
            val yPoints = points.map { (it.second * scale).toInt() }.toIntArray()
            g.fillPolygon(xPoints, yPoints, points.size)
        }

        override fun getTextWidth(text: String, size: Float, bold: Boolean): Float {
            val font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size.toInt())
            return g.getFontMetrics(font).stringWidth(text).toFloat()
        }
    }



    private fun isGoogleDrivePath(path: String): Boolean {
        val normalized = path.replace("\\", "/").lowercase()
        return normalized.contains("google drive") || 
               normalized.contains("マイドライブ") || 
               normalized.contains("my drive") ||
               normalized.startsWith("g:/") || 
               normalized.startsWith("h:/")
    }

    private fun testEncoder(ffmpegPath: String, encoder: String, hwaccel: String?): Boolean {
        var process: Process? = null
        try {
            val args = mutableListOf<String>()
            args.add(ffmpegPath)
            args.add("-y")
            if (hwaccel != null) {
                args.add("-hwaccel")
                args.add(hwaccel)
            }
            args.add("-f")
            args.add("lavfi")
            args.add("-i")
            args.add("color=c=black:s=128x128:d=0.1")
            args.add("-c:v")
            args.add(encoder)
            args.add("-f")
            args.add("null")
            args.add("-")

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            
            // Spawn a thread to discard output to prevent buffer blocking
            val reader = Thread {
                try {
                    p.inputStream.copyTo(java.io.OutputStream.nullOutputStream())
                } catch (e: Exception) {}
            }
            reader.start()
            
            val completed = p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                println("⚠️ Timeout testing encoder: $encoder")
                p.destroyForcibly()
                return false
            }
            try { reader.join(500) } catch (e: Exception) {}
            
            return p.exitValue() == 0
        } catch (e: Exception) {
            return false
        } finally {
            process?.let { if (it.isAlive) try { it.destroyForcibly() } catch (e: Exception) {} }
        }
    }

    private fun detectEncoderAndHardware(ffmpegPath: String, originalCodec: String): Pair<String?, String> {
        val forceCpu = System.getProperty("FIT_TRIMMER_FORCE_CPU") == "true" || System.getenv("FIT_TRIMMER_FORCE_CPU") == "true"
        val useHevc = (originalCodec == "hevc")
        if (useHevc && !forceCpu) {
            // Test NVIDIA NVENC HEVC
            if (testEncoder(ffmpegPath, "hevc_nvenc", "auto")) {
                return Pair("auto", "hevc_nvenc")
            }
            // Test Intel QSV HEVC
            if (testEncoder(ffmpegPath, "hevc_qsv", "auto")) {
                return Pair("auto", "hevc_qsv")
            }
            // Test AMD AMF HEVC
            if (testEncoder(ffmpegPath, "hevc_amf", "auto")) {
                return Pair("auto", "hevc_amf")
            }
            // CPU fallback HEVC
            return Pair(null, "libx265")
        } else {
            if (!forceCpu) {
                // Test NVIDIA NVENC H.264
                if (testEncoder(ffmpegPath, "h264_nvenc", "auto")) {
                    return Pair("auto", "h264_nvenc")
                }
                // Test Intel QSV H.264
                if (testEncoder(ffmpegPath, "h264_qsv", "auto")) {
                    return Pair("auto", "h264_qsv")
                }
                // Test AMD AMF H.264
                if (testEncoder(ffmpegPath, "h264_amf", "auto")) {
                    return Pair("auto", "h264_amf")
                }
            }
            // CPU fallback H.264
            if (testEncoder(ffmpegPath, "libx264", null)) {
                return Pair(null, "libx264")
            }
            return Pair(null, "libopenh264")
        }
    }

    fun encode(
        fitPath: String, 
        videoPath: String, 
        output: String, 
        startUtc: String, 
        maxDurationSeconds: Int = -1,
        trimStartSeconds: Double = 0.0,
        trimEndSeconds: Double = -1.0
    ) {
        try {
            val ffmpegPath = findFfmpegPath()
        
        val workDir = PathResolver.getTempWorkDir(videoPath)
        if (!workDir.exists()) workDir.mkdirs()
        val tempOutput = File(workDir, "encoding_temp.mp4")
        if (tempOutput.exists()) tempOutput.delete() // Clean up any stale temp files
        val logFile = File(workDir, "ffmpeg_log.txt")
        if (logFile.exists()) logFile.delete()

        var localVideoPath = videoPath
        var isLocalTrimmedVideo = false

        val hasTelemetry = fitPath.isNotEmpty() && File(fitPath).exists()
        var parser: FitParser? = null
        var telemetry = if (hasTelemetry) {
            try {
                val fitBytes = File(fitPath).readBytes()
                val p = FitParser(fitBytes)
                p.parse()
                parser = p
                p.getTelemetry()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        } else {
            emptyList()
        }

        var videoWidth = 1920
        var videoHeight = 1080
        var videoDurationSeconds = 300
        var videoFps = "30" // default to 30
        var originalCodec = "h264"
        var videoRotation = 0
        try {
            // Run ffmpeg -i to gather duration and resolution via stderr stream to avoid missing ffprobe dependency
            val pb = ProcessBuilder(ffmpegPath, "-i", localVideoPath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val outputInfo = p.inputStream.bufferedReader().readText()
            p.waitFor()
            
            // Duration parsing: "Duration: 00:01:23.45"
            val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
            val durMatch = durRegex.find(outputInfo)
            if (durMatch != null) {
                val h = durMatch.groupValues[1].toInt()
                val m = durMatch.groupValues[2].toInt()
                val s = durMatch.groupValues[3].toInt()
                videoDurationSeconds = h * 3600 + m * 60 + s
            }
            
            // Resolution parsing: "Video: ..., 1920x1080 ..."
            val lines = outputInfo.lines()
            val videoLine = lines.find { it.contains("Video:") }
            if (videoLine != null) {
                if (videoLine.contains("hevc", ignoreCase = true) || videoLine.contains("h265", ignoreCase = true)) {
                    originalCodec = "hevc"
                }
                val resRegex = Regex("""\b(\d{3,4})x(\d{3,4})\b""")
                val resMatch = resRegex.find(videoLine)
                if (resMatch != null) {
                    videoWidth = resMatch.groupValues[1].toInt()
                    videoHeight = resMatch.groupValues[2].toInt()
                }
                val fpsRegex = Regex("""([\d\.]+)\s*fps""")
                val fpsMatch = fpsRegex.find(videoLine)
                if (fpsMatch != null) {
                    videoFps = fpsMatch.groupValues[1]
                }
            }

            // Parse rotation
            val rotateMatch = Regex("""rotate\s*:\s*(-?\d+)""").find(outputInfo)
            if (rotateMatch != null) {
                videoRotation = rotateMatch.groupValues[1].toIntOrNull() ?: 0
                println("DEBUG: NativeHudEncoder rotation parsed: $videoRotation degrees")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        var exportWidth = videoWidth
        var exportHeight = videoHeight
        val maxLongEdge = when (settings.exportResolution) {
            "1080p" -> 1920.0
            "2.7k" -> 2704.0
            else -> 0.0 // "original" size
        }
        if (maxLongEdge > 0.0 && (exportWidth > maxLongEdge || exportHeight > maxLongEdge)) {
            if (exportWidth >= exportHeight) {
                val ratio = maxLongEdge / exportWidth
                exportWidth = maxLongEdge.toInt()
                exportHeight = (exportHeight * ratio).toInt()
            } else {
                val ratio = maxLongEdge / exportHeight
                exportHeight = maxLongEdge.toInt()
                exportWidth = (exportWidth * ratio).toInt()
            }
        }
        exportWidth = (exportWidth / 2) * 2
        exportHeight = (exportHeight / 2) * 2

        val actualTrimStart = trimStartSeconds.coerceIn(0.0, videoDurationSeconds.toDouble())
        val actualTrimEnd = if (trimEndSeconds <= 0.0 || trimEndSeconds > videoDurationSeconds.toDouble()) {
            videoDurationSeconds.toDouble()
        } else {
            trimEndSeconds
        }
        val trimDurationSeconds = (actualTrimEnd - actualTrimStart).toInt().coerceAtLeast(1)

        val targetDurationSeconds = if (maxDurationSeconds > 0) {
            minOf(trimDurationSeconds, maxDurationSeconds)
        } else {
            trimDurationSeconds
        }

        if (!hasTelemetry || telemetry.isEmpty()) {
            // HUD-less fast stream copy trimming mode (extremely fast, zero re-encoding)
            println("ℹ️ No FIT file / telemetry provided or empty. Running fast trim (stream copy) mode...")
            onProgress(0.0f, "Running fast trim (stream copy)...")
            
            val pbArgs = mutableListOf<String>()
            pbArgs.add(ffmpegPath)
            pbArgs.add("-y")
            // Fast seek before input
            pbArgs.add("-ss")
            pbArgs.add(actualTrimStart.toString())
            pbArgs.add("-t")
            pbArgs.add(targetDurationSeconds.toString())
            pbArgs.add("-i")
            pbArgs.add(localVideoPath)
            pbArgs.add("-c")
            pbArgs.add("copy")
            pbArgs.add(tempOutput.absolutePath)
            
            val pb = ProcessBuilder(pbArgs)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val logStream = FileOutputStream(logFile, true)
            val readerThread = Thread {
                try {
                    process.inputStream.copyTo(logStream)
                } finally {
                    logStream.close()
                }
            }
            readerThread.start()

            val cancelMonitorThread = kotlin.concurrent.thread {
                while (process.isAlive) {
                    if (cancelSupplier()) {
                        process.destroy()
                        try { process.destroyForcibly() } catch (e: Exception) {}
                        break
                    }
                    try { Thread.sleep(100) } catch (e: Exception) { break }
                }
            }
            
            // Loop with timeout to allow cancel detection during process execution
            var exitCode = -1
            while (process.isAlive) {
                if (cancelSupplier()) {
                    process.destroy()
                    try { process.destroyForcibly() } catch (e: Exception) {}
                    break
                }
                if (process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue()
                    break
                }
            }
            if (!process.isAlive) {
                exitCode = process.exitValue()
            }
            
            try { readerThread.interrupt() } catch (e: Exception) {}
            try { readerThread.join(1000) } catch (e: Exception) {}
            try { cancelMonitorThread.interrupt() } catch (e: Exception) {}
            try { cancelMonitorThread.join(1000) } catch (e: Exception) {}

            if (cancelSupplier()) {
                throw Exception("Encoding was canceled by user.")
            }
            
            if (exitCode == 0 && tempOutput.exists() && tempOutput.length() > 0L) {
                val outFile = File(output)
                if (outFile.exists()) outFile.delete()
                outFile.parentFile?.mkdirs()
                Files.move(tempOutput.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                onProgress(1.0f, "✨ Finished Successfully!")
                return
            } else {
                throw Exception("Fast trim-copy failed with exit code $exitCode")
            }
        }

        val startTime = try { Instant.parse(startUtc) } catch(e: Exception) { Instant.EPOCH }
        val fitEpoch = Instant.parse("1989-12-31T00:00:00Z").epochSecond

        val startTimeAdjusted = startTime.plusSeconds(actualTrimStart.toLong())
        val videoStartFit = startTimeAdjusted.epochSecond - fitEpoch
        val videoEndFit = (startTimeAdjusted.epochSecond + targetDurationSeconds) - fitEpoch
        val trimmedTelemetry = telemetry.filter { it.timestamp in videoStartFit.toDouble()..videoEndFit.toDouble() }
        if (trimmedTelemetry.isNotEmpty()) {
            telemetry = trimmedTelemetry
        }

        val config = HudConfig(
            valSize = settings.valSize, tightness = settings.tightness, spacing = settings.spacing,
            xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW,
            captionPosition = settings.captionPosition,
            roadCaptions = settings.roadCaptions,
            powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
            useImperialUnits = settings.useImperialUnits,
            language = settings.language
        )
        println("DEBUG: NativeHudEncoder.encode config=$config, videoWidth=$videoWidth, videoHeight=$videoHeight")
        val renderer = HudRenderer(config)
        val plateCache = if (settings.blurLicensePlates) {
            PlateCacheManager.loadCache(videoPath)
        } else null
        if (plateCache != null) {
            println("DEBUG: Loaded ${plateCache.records.size} plate records for rendering.")
        }
        
        val (hwaccel, encoderName) = detectEncoderAndHardware(ffmpegPath, originalCodec)
        println("DEBUG: Auto-detected encoder: $encoderName, hwaccel: $hwaccel")

        // Create deterministic job hash for crash recovery (include resolution to avoid mismatched segment reuse)
        val jobHash = kotlin.math.abs((fitPath + videoPath + startUtc + maxDurationSeconds + actualTrimStart + actualTrimEnd + videoWidth + videoHeight + config.hashCode()).hashCode()).toString()
        val jobDir = File(workDir, "job_$jobHash")
        if (!jobDir.exists()) jobDir.mkdirs()
        globalActiveJobDir = jobDir

        // Google Drive mitigation: pre-copy target segment locally to avoid network read bottleneck
        if (isGoogleDrivePath(videoPath)) {
            val tempTrimmedVideo = File(jobDir, "temp_trimmed_input.mp4")
            if (!tempTrimmedVideo.exists() || tempTrimmedVideo.length() == 0L) {
                println("⚠️ Cloud drive path detected: $videoPath")
                println("📥 Pre-copying trimmed segment to local temp file to avoid network bottlenecks: ${tempTrimmedVideo.absolutePath}")
                onProgress(0.0f, "Downloading/trimming segment from cloud drive...")
                try {
                    val cutArgs = mutableListOf<String>()
                    cutArgs.add(ffmpegPath)
                    cutArgs.add("-y")
                    // Note: putting -ss before -i causes fast seek on remote files to load only the required chunk
                    cutArgs.add("-ss")
                    cutArgs.add(actualTrimStart.toString())
                    cutArgs.add("-t")
                    cutArgs.add(targetDurationSeconds.toString())
                    cutArgs.add("-i")
                    cutArgs.add(videoPath)
                    cutArgs.add("-c")
                    cutArgs.add("copy")
                    cutArgs.add(tempTrimmedVideo.absolutePath)

                    println("DEBUG: Running cloud segment copy: ${cutArgs.joinToString(" ")}")
                    val pbCut = ProcessBuilder(cutArgs)
                    pbCut.redirectErrorStream(true)
                    val pCut = pbCut.start()
                    
                    val timeRegex = Regex("""time=\s*(\d+):(\d+):(\d+)\.(\d+)""")
                    val sizeRegex = Regex("""(?:Lsize|size)=\s*(\d+)\s*KiB""")
                    val spinner = listOf("|", "/", "-", "\\")
                    var spinnerIdx = 0

                    val cutLogThread = Thread {
                        try {
                            pCut.inputStream.bufferedReader().forEachLine { line ->
                                println("FFMPEG-COPY: $line")
                                val sp = spinner[spinnerIdx++ % spinner.size]
                                val timeMatch = timeRegex.find(line)
                                val sizeMatch = sizeRegex.find(line)
                                
                                var sizeMbStr = ""
                                if (sizeMatch != null) {
                                    val kib = sizeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                                    sizeMbStr = " (%.1f MB)".format(kib / 1024.0)
                                }
                                
                                if (timeMatch != null) {
                                    val h = timeMatch.groupValues[1].toInt()
                                    val m = timeMatch.groupValues[2].toInt()
                                    val s = timeMatch.groupValues[3].toInt()
                                    val msVal = timeMatch.groupValues[4]
                                    val ms = msVal.toDouble() / java.lang.Math.pow(10.0, msVal.length.toDouble())
                                    val currentSec = h * 3600.0 + m * 60.0 + s.toDouble() + ms
                                    
                                    val ratio = (currentSec / targetDurationSeconds).coerceIn(0.0, 1.0)
                                    val percent = (ratio * 100).toInt()
                                    onProgress(0.0f, "$sp 📥 Downloading segment from cloud: $percent%$sizeMbStr")
                                } else if (sizeMbStr.isNotEmpty()) {
                                    onProgress(0.0f, "$sp 📥 Downloading segment from cloud...$sizeMbStr")
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    cutLogThread.start()
                    
                    // Cancel monitor thread for download/trim process
                    val cutCancelMonitorThread = kotlin.concurrent.thread {
                        while (pCut.isAlive) {
                            if (cancelSupplier()) {
                                pCut.destroy()
                                try { pCut.destroyForcibly() } catch (e: Exception) {}
                                break
                            }
                            try { Thread.sleep(100) } catch (e: Exception) { break }
                        }
                    }
                    
                    val exitCut = pCut.waitFor()
                    try { cutLogThread.interrupt() } catch (e: Exception) {}
                    cutLogThread.join(1000)
                    try { cutCancelMonitorThread.interrupt() } catch (e: Exception) {}
                    cutCancelMonitorThread.join(1000)
                    
                    if (cancelSupplier()) {
                        try { tempTrimmedVideo.delete() } catch (e: Exception) {}
                        throw Exception("Encoding was canceled by user during cloud copy.")
                    }
                    
                    if (exitCut == 0 && tempTrimmedVideo.exists() && tempTrimmedVideo.length() > 0L) {
                        println("✅ Successfully copied segment to local: ${tempTrimmedVideo.length() / (1024 * 1024)} MB")
                        localVideoPath = tempTrimmedVideo.absolutePath
                        isLocalTrimmedVideo = true
                    } else {
                        try { tempTrimmedVideo.delete() } catch (e: Exception) {}
                        println("❌ Failed to perform fast trim-copy (exit code $exitCut). Falling back to direct cloud stream.")
                    }
                } catch (e: Exception) {
                    try { tempTrimmedVideo.delete() } catch (e2: Exception) {}
                    println("❌ Error during cloud copy: ${e.message}. Falling back to direct cloud stream.")
                }
            } else {
                println("✨ Using existing local temp segment: ${tempTrimmedVideo.absolutePath}")
                localVideoPath = tempTrimmedVideo.absolutePath
                isLocalTrimmedVideo = true
            }
        }

        // Clean up any stray temp files in the job directory
        jobDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach {
            try {
                it.delete()
                println("🧹 Deleted stray temp file: ${it.name}")
            } catch (e: Exception) {}
        }

        val existingParts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) }?.sortedBy { it.name } ?: emptyList()
        var resumePartIndex = existingParts.size
        if (resumePartIndex > 0) {
            try {
                val lastFile = existingParts.last()
                lastFile.delete()
                resumePartIndex--
                println("⚠️ Detected crash recovery: deleted potentially incomplete last chunk ${lastFile.name}. Resuming from chunk $resumePartIndex.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var resumeSeconds = 0
        var ffmpegStartSeconds = 0.0
        if (resumePartIndex > 0) {
            val completedParts = existingParts.take(resumePartIndex)
            var totalDuration = 0.0
            for (partFile in completedParts) {
                val duration = getSegmentDuration(ffmpegPath, partFile)
                totalDuration += duration
                println("DEBUG: Segment ${partFile.name} duration: $duration s")
            }
            resumeSeconds = totalDuration.toInt()
            ffmpegStartSeconds = totalDuration
            println("🔄 Calculated accurate resume offset: ${formatDuration(resumeSeconds)} ($ffmpegStartSeconds s) from $resumePartIndex completed chunks")
        }
        
        if (resumeSeconds > 0 && resumeSeconds < targetDurationSeconds) {
            println("🔄 RESUMING encode from chunk $resumePartIndex (${formatDuration(resumeSeconds)} / ${formatDuration(targetDurationSeconds)})")
            onProgress(resumeSeconds.toFloat() / targetDurationSeconds, "Resuming encode from ${formatDuration(resumeSeconds)}...")
        }

        val isEncodingActive = java.util.concurrent.atomic.AtomicBoolean(true)

        if (resumeSeconds < targetDurationSeconds && isEncodingActive.get()) {
            if (cancelSupplier()) {
                throw Exception("Encoding was canceled by user.")
            }
            while (pauseSupplier()) {
                if (cancelSupplier()) throw Exception("Encoding was canceled by user.")
                Thread.sleep(100)
            }
            
            val ffmpegInputStart = actualTrimStart + ffmpegStartSeconds
            val remainingDuration = targetDurationSeconds - ffmpegStartSeconds
            
            val pbArgs = mutableListOf<String>()
            pbArgs.add(ffmpegPath)
            pbArgs.add("-y")
            
            pbArgs.add("-f")
            pbArgs.add("rawvideo")
            pbArgs.add("-pixel_format")
            pbArgs.add("abgr")
            pbArgs.add("-video_size")
            pbArgs.add("${exportWidth}x${exportHeight * 2}")
            pbArgs.add("-framerate")
            pbArgs.add(videoFps)
            pbArgs.add("-i")
            pbArgs.add("pipe:0") // raw frames
            
            if (hwaccel != null) {
                pbArgs.add("-hwaccel")
                pbArgs.add(hwaccel)
            }
            val seekStart = if (isLocalTrimmedVideo) {
                ffmpegStartSeconds
            } else {
                ffmpegInputStart
            }
            pbArgs.add("-ss")
            pbArgs.add(seekStart.toString())
            pbArgs.add("-t")
            pbArgs.add(remainingDuration.toString())
            pbArgs.add("-i")
            pbArgs.add(localVideoPath)
            
            pbArgs.add("-filter_complex")
            pbArgs.add(
                "[0:v]crop=$exportWidth:$exportHeight:0:0,setpts=PTS-STARTPTS[hud];" +
                "[0:v]crop=$exportWidth:$exportHeight:0:$exportHeight,setpts=PTS-STARTPTS,extractplanes=a[mask];" +
                "[1:v]scale=$exportWidth:$exportHeight,setpts=PTS-STARTPTS,split[vid_orig][vid_blur_src];" +
                "[vid_blur_src]avgblur=sizeX=30:sizeY=30[vid_blurred];" +
                "[vid_orig][vid_blurred][mask]maskedmerge[vid_merged];" +
                "[vid_merged][hud]overlay=0:0:shortest=1"
            )
            
            pbArgs.add("-c:v")
            pbArgs.add(encoderName)
            pbArgs.add("-fps_mode")
            pbArgs.add("cfr")
            pbArgs.add("-r")
            pbArgs.add(videoFps)
            
            val qualityVal = "21"
            if (encoderName.endsWith("_qsv")) {
                pbArgs.add("-global_quality")
                pbArgs.add(qualityVal)
            } else if (encoderName.endsWith("_nvenc")) {
                pbArgs.add("-rc")
                pbArgs.add("constqp")
                pbArgs.add("-qp")
                pbArgs.add(qualityVal)
            } else {
                pbArgs.add("-crf")
                pbArgs.add(qualityVal)
            }
            pbArgs.add("-pix_fmt")
            pbArgs.add("yuv420p")
            pbArgs.add("-c:a")
            pbArgs.add("aac")
            pbArgs.add("-b:a")
            pbArgs.add("192k")
            
            // Use segment muxer to split outputs into part_%04d.ts files on the fly
            pbArgs.add("-f")
            pbArgs.add("segment")
            pbArgs.add("-segment_time")
            pbArgs.add("60")
            pbArgs.add("-segment_format")
            pbArgs.add("mpegts")
            pbArgs.add("-segment_start_number")
            pbArgs.add(resumePartIndex.toString())
            pbArgs.add("-reset_timestamps")
            pbArgs.add("1")
            pbArgs.add(File(jobDir, "part_%04d.ts").absolutePath)
            
            val pb = ProcessBuilder(pbArgs)
            pb.redirectErrorStream(true)
            val process = pb.start()
            
            val pid = try { process.pid() } catch (e: Exception) { null }
            if (pid != null) {
                kotlin.concurrent.thread(start = true, isDaemon = true) {
                    try {
                        val pbBoost = ProcessBuilder("powershell", "-Command", "\$p = Get-Process -Id $pid -ErrorAction SilentlyContinue; if (\$p) { \$p.PriorityClass = 'High' }")
                        pbBoost.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        pbBoost.redirectError(ProcessBuilder.Redirect.DISCARD)
                        val pBoost = pbBoost.start()
                        pBoost.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (pBoost.isAlive) {
                            try { pBoost.destroyForcibly() } catch (e: Exception) {}
                        }
                        println("DEBUG: Boosted ffmpeg process (PID=$pid) priority to High")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            val logStream = FileOutputStream(logFile, true) // Append mode for chunks
            val readerThread = Thread {
                try {
                    process.inputStream.copyTo(logStream)
                } finally {
                    logStream.close()
                }
            }
            readerThread.start()
            
            val cancelMonitorThread = kotlin.concurrent.thread {
                while (isEncodingActive.get() && process.isAlive) {
                    if (cancelSupplier()) {
                        process.destroy()
                        try { process.destroyForcibly() } catch (e: Exception) {}
                        break
                    }
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
            
            val out = process.outputStream
            val pBuf = mutableListOf<Double>()
            val img = BufferedImage(exportWidth, exportHeight * 2, BufferedImage.TYPE_4BYTE_ABGR)
            val frameTimes = mutableListOf<Long>()
            
            // Pre-fill power buffer for context if we're not at the start
            if (resumeSeconds > 0) {
                val prefillStart = maxOf(0, resumeSeconds - settings.powerTrendSpanSeconds)
                for (preSec in prefillStart until resumeSeconds) {
                    val preUtc = startTimeAdjusted.plusSeconds(preSec.toLong()).epochSecond
                    val preFitTs = preUtc - fitEpoch
                    val pt = telemetry.find { it.timestamp >= preFitTs } ?: telemetry.last()
                    pBuf.add(pt.power)
                }
            }
            
            var telemetryIdx = 0
            val fpsDouble = videoFps.toDoubleOrNull() ?: 30.0
            val totalFrames = (targetDurationSeconds * fpsDouble).toInt()
            val resumeFrames = (resumeSeconds * fpsDouble).toInt()
            var lastPowerSec = resumeSeconds - 1
            var lastProcessedFrame = resumeFrames
            try {
                loop@ for (f in resumeFrames until totalFrames) {
                    lastProcessedFrame = f
                    if (cancelSupplier()) {
                        process.destroy()
                        try { process.destroyForcibly() } catch (e: Exception) {}
                        break@loop
                    }
                    while (pauseSupplier()) {
                        if (cancelSupplier()) {
                            process.destroy()
                            try { process.destroyForcibly() } catch (e: Exception) {}
                            break@loop
                        }
                        Thread.sleep(100)
                    }
                    
                    val loopStart = System.currentTimeMillis()
                    val currentSec = f / fpsDouble
                    val currentUtcSeconds = startTimeAdjusted.epochSecond + currentSec
                    val currentFitTs = currentUtcSeconds - fitEpoch
                    
                    val point = findTelemetryLerp(telemetry, currentFitTs)
                    
                    val currentSecInt = currentSec.toInt()
                    if (currentSecInt > lastPowerSec) {
                        pBuf.add(point.power)
                        if (pBuf.size > settings.powerTrendSpanSeconds) pBuf.removeAt(0)
                        lastPowerSec = currentSecInt
                    }
                    
                    val g = img.createGraphics()
                    g.composite = AlphaComposite.Clear
                    g.fillRect(0, 0, exportWidth, exportHeight * 2)
                    g.composite = AlphaComposite.SrcOver
                    
                    val isValid = currentFitTs >= telemetry.first().timestamp && currentFitTs <= telemetry.last().timestamp
                    
                    // Render blur mask in the bottom half (heightOffset = exportHeight)
                    val timeMs = (currentSec * 1000.0).toLong()
                    val blurBoxes = plateCache?.shouldBlurAt(timeMs, settings.blurLicensePlates) ?: emptyList()
                    if (blurBoxes.isNotEmpty()) {
                        val is90Or270 = videoRotation == 90 || videoRotation == -270 || videoRotation == 270 || videoRotation == -90
                        val rotatedVideoW = if (is90Or270) videoHeight else videoWidth
                        val rotatedVideoH = if (is90Or270) videoWidth else videoHeight
                        
                        val scaleX = exportWidth.toFloat() / rotatedVideoW.toFloat()
                        val scaleY = exportHeight.toFloat() / rotatedVideoH.toFloat()
                        
                        // Mask must be solid white (alpha 255) on a transparent background
                        g.color = java.awt.Color(255, 255, 255, 255)
                        for (box in blurBoxes) {
                            val rx1 = box.x1.toFloat() * scaleX
                            val ry1 = box.y1.toFloat() * scaleY + exportHeight // Shift to bottom half
                            val rx2 = box.x2.toFloat() * scaleX
                            val ry2 = box.y2.toFloat() * scaleY + exportHeight // Shift to bottom half
                            
                            val w = (rx2 - rx1).toInt()
                            val h = (ry2 - ry1).toInt()
                            if (w > 0 && h > 0) {
                                g.fillRect(rx1.toInt(), ry1.toInt(), w, h)
                            }
                        }
                    }

                    // Render HUD in the top half
                    val gHud = g.create() as java.awt.Graphics2D
                    gHud.clipRect(0, 0, exportWidth, exportHeight)
                    val scale = exportWidth.toFloat() / 1920f
                    val canvas = DesktopHudCanvas(gHud, scale, exportWidth.toFloat() / scale, exportHeight.toFloat() / scale)
                    if (customRenderer != null) {
                        customRenderer.invoke(canvas, point, telemetry, pBuf, currentSec.toFloat())
                    } else {
                        renderer.renderFrame(canvas, point, telemetry, pBuf, currentSec.toFloat(), isValid)
                    }
                    gHud.dispose()
                    g.dispose()
                    
                    val rawBytes = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
                    out.write(rawBytes)
                    out.flush()
                    
                    val loopEnd = System.currentTimeMillis()
                    val elapsedLoop = loopEnd - loopStart
                    frameTimes.add(elapsedLoop)
                    if (frameTimes.size > 24) frameTimes.removeAt(0)
                    
                    val avgFrameTime = frameTimes.average()
                    val currentFps = if (avgFrameTime > 0) 1000.0 / avgFrameTime else 0.0
                    
                    val remainingFrames = totalFrames - f
                    val remainingSecondsETA = if (currentFps > 0) (remainingFrames / currentFps).toInt() else 0
                    
                    val processedStr = formatDuration(currentSec.toInt())
                    val totalStr = formatDuration(targetDurationSeconds)
                    val remainingStr = formatDuration(remainingSecondsETA)
                    val progressRatio = f.toFloat() / totalFrames
                    val progressPercent = (progressRatio * 100).toInt()
                    
                    val fpsStr = "%.1f fps".format(currentFps)
                    val speedStr = "%.1fx".format(currentFps / fpsDouble)
                    
                    val statusText = "Encoding: $progressPercent% | $processedStr / $totalStr | Speed: $fpsStr ($speedStr) | ETA: $remainingStr"
                    onProgress(progressRatio, statusText)
                    
                    if (showLivePreviewSupplier()) {
                        val targetW = 960
                        val targetH = (exportHeight * (targetW.toFloat() / exportWidth)).toInt().coerceAtLeast(1)
                        val copy = BufferedImage(targetW, targetH, img.type)
                        val g2d = copy.createGraphics()
                        g2d.drawImage(img, 0, 0, targetW, targetH, null)
                        g2d.dispose()
                        onFrameRendered(copy)
                    }
                }
            } catch (e: Exception) {
                println("\n❌ Pipe Write Error: ${e.message}")
            } finally {
                isEncodingActive.set(false)
                try { out.close() } catch (e: Exception) {}
                
                // Wait for the process to finish naturally first (especially if we finished loop normally)
                if (process.isAlive) {
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                
                // Ensure process is terminated forcibly if it's still alive after waiting
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        try { process.destroyForcibly() } catch (e: Exception) {}
                    }
                }
                val exitCode = process.waitFor()
                
                // Interrupt threads to avoid hanging on blocking IO/sleep
                try { readerThread.interrupt() } catch (e: Exception) {}
                try { readerThread.join(1000) } catch (e: Exception) {}
                
                try { cancelMonitorThread.interrupt() } catch (e: Exception) {}
                try { cancelMonitorThread.join(1000) } catch (e: Exception) {}
                
                if (cancelSupplier()) {
                    throw Exception("Encoding was canceled by user.")
                }
                
                if (exitCode != 0) {
                    val lastProcessedSec = lastProcessedFrame / fpsDouble
                    val isNearEnd = (targetDurationSeconds - lastProcessedSec) <= 3.0
                    if (isNearEnd && !cancelSupplier()) {
                        println("ℹ️ FFmpeg exited with code $exitCode near the end of video (${lastProcessedSec.toInt()}/$targetDurationSeconds s). Treating as success (EOF reached).")
                        resumeSeconds = targetDurationSeconds
                    } else {
                        throw Exception("ffmpeg exited with error code $exitCode. See ffmpeg_log.txt for details.")
                    }
                } else {
                    // Success!
                    resumeSeconds = targetDurationSeconds
                }
            }
        }

        if (!cancelSupplier() && resumeSeconds >= targetDurationSeconds) {
            // Export trimmed FIT file
            if (hasTelemetry && parser != null) {
                try {
                    val fitStartUtcSeconds = startTimeAdjusted.epochSecond
                    val fitEndUtcSeconds = startTimeAdjusted.epochSecond + targetDurationSeconds
                    val trimmedFitBytes = parser.trim(fitStartUtcSeconds, fitEndUtcSeconds)
                    val trimmedFitFile = File(output.replace(Regex("""\.(mp4|mov)$""", RegexOption.IGNORE_CASE), ".fit"))
                    trimmedFitFile.writeBytes(trimmedFitBytes)
                    println("⚡ Trimmed FIT file exported to: ${trimmedFitFile.absolutePath}")
                } catch (e: Exception) {
                    println("⚠️ Failed to export trimmed FIT file: ${e.message}")
                    e.printStackTrace()
                }
            }

            onProgress(1.0f, "Merging video segments (Crash Recovery Checkpoint)...")
            
            val partsListFile = File(jobDir, "parts.txt")
            val parts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) }?.sortedBy { it.name } ?: emptyList()
            
            if (parts.isEmpty()) {
                throw Exception("No valid video parts found to merge.")
            }
            
            val listContent = parts.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }
            partsListFile.writeText(listContent)
            
            val finalDest = File(output)
            finalDest.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (finalDest.exists()) finalDest.delete()
            
            val concatArgs = listOf(
                ffmpegPath, "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", partsListFile.absolutePath,
                "-c", "copy",
                "-metadata", "comment=fit-trimmer-hud-burned",
                finalDest.absolutePath
            )
            
            val pb = ProcessBuilder(concatArgs)
            pb.redirectErrorStream(true)
            val p = pb.start()
            
            val logStream = FileOutputStream(logFile, true)
            val readerThread = Thread {
                try {
                    p.inputStream.copyTo(logStream)
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                } finally {
                    try { logStream.close() } catch (e: java.lang.Exception) {}
                }
            }
            readerThread.start()
            
            val concatExit = p.waitFor()
            try {
                readerThread.join(2000)
            } catch (e: java.lang.Exception) {}
            
            if (concatExit != 0) {
                throw Exception("Failed to merge video segments. ffmpeg exited with code $concatExit.")
            }
            
            // Clean up chunks after successful concat
            parts.forEach { it.delete() }
            partsListFile.delete()
            jobDir.deleteRecursively() // Cleanup the whole job folder
            
            onProgress(1.0f, "✨ Finished Successfully!")
        }
        } finally {
            try {
                globalActiveJobDir?.let {
                    val usableSpace = try { it.usableSpace } catch (e: Exception) { Long.MAX_VALUE }
                    val isDiskFull = usableSpace < 100 * 1024 * 1024 // Less than 100MB free space
                    
                    if ((cancelSupplier() || isDiskFull) && it.exists()) {
                        if (isDiskFull) {
                            println("⚠️ Critical: Low disk space detected (<100MB). Forcibly cleaning up job folder to free space: ${it.absolutePath}")
                        } else {
                            println("DEBUG: Cleaning up active job directory after manual cancel: ${it.absolutePath}")
                        }
                        it.deleteRecursively()
                    } else {
                        println("DEBUG: Preserving active job directory for resume/recovery: ${it.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            globalActiveJobDir = null
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }
}
