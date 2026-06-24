package fit

import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.InputStreamReader
import java.io.FileOutputStream

fun findFfmpegPath(): String {
    val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    
    // 1. Query Python's imageio_ffmpeg (Preferred as it typically installs a modern ffmpeg build)
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

    // 2. Check system PATH
    val ffmpegCmd = if (isWindows) "ffmpeg.exe" else "ffmpeg"
    try {
        val pb = ProcessBuilder(ffmpegCmd, "-version")
        val p = pb.start()
        p.waitFor()
        if (p.exitValue() == 0) {
            return ffmpegCmd
        }
    } catch (e: Exception) {}

    // 3. Fallback to default paths
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

class NativeHudEncoder(
    val settings: HudSettings, 
    val onProgress: (Float, String) -> Unit = { _, _ -> },
    val onFrameRendered: (BufferedImage) -> Unit = {},
    val pauseSupplier: () -> Boolean = { false },
    val cancelSupplier: () -> Boolean = { false },
    val customRenderer: ((HudCanvas, fit.FitParser.TelemetryPoint, List<fit.FitParser.TelemetryPoint>, List<Double>, Float) -> Unit)? = null
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
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            g.stroke = BasicStroke(width * scale)
            for (i in 0 until points.size - 1) {
                val p1x = (points[i].first * scale).toInt()
                val p1y = (points[i].second * scale).toInt()
                val p2x = (points[i+1].first * scale).toInt()
                val p2y = (points[i+1].second * scale).toInt()
                g.drawLine(p1x, p1y, p2x, p2y)
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

    fun encode(fitPath: String, videoPath: String, output: String, startUtc: String, maxDurationSeconds: Int = -1) {
        val ffmpegPath = findFfmpegPath()
        
        val workDir = File(System.getProperty("user.dir"), "temp_work")
        if (!workDir.exists()) workDir.mkdirs()
        val tempOutput = File(workDir, "encoding_temp.mp4")
        if (tempOutput.exists()) tempOutput.delete() // Clean up any stale temp files
        val logFile = File(workDir, "ffmpeg_log.txt")
        if (logFile.exists()) logFile.delete()

        val localVideoPath = videoPath

        val fitBytes = File(fitPath).readBytes()
        val parser = FitParser(fitBytes)
        parser.parse()
        var telemetry = parser.getTelemetry()
        if (telemetry.isEmpty()) return

        val startTime = Instant.parse(startUtc)
        val fitEpoch = Instant.parse("1989-12-31T00:00:00Z").epochSecond
        
        var videoWidth = 1920
        var videoHeight = 1080
        var videoDurationSeconds = 300
        var videoFps = "30" // default to 30
        try {
            // Run ffmpeg -i to gather duration and resolution via stderr stream to avoid missing ffprobe dependency
            val pb = ProcessBuilder(ffmpegPath, "-i", localVideoPath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            
            // Duration parsing: "Duration: 00:01:23.45"
            val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
            val durMatch = durRegex.find(output)
            if (durMatch != null) {
                val h = durMatch.groupValues[1].toInt()
                val m = durMatch.groupValues[2].toInt()
                val s = durMatch.groupValues[3].toInt()
                videoDurationSeconds = h * 3600 + m * 60 + s
            }
            
            // Resolution parsing: "Video: ..., 1920x1080 ..."
            val lines = output.lines()
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

        if (maxDurationSeconds > 0) {
            videoDurationSeconds = minOf(videoDurationSeconds, maxDurationSeconds)
        }

        val videoStartFit = startTime.epochSecond - fitEpoch
        val videoEndFit = (startTime.epochSecond + videoDurationSeconds) - fitEpoch
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
        val jobHash = kotlin.math.abs((fitPath + videoPath + startUtc + maxDurationSeconds + config.hashCode()).hashCode()).toString()
        val jobDir = File(workDir, "job_$jobHash")
        if (!jobDir.exists()) jobDir.mkdirs()

        val chunkSizeSeconds = 60
        var resumePartIndex = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) }?.size ?: 0
        var resumeSeconds = resumePartIndex * chunkSizeSeconds
        
        if (resumeSeconds > 0 && resumeSeconds < videoDurationSeconds) {
            println("🔄 RESUMING encode from chunk $resumePartIndex (${formatDuration(resumeSeconds)} / ${formatDuration(videoDurationSeconds)})")
            onProgress(resumeSeconds.toFloat() / videoDurationSeconds, "Resuming encode from ${formatDuration(resumeSeconds)}...")
        }

        val isEncodingActive = java.util.concurrent.atomic.AtomicBoolean(true)

        while (resumeSeconds < videoDurationSeconds && isEncodingActive.get()) {
            if (cancelSupplier()) {
                throw Exception("Encoding was canceled by user.")
            }
            while (pauseSupplier()) {
                if (cancelSupplier()) throw Exception("Encoding was canceled by user.")
                Thread.sleep(100)
            }
            
            val currentChunkEnd = minOf(resumeSeconds + chunkSizeSeconds, videoDurationSeconds)
            val chunkDuration = currentChunkEnd - resumeSeconds
            
            val pbArgs = mutableListOf<String>()
            pbArgs.add(ffmpegPath)
            pbArgs.add("-y")
            
            pbArgs.add("-f")
            pbArgs.add("rawvideo")
            pbArgs.add("-pixel_format")
            pbArgs.add("abgr")
            pbArgs.add("-video_size")
            pbArgs.add("${videoWidth}x${videoHeight}")
            pbArgs.add("-framerate")
            pbArgs.add("1")
            pbArgs.add("-i")
            pbArgs.add("pipe:0") // raw frames for this chunk
            
            if (hwaccel != null) {
                pbArgs.add("-hwaccel")
                pbArgs.add(hwaccel)
            }
            pbArgs.add("-ss")
            pbArgs.add(resumeSeconds.toString())
            pbArgs.add("-t")
            pbArgs.add(chunkDuration.toString())
            pbArgs.add("-i")
            pbArgs.add(localVideoPath)
            
            pbArgs.add("-filter_complex")
            pbArgs.add("[0:v]setpts=PTS-STARTPTS[hud];[1:v]setpts=PTS-STARTPTS[vid];[vid][hud]overlay=0:0:shortest=1")
            
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
            
            val tempPartFile = File(jobDir, "part_%04d.ts.tmp".format(resumePartIndex))
            val finalPartFile = File(jobDir, "part_%04d.ts".format(resumePartIndex))
            if (tempPartFile.exists()) tempPartFile.delete()
            
            pbArgs.add("-f")
            pbArgs.add("mpegts")
            pbArgs.add(tempPartFile.absolutePath)
            
            val pb = ProcessBuilder(pbArgs)
            pb.redirectErrorStream(true)
            val process = pb.start()
            
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
            val img = BufferedImage(videoWidth, videoHeight, BufferedImage.TYPE_4BYTE_ABGR)
            val frameTimes = mutableListOf<Long>()
            
            // Pre-fill power buffer for context if we're not at the start
            if (resumeSeconds > 0) {
                val prefillStart = maxOf(0, resumeSeconds - 30)
                for (preSec in prefillStart until resumeSeconds) {
                    val preUtc = startTime.plusSeconds(preSec.toLong()).epochSecond
                    val preFitTs = preUtc - fitEpoch
                    val pt = telemetry.find { it.timestamp >= preFitTs } ?: telemetry.last()
                    pBuf.add(pt.power)
                }
            }
            
            var telemetryIdx = 0
            try {
                loop@ for (i in resumeSeconds until currentChunkEnd) {
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
                    val currentUtc = startTime.plusSeconds(i.toLong()).epochSecond
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
                    
                    val scale = videoWidth.toFloat() / 1920f
                    val canvas = DesktopHudCanvas(g, scale)
                    if (customRenderer != null) {
                        customRenderer.invoke(canvas, point, telemetry, pBuf, i.toFloat() / videoDurationSeconds)
                    } else {
                        renderer.renderFrame(canvas, point, telemetry, pBuf, i.toFloat() / videoDurationSeconds)
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
                    
                    val remainingFrames = videoDurationSeconds - i
                    val remainingSecondsETA = if (currentFps > 0) (remainingFrames / currentFps).toInt() else 0
                    
                    val processedStr = formatDuration(i)
                    val totalStr = formatDuration(videoDurationSeconds)
                    val remainingStr = formatDuration(remainingSecondsETA)
                    val progressRatio = i.toFloat() / videoDurationSeconds
                    val progressPercent = (progressRatio * 100).toInt()
                    
                    val fpsStr = "%.1f fps".format(currentFps)
                    val speedStr = "%.1fx".format(currentFps)
                    
                    val statusText = "Encoding: $progressPercent% | $processedStr / $totalStr | Speed: $fpsStr ($speedStr) | ETA: $remainingStr"
                    onProgress(progressRatio, statusText)
                    
                    val targetW = 960
                    val targetH = (videoHeight * (targetW.toFloat() / videoWidth)).toInt().coerceAtLeast(1)
                    val copy = BufferedImage(targetW, targetH, img.type)
                    val g2d = copy.createGraphics()
                    g2d.drawImage(img, 0, 0, targetW, targetH, null)
                    g2d.dispose()
                    onFrameRendered(copy)
                }
            } catch (e: Exception) {
                println("\n❌ Pipe Write Error: ${e.message}")
            } finally {
                out.close()
                val exitCode = process.waitFor()
                readerThread.join(1000)
                cancelMonitorThread.join(1000)
                cancelMonitorThread.join(1000)
                
                if (cancelSupplier()) {
                    if (tempPartFile.exists()) tempPartFile.delete()
                    throw Exception("Encoding was canceled by user.")
                }
                
                if (exitCode != 0) {
                    if (tempPartFile.exists()) tempPartFile.delete()
                    throw Exception("ffmpeg exited with error code $exitCode. See ffmpeg_log.txt for details.")
                } else {
                    // Success! Rename temp part to final part
                    tempPartFile.renameTo(finalPartFile)
                    resumePartIndex++
                    resumeSeconds += chunkDuration
                }
            }
        }

        if (!cancelSupplier() && resumeSeconds >= videoDurationSeconds) {
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
                finalDest.absolutePath
            )
            
            val pb = ProcessBuilder(concatArgs)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            val p = pb.start()
            val concatExit = p.waitFor()
            
            if (concatExit != 0) {
                throw Exception("Failed to merge video segments. ffmpeg exited with code $concatExit.")
            }
            
            // Clean up chunks after successful concat
            parts.forEach { it.delete() }
            partsListFile.delete()
            jobDir.deleteRecursively() // Cleanup the whole job folder
            
            onProgress(1.0f, "✨ Finished Successfully!")
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
