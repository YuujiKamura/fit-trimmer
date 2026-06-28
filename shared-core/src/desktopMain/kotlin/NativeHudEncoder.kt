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
    try {
        val pb = ProcessBuilder(ffmpegPath, "-i", file.absolutePath)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor()
        
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
    }
    return 60.0
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

    class DesktopHudCanvas(val g: Graphics2D, val scale: Float) : HudCanvas {
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
            val exitCode = p.waitFor()
            return exitCode == 0
        } catch (e: Exception) {
            return false
        }
    }

    private fun detectEncoderAndHardware(ffmpegPath: String): Pair<String?, String> {
        // Test NVIDIA NVENC
        if (testEncoder(ffmpegPath, "h264_nvenc", "auto")) {
            return Pair("auto", "h264_nvenc")
        }
        
        // Test Intel QSV
        if (testEncoder(ffmpegPath, "h264_qsv", "auto")) {
            return Pair("auto", "h264_qsv")
        }
        
        // Test AMD AMF
        if (testEncoder(ffmpegPath, "h264_amf", "auto")) {
            return Pair("auto", "h264_amf")
        }
        
        // CPU fallback
        return Pair(null, "libx264")
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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        var exportWidth = videoWidth
        var exportHeight = videoHeight
        val maxLongEdge = 1920.0
        if (exportWidth > maxLongEdge || exportHeight > maxLongEdge) {
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
            
            val exitCode = process.waitFor()
            readerThread.join(1000)
            
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
            xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW
        )
        println("DEBUG: NativeHudEncoder.encode config=$config, videoWidth=$videoWidth, videoHeight=$videoHeight")
        val renderer = HudRenderer(config)
        
        val (hwaccel, encoderName) = detectEncoderAndHardware(ffmpegPath)
        println("DEBUG: Auto-detected encoder: $encoderName, hwaccel: $hwaccel")

        // Create deterministic job hash for crash recovery
        val jobHash = kotlin.math.abs((fitPath + videoPath + startUtc + maxDurationSeconds + actualTrimStart + actualTrimEnd + config.hashCode()).hashCode()).toString()
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
                                break
                            }
                            Thread.sleep(100)
                        }
                    }
                    
                    val exitCut = pCut.waitFor()
                    cutLogThread.join(1000)
                    cutCancelMonitorThread.join(1000)
                    
                    if (cancelSupplier()) {
                        throw Exception("Encoding was canceled by user during cloud copy.")
                    }
                    
                    if (exitCut == 0 && tempTrimmedVideo.exists() && tempTrimmedVideo.length() > 0L) {
                        println("✅ Successfully copied segment to local: ${tempTrimmedVideo.length() / (1024 * 1024)} MB")
                        localVideoPath = tempTrimmedVideo.absolutePath
                        isLocalTrimmedVideo = true
                    } else {
                        println("❌ Failed to perform fast trim-copy (exit code $exitCut). Falling back to direct cloud stream.")
                    }
                } catch (e: Exception) {
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
            pbArgs.add("${exportWidth}x${exportHeight}")
            pbArgs.add("-framerate")
            pbArgs.add("1")
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
            pbArgs.add("[0:v]setpts=PTS-STARTPTS[hud];[1:v]scale=$exportWidth:$exportHeight,setpts=PTS-STARTPTS[vid];[vid][hud]overlay=0:0:shortest=1")
            
            pbArgs.add("-c:v")
            pbArgs.add(encoderName)
            pbArgs.add("-fps_mode")
            pbArgs.add("cfr")
            pbArgs.add("-r")
            pbArgs.add(videoFps)
            
            when (encoderName) {
                "h264_qsv" -> {
                    pbArgs.add("-global_quality")
                    pbArgs.add("18")
                }
                "h264_nvenc" -> {
                    pbArgs.add("-rc")
                    pbArgs.add("constqp")
                    pbArgs.add("-qp")
                    pbArgs.add("18")
                }
                else -> {
                    pbArgs.add("-crf")
                    pbArgs.add("18")
                }
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
                        ProcessBuilder("powershell", "-Command", "\$p = Get-Process -Id $pid -ErrorAction SilentlyContinue; if (\$p) { \$p.PriorityClass = 'High' }").start()
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
                        break
                    }
                    Thread.sleep(100)
                }
            }
            
            val out = process.outputStream
            val pBuf = mutableListOf<Double>()
            val img = BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_4BYTE_ABGR)
            val frameTimes = mutableListOf<Long>()
            
            // Pre-fill power buffer for context if we're not at the start
            if (resumeSeconds > 0) {
                val prefillStart = maxOf(0, resumeSeconds - 30)
                for (preSec in prefillStart until resumeSeconds) {
                    val preUtc = startTimeAdjusted.plusSeconds(preSec.toLong()).epochSecond
                    val preFitTs = preUtc - fitEpoch
                    val pt = telemetry.find { it.timestamp >= preFitTs } ?: telemetry.last()
                    pBuf.add(pt.power)
                }
            }
            
            var telemetryIdx = 0
            try {
                loop@ for (i in resumeSeconds until targetDurationSeconds) {
                    if (cancelSupplier()) {
                        process.destroy()
                        break@loop
                    }
                    while (pauseSupplier()) {
                        if (cancelSupplier()) {
                            process.destroy()
                            break@loop
                        }
                        Thread.sleep(100)
                    }
                    
                    val loopStart = System.currentTimeMillis()
                    val currentUtc = startTimeAdjusted.plusSeconds(i.toLong()).epochSecond
                    val currentFitTs = currentUtc - fitEpoch
                    
                    while (telemetryIdx < telemetry.size - 1 && telemetry[telemetryIdx].timestamp < currentFitTs) {
                        telemetryIdx++
                    }
                    val point = telemetry.getOrNull(telemetryIdx) ?: telemetry.last()
                    
                    pBuf.add(point.power)
                    if (pBuf.size > 30) pBuf.removeAt(0)
                    
                    val g = img.createGraphics()
                    g.composite = AlphaComposite.Clear
                    g.fillRect(0, 0, videoWidth, videoHeight)
                    g.composite = AlphaComposite.SrcOver
                    
                    val isValid = currentFitTs >= telemetry.first().timestamp && currentFitTs <= telemetry.last().timestamp
                    val scale = exportWidth.toFloat() / 1920f
                    val canvas = DesktopHudCanvas(g, scale)
                    if (customRenderer != null) {
                        customRenderer.invoke(canvas, point, telemetry, pBuf, i.toFloat() / targetDurationSeconds)
                    } else {
                        renderer.renderFrame(canvas, point, telemetry, pBuf, i.toFloat() / targetDurationSeconds, isValid)
                    }
                    g.dispose()
                    
                    val rawBytes = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
                    out.write(rawBytes)
                    out.flush()
                    
                    val loopEnd = System.currentTimeMillis()
                    val elapsedLoop = loopEnd - loopStart
                    frameTimes.add(elapsedLoop)
                    if (frameTimes.size > 8) frameTimes.removeAt(0)
                    
                    val avgFrameTime = frameTimes.average()
                    val currentFps = if (avgFrameTime > 0) 1000.0 / avgFrameTime else 0.0
                    
                    val remainingFrames = targetDurationSeconds - i
                    val remainingSecondsETA = if (currentFps > 0) (remainingFrames / currentFps).toInt() else 0
                    
                    val processedStr = formatDuration(i)
                    val totalStr = formatDuration(targetDurationSeconds)
                    val remainingStr = formatDuration(remainingSecondsETA)
                    val progressRatio = i.toFloat() / targetDurationSeconds
                    val progressPercent = (progressRatio * 100).toInt()
                    
                    val fpsStr = "%.1f fps".format(currentFps)
                    val speedStr = "%.1fx".format(currentFps)
                    
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
                out.close()
                val exitCode = process.waitFor()
                readerThread.join(1000)
                cancelMonitorThread.join(1000)
                cancelMonitorThread.join(1000)
                
                if (cancelSupplier()) {
                    throw Exception("Encoding was canceled by user.")
                }
                
                if (exitCode != 0) {
                    throw Exception("ffmpeg exited with error code $exitCode. See ffmpeg_log.txt for details.")
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
                    if (it.exists()) {
                        println("DEBUG: Cleaning up active job directory after failure/cancel: ${it.absolutePath}")
                        it.deleteRecursively()
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
