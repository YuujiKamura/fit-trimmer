package fit

import java.awt.*
import java.awt.image.BufferedImage
import kotlin.math.pow
import java.io.File
import java.time.Instant
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.InputStreamReader
import java.io.FileOutputStream

var globalActiveJobDir: java.io.File? = null

fun EncodeProfileReport.appendToHistory(label: String) {
        try {
            val historyDir = java.io.File(System.getProperty("user.home"), ".fittrimmer_history")
            if (!historyDir.exists()) historyDir.mkdirs()
            val historyFile = java.io.File(historyDir, "encode_profile_history.csv")
            val isNewFile = !historyFile.exists() || historyFile.length() == 0L
            
            java.io.FileWriter(historyFile, true).use { writer ->
                if (isNewFile) {
                    writer.write("Timestamp,Label,TotalMs,MaskPlanMs,MaskVideoMs,FfmpegActiveMs,Frames,TelemetryMs,HudRenderMs,RawCopyMs,BufferWaitMs,PipeWriteMs,PipeMiB,AvgHudMs,AvgCopyMs,AvgWaitMs,AvgPipeMs\n")
                }
                val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                writer.write(
                    "$now,$label," +
                    "${"%.2f".format(totalElapsedMs)}," +
                    "${"%.2f".format(maskPlanMs)}," +
                    "${"%.2f".format(maskVideoMs)}," +
                    "${"%.2f".format(ffmpegActiveMs)}," +
                    "$frameCount," +
                    "${"%.2f".format(telemetryMs)}," +
                    "${"%.2f".format(hudRenderMs)}," +
                    "${"%.2f".format(rawCopyMs)}," +
                    "${"%.2f".format(bufferWaitMs)}," +
                    "${"%.2f".format(pipeWriteMs)}," +
                    "${"%.2f".format(pipeMiB)}," +
                    "${"%.3f".format(avgHudRenderMs)}," +
                    "${"%.3f".format(avgRawCopyMs)}," +
                    "${"%.3f".format(avgBufferWaitMs)}," +
                    "${"%.3f".format(avgPipeWriteMs)}\n"
                )
            }
        } catch (e: Exception) {
            println("WARNING: Failed to write encode profile history: ${e.message}")
        }
    }


private class EncodeProfiler {
    private val startNs = System.nanoTime()
    private val frameCount = java.util.concurrent.atomic.AtomicLong(0)
    private val pipeBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val maskPlanNs = java.util.concurrent.atomic.AtomicLong(0)
    private val maskVideoNs = java.util.concurrent.atomic.AtomicLong(0)
    private val ffmpegActiveNs = java.util.concurrent.atomic.AtomicLong(0)
    private val telemetryNs = java.util.concurrent.atomic.AtomicLong(0)
    private val hudRenderNs = java.util.concurrent.atomic.AtomicLong(0)
    private val rawCopyNs = java.util.concurrent.atomic.AtomicLong(0)
    private val bufferWaitNs = java.util.concurrent.atomic.AtomicLong(0)
    private val queuePutNs = java.util.concurrent.atomic.AtomicLong(0)
    private val livePreviewNs = java.util.concurrent.atomic.AtomicLong(0)
    private val progressNs = java.util.concurrent.atomic.AtomicLong(0)
    private val pipeWriteNs = java.util.concurrent.atomic.AtomicLong(0)

    fun addMaskPlan(ns: Long) = maskPlanNs.addAndGet(ns)
    fun addMaskVideo(ns: Long) = maskVideoNs.addAndGet(ns)
    fun addFfmpegActive(ns: Long) = ffmpegActiveNs.addAndGet(ns)
    fun addTelemetry(ns: Long) = telemetryNs.addAndGet(ns)
    fun addHudRender(ns: Long) = hudRenderNs.addAndGet(ns)
    fun addRawCopy(ns: Long) = rawCopyNs.addAndGet(ns)
    fun addBufferWait(ns: Long) = bufferWaitNs.addAndGet(ns)
    fun addQueuePut(ns: Long) = queuePutNs.addAndGet(ns)
    fun addLivePreview(ns: Long) = livePreviewNs.addAndGet(ns)
    fun addProgress(ns: Long) = progressNs.addAndGet(ns)
    fun addFrame() = frameCount.incrementAndGet()
    fun addPipeWrite(ns: Long, bytes: Int) {
        pipeWriteNs.addAndGet(ns)
        pipeBytes.addAndGet(bytes.toLong())
    }

    fun report(): EncodeProfileReport {
        fun ms(ns: Long): Double = ns.toDouble() / 1_000_000.0
        return EncodeProfileReport(
            totalElapsedMs = ms(System.nanoTime() - startNs),
            maskPlanMs = ms(maskPlanNs.get()),
            maskVideoMs = ms(maskVideoNs.get()),
            ffmpegActiveMs = ms(ffmpegActiveNs.get()),
            frameCount = frameCount.get(),
            telemetryMs = ms(telemetryNs.get()),
            hudRenderMs = ms(hudRenderNs.get()),
            rawCopyMs = ms(rawCopyNs.get()),
            bufferWaitMs = ms(bufferWaitNs.get()),
            queuePutMs = ms(queuePutNs.get()),
            livePreviewMs = ms(livePreviewNs.get()),
            progressMs = ms(progressNs.get()),
            pipeWriteMs = ms(pipeWriteNs.get()),
            pipeBytes = pipeBytes.get()
        )
    }
}

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
                                if (entry.name.endsWith("ffmpeg.exe") || entry.name.endsWith("ffmpeg")) {
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
                                if (entry.name.endsWith("ffmpeg.exe") || entry.name.endsWith("ffmpeg")) {
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

fun findFfprobePath(): String {
    val ffmpeg = findFfmpegPath()
    val ffprobe = if (ffmpeg.endsWith("ffmpeg.exe")) {
        ffmpeg.replace("ffmpeg.exe", "ffprobe.exe")
    } else if (ffmpeg.endsWith("ffmpeg")) {
        ffmpeg.substringBeforeLast("ffmpeg") + "ffprobe"
    } else {
        "ffprobe"
    }
    val file = File(ffprobe)
    return if (file.exists()) ffprobe else "ffprobe"
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

fun findTelemetryLerp(telemetry: List<fit.TelemetryPoint>, targetFitTs: Double): fit.TelemetryPoint {
    if (telemetry.isEmpty()) return fit.TelemetryPoint(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    if (targetFitTs <= telemetry.first().timestamp) return telemetry.first().copy(timestamp = targetFitTs, speed = 0.0, power = 0.0, cadence = 0.0)
    if (targetFitTs >= telemetry.last().timestamp) return telemetry.last().copy(timestamp = targetFitTs, speed = 0.0, power = 0.0, cadence = 0.0)

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

    return fit.TelemetryPoint(
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
    val customRenderer: ((HudCanvas, fit.TelemetryPoint, List<fit.TelemetryPoint>, List<fit.TelemetryPoint>, List<Double>, Float) -> Unit)? = null,
    val showLivePreviewSupplier: () -> Boolean = { true },
    val profileSink: ((EncodeProfileReport) -> Unit)? = null
) : HudEncoder {

    private val fontCache = java.util.concurrent.ConcurrentHashMap<String, Font>()
    private val metricsCache = java.util.concurrent.ConcurrentHashMap<Font, FontMetrics>()

    private var cachedMapKey: String? = null
    private var cachedMapImage: BufferedImage? = null
    private var cachedMapLeftLon = 0.0
    private var cachedMapTopLat = 0.0
    private var cachedMapRightLon = 0.0
    private var cachedMapBottomLat = 0.0

    inner class DesktopHudCanvas(val g: Graphics2D, val scale: Float, val logicalWidth: Float, val logicalHeight: Float) : HudCanvas {
        override val width: Float get() = logicalWidth
        override val height: Float get() = logicalHeight

        init {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        }

        private fun parseColor(colorStr: String): Color {
            val clean = colorStr.replace("#", "")
            return try {
                if (clean.length == 8) {
                    val argb = clean.toLong(16)
                    val a = ((argb shr 24) and 0xFF).toInt()
                    val r = ((argb shr 16) and 0xFF).toInt()
                    val g = ((argb shr 8) and 0xFF).toInt()
                    val b = (argb and 0xFF).toInt()
                    Color(r, g, b, a)
                } else {
                    Color.decode(colorStr)
                }
            } catch (e: Exception) {
                Color.WHITE
            }
        }

        private fun getCachedFont(size: Float, bold: Boolean, isWidthCheck: Boolean = false): Font {
            val actualSize = if (isWidthCheck) size.toInt() else (size * scale).toInt().coerceAtLeast(1)
            val key = "${actualSize}_${bold}"
            return fontCache.getOrPut(key) {
                Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, actualSize)
            }
        }

        private fun getCachedMetrics(font: Font): FontMetrics {
            return metricsCache.getOrPut(font) {
                g.getFontMetrics(font)
            }
        }

        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val font = getCachedFont(size, bold)
            g.font = font
            
            val metrics = getCachedMetrics(font)
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
            
            g.color = parseColor(color)
            g.drawString(text, drawX, sy)
        }

        override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean, rx: Float, ry: Float) {
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            val sx = (x * scale).toInt()
            val sy = (y * scale).toInt()
            val sw = (w * scale).toInt()
            val sh = (h * scale).toInt()
            val srx = (rx * scale).toInt()
            val sry = (ry * scale).toInt()
            if (srx > 0 && sry > 0) {
                if (outline) {
                    g.drawRoundRect(sx, sy, sw, sh, srx * 2, sry * 2)
                } else {
                    g.fillRoundRect(sx, sy, sw, sh, srx * 2, sry * 2)
                }
            } else {
                if (outline) {
                    g.drawRect(sx, sy, sw, sh)
                } else {
                    g.fillRect(sx, sy, sw, sh)
                }
            }
        }

        override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {
            if (points.isEmpty()) return
            val c = Color.decode(color)
            g.color = Color(c.red, c.green, c.blue, (alpha * 255).toInt())
            g.stroke = BasicStroke(width * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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
            val font = getCachedFont(size, bold, isWidthCheck = true)
            return getCachedMetrics(font).stringWidth(text).toFloat()
        }

        override fun drawMapBackground(
            videoPoints: List<TelemetryPoint>,
            mcx: Float,
            mcy: Float,
            R: Float,
            padR: Float,
            sf: Float,
            pathBearing: Double,
            cosLat: Double,
            dx: Double,
            dy: Double,
            L: Double,
            cxL: Double,
            cyL: Double,
            dynamicScale: Double
        ) {
            val meanLat = (videoPoints.minOf { it.lat } + videoPoints.maxOf { it.lat }) / 2.0
            val meanLon = (videoPoints.minOf { it.lon } + videoPoints.maxOf { it.lon }) / 2.0
            val maxR_local = (R / dynamicScale) * 1.4
            
            val minLat = meanLat - maxR_local
            val maxLat = meanLat + maxR_local
            val minLon = meanLon - maxR_local / cosLat
            val maxLon = meanLon + maxR_local / cosLat
            
            val calculatedZ = calculateBestZoom(minLat, maxLat, minLon, maxLon)
            val z = (calculatedZ + settings.mapZoomOffset).coerceIn(9, 18)
            val actualMapType = if (settings.mapType == "auto") {
                when {
                    z >= 14 -> "openstreetmap"
                    z == 13 -> "carto_voyager"
                    else -> "carto_light"
                }
            } else {
                settings.mapType
            }

            val mapKey = "${settings.mapType}_${settings.mapZoomOffset}_${settings.fixMapNorthUp}_${minLat}_${maxLat}_${minLon}_${maxLon}"
            
            var mapImg = cachedMapImage
            var leftLon = cachedMapLeftLon
            var topLat = cachedMapTopLat
            var rightLon = cachedMapRightLon
            var bottomLat = cachedMapBottomLat
            
            if (cachedMapKey != mapKey || mapImg == null) {
                val latRadMin = minLat * kotlin.math.PI / 180.0
                val latRadMax = maxLat * kotlin.math.PI / 180.0
                val n = 2.0.pow(z.toDouble())
                
                val minTileX = ((minLon + 180.0) / 360.0 * n).toInt()
                val maxTileX = ((maxLon + 180.0) / 360.0 * n).toInt()
                
                val minTileY = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRadMax) + 1.0 / kotlin.math.cos(latRadMax)) / kotlin.math.PI) / 2.0 * n).toInt()
                val maxTileY = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRadMin) + 1.0 / kotlin.math.cos(latRadMin)) / kotlin.math.PI) / 2.0 * n).toInt()
                
                val tX1 = minOf(minTileX, maxTileX)
                val tX2 = maxOf(minTileX, maxTileX)
                val tY1 = minOf(minTileY, maxTileY)
                val tY2 = maxOf(minTileY, maxTileY)
                
                val Wt = tX2 - tX1 + 1
                val Ht = tY2 - tY1 + 1
                
                if (Wt in 1..8 && Ht in 1..8) {
                    val merged = BufferedImage(Wt * 256, Ht * 256, BufferedImage.TYPE_INT_ARGB)
                    val g2 = merged.createGraphics()
                    
                    val cacheDir = java.io.File(System.getProperty("user.home") + "/.fit-trimmer/osm_cache")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    
                    val client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(3))
                        .build()
                        
                    for (ty in tY1..tY2) {
                        for (tx in tX1..tX2) {
                            val tileFile = java.io.File(cacheDir, "${actualMapType}_${z}_${tx}_${ty}.png")
                            var img: BufferedImage? = null
                            if (tileFile.exists()) {
                                try {
                                    img = javax.imageio.ImageIO.read(tileFile)
                                } catch (e: Exception) {
                                    tileFile.delete()
                                }
                            }
                            if (img == null) {
                                val urlStr = when (actualMapType) {
                                    "carto_light" -> "https://basemaps.cartocdn.com/light_all/$z/$tx/$ty.png"
                                    "carto_dark" -> "https://basemaps.cartocdn.com/dark_all/$z/$tx/$ty.png"
                                    "carto_voyager" -> "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$tx/$ty.png"
                                    else -> "https://tile.openstreetmap.org/$z/$tx/$ty.png"
                                }
                                try {
                                    val request = java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(urlStr))
                                        .header("User-Agent", "FitTrimmerApp/1.0 (contact: yuujiKamura on GitHub)")
                                        .timeout(java.time.Duration.ofSeconds(3))
                                        .build()
                                    val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                                    if (response.statusCode() == 200) {
                                        val bytes = response.body()
                                        java.nio.file.Files.write(tileFile.toPath(), bytes)
                                        img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                                    }
                                } catch (e: Exception) {
                                    // Ignore errors
                                }
                            }
                            
                            if (img != null) {
                                val dx = (tx - tX1) * 256
                                val dy = (ty - tY1) * 256
                                g2.drawImage(img, dx, dy, null)
                            }
                        }
                    }
                    g2.dispose()
                    mapImg = merged
                    
                    leftLon = tX1.toDouble() / n * 360.0 - 180.0
                    val nLat = kotlin.math.PI - 2.0 * kotlin.math.PI * tY1.toDouble() / n
                    topLat = 180.0 / kotlin.math.PI * kotlin.math.atan(0.5 * (kotlin.math.exp(nLat) - kotlin.math.exp(-nLat)))
                    
                    rightLon = (tX2 + 1).toDouble() / n * 360.0 - 180.0
                    val sLat = kotlin.math.PI - 2.0 * kotlin.math.PI * (tY2 + 1).toDouble() / n
                    bottomLat = 180.0 / kotlin.math.PI * kotlin.math.atan(0.5 * (kotlin.math.exp(sLat) - kotlin.math.exp(-sLat)))
                    
                    cachedMapImage = mapImg
                    cachedMapLeftLon = leftLon
                    cachedMapTopLat = topLat
                    cachedMapRightLon = rightLon
                    cachedMapBottomLat = bottomLat
                    cachedMapKey = mapKey
                }
            }
            
            if (mapImg != null) {
                val originalClip = g.clip
                try {
                    val clipCircle = java.awt.geom.Ellipse2D.Float(
                        (mcx - R) * scale,
                        (mcy - R) * scale,
                        (R * 2f) * scale,
                        (R * 2f) * scale
                    )
                    g.clip = clipCircle
                    
                    val startPt = videoPoints.first()
                    val L_proj = if (settings.fixMapNorthUp) 0.0 else L
                    
                    fun localX(lon: Double, lat: Double): Double {
                        val px = (lon - startPt.lon) * cosLat
                        val py = lat - startPt.lat
                        return if (L_proj > 1e-7) (px * dy - py * dx) / L_proj else px
                    }
                    
                    fun localY(lon: Double, lat: Double): Double {
                        val px = (lon - startPt.lon) * cosLat
                        val py = lat - startPt.lat
                        return if (L_proj > 1e-7) -(px * dx + py * dy) / L_proj else -py
                    }
                    
                    fun screenX(lon: Double, lat: Double): Float {
                        val lx = localX(lon, lat)
                        return ((mcx + (lx - cxL) * dynamicScale) * scale).toFloat()
                    }
                    
                    fun screenY(lon: Double, lat: Double): Float {
                        val ly = localY(lon, lat)
                        return ((mcy + (ly - cyL) * dynamicScale) * scale).toFloat()
                    }
                    
                    val x0 = screenX(leftLon, topLat)
                    val y0 = screenY(leftLon, topLat)
                    val x1 = screenX(rightLon, topLat)
                    val y1 = screenY(rightLon, topLat)
                    val x2 = screenX(leftLon, bottomLat)
                    val y2 = screenY(leftLon, bottomLat)
                    
                    val wImg = mapImg.width.toDouble()
                    val hImg = mapImg.height.toDouble()
                    
                    if (wImg > 0 && hImg > 0) {
                        val m00 = (x1 - x0) / wImg
                        val m10 = (y1 - y0) / wImg
                        val m01 = (x2 - x0) / hImg
                        val m11 = (y2 - y0) / hImg
                        val m02 = x0.toDouble()
                        val m12 = y0.toDouble()
                        
                        val transform = java.awt.geom.AffineTransform(m00, m10, m01, m11, m02, m12)
                        
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.drawImage(mapImg, transform, null)
                    }
                } finally {
                    g.clip = originalClip
                }
            }
        }
        
        private fun calculateBestZoom(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Int {
            val maxDiff = maxOf(maxLat - minLat, maxLon - minLon)
            if (maxDiff <= 0.0) return 16
            
            for (z in 16 downTo 9) {
                val n = 2.0.pow(z.toDouble())
                val tileSizeDeg = 360.0 / n
                if (maxDiff < tileSizeDeg * 2.0) {
                    return z
                }
            }
            return 9
        }
    }


    private fun generateMaskVideo(
        ffmpegPath: String,
        outputFile: File,
        maskFramePlan: List<List<MappedPlateBox>>,
        width: Int,
        height: Int,
        fps: String
    ) {
        if (maskFramePlan.isEmpty()) return
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val args = listOf(
            ffmpegPath, "-y",
            "-f", "rawvideo",
            "-pixel_format", "gray",
            "-video_size", "${width}x${height}",
            "-framerate", fps,
            "-i", "pipe:0",
            "-an",
            "-c:v", "ffv1",
            "-level", "3",
            "-threads", "0",
            "-slices", "16",
            outputFile.absolutePath
        )

        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val logThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("FFMPEG-MASK: $line")
                }
            } catch (e: Exception) {
                if (process.isAlive) e.printStackTrace()
            }
        }
        logThread.start()

        val dataSize = width * height
        val blackFrame = ByteArray(dataSize) // Pre-allocated and pre-cleared black frame (all zeros)
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val data = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
        val g = img.createGraphics()
        try {
            for (boxes in maskFramePlan) {
                if (cancelSupplier()) {
                    process.destroy()
                    try { process.destroyForcibly() } catch (e: Exception) {}
                    throw Exception("Encoding was canceled by user during mask generation.")
                }
                
                if (boxes.isEmpty()) {
                    // No mask boxes for this frame: completely skip zero-fill and AWT drawing.
                    // Directly write the pre-cached black frame to FFmpeg pipeline.
                    process.outputStream.write(blackFrame)
                } else {
                    // Mask boxes exist: clean transient buffer, draw white rects, and write.
                    java.util.Arrays.fill(data, 0.toByte())
                    g.color = java.awt.Color.WHITE
                    for (box in boxes) {
                        val x = box.x.toInt()
                        val y = box.y.toInt()
                        val w = box.width.toInt()
                        val h = box.height.toInt()
                        if (w > 0 && h > 0) {
                            g.fillRect(x, y, w, h)
                        }
                    }
                    process.outputStream.write(data)
                }
            }
        } finally {
            g.dispose()
            try { process.outputStream.close() } catch (e: Exception) {}
        }

        val exitCode = process.waitFor()
        try { logThread.interrupt() } catch (e: Exception) {}
        try { logThread.join(1000) } catch (e: Exception) {}

        if (cancelSupplier()) {
            outputFile.delete()
            throw Exception("Encoding was canceled by user during mask generation.")
        }
        if (exitCode != 0 || !outputFile.exists() || outputFile.length() == 0L) {
            throw Exception("Failed to generate mask video. ffmpeg exited with code $exitCode.")
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
            // CPU fallback HEVC (check if libx265 is available)
            if (testEncoder(ffmpegPath, "libx265", null)) {
                return Pair(null, "libx265")
            }
            // If libx265 is not supported, fall back to H.264 software encoder
            println("⚠️ libx265 not supported in this FFmpeg build. Falling back to libx264 for encoding...")
            if (testEncoder(ffmpegPath, "libx264", null)) {
                return Pair(null, "libx264")
            }
            return Pair(null, "libopenh264")
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

    override fun encode(
        fitPath: String,
        videoPath: String,
        output: String,
        startUtc: String,
        maxDurationSeconds: Int,
        trimStartSeconds: Double,
        trimEndSeconds: Double,
        shouldResume: Boolean,
        skipConcat: Boolean,
        groundTruthMetadata: EncodeGroundTruthMetadata?,
        hudTelemetryStartSeconds: Double?,
        hudTelemetryEndSeconds: Double?
    ) {
        val profiler = EncodeProfiler()
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
                p.parse(cancelSupplier)
                parser = p
                p.getTelemetry(cancelSupplier)
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
        var hasAudioStream = false
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
            if (outputInfo.contains("Audio:")) {
                hasAudioStream = true
                println("DEBUG: NativeHudEncoder audio stream detected")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        var exportWidth = if (settings.cropToSquare) videoHeight else videoWidth
        var exportHeight = videoHeight
        val maxLongEdge = when (settings.exportResolution) {
            "1080p" -> 1920.0
            "strava" -> 1280.0
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

        val activeSegments = settings.speedSegments.mapNotNull { seg ->
            val sShifted = seg.startSeconds - actualTrimStart
            val eShifted = seg.endSeconds - actualTrimStart
            val sCropped = maxOf(0.0, sShifted)
            val eCropped = minOf(targetDurationSeconds.toDouble(), eShifted)
            if (sCropped < eCropped) {
                SpeedSegment(
                    id = seg.id,
                    startSeconds = sCropped,
                    endSeconds = eCropped,
                    speedFactor = seg.speedFactor
                )
            } else {
                null
            }
        }.sortedBy { it.startSeconds }

        val targetStart = SpeedMapper.mapSourceToTarget(actualTrimStart, settings.speedSegments)
        val targetEnd = SpeedMapper.mapSourceToTarget(actualTrimStart + targetDurationSeconds, settings.speedSegments)
        val finalOutputDuration = if (settings.speedSegments.isEmpty()) {
            targetDurationSeconds.toDouble()
        } else {
            targetEnd - targetStart
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
            pbArgs.addAll(groundTruthMetadata?.toFfmpegMetadataArgs() ?: emptyList())
            pbArgs.add(tempOutput.absolutePath)
            
            val pb = ProcessBuilder(pbArgs)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val ffmpegActiveStartNs = System.nanoTime()
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

        val startUtcSeconds = startTime.toEpochMilli() / 1000.0
        val hudTelemetryStart = hudTelemetryStartSeconds
            ?.coerceIn(0.0, videoDurationSeconds.toDouble())
            ?: actualTrimStart
        val hudTelemetryEnd = hudTelemetryEndSeconds
            ?.takeIf { it > hudTelemetryStart }
            ?.coerceIn(hudTelemetryStart, videoDurationSeconds.toDouble())
            ?: actualTrimEnd
        val videoStartFit = startUtcSeconds + hudTelemetryStart - fitEpoch
        val videoEndFit = startUtcSeconds + hudTelemetryEnd - fitEpoch
        val trimmedTelemetryRaw = telemetry.filter { it.timestamp in videoStartFit..videoEndFit }
        val trimmedTelemetry = if (trimmedTelemetryRaw.isNotEmpty()) trimmedTelemetryRaw else telemetry

        val config = HudConfig(
            valSize = settings.valSize, tightness = settings.tightness, spacing = settings.spacing,
            xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW,
            captionPosition = settings.captionPosition,
            roadCaptions = settings.roadCaptions,
            powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
            useImperialUnits = settings.useImperialUnits,
            language = settings.language,
            elevationGraphScope = settings.elevationGraphScope,
            heartRateAccumulationScope = settings.heartRateAccumulationScope,
            showSpeed = settings.showSpeed,
            showCadence = settings.showCadence,
            showHeartRate = settings.showHeartRate,
            showPower = settings.showPower,
            showWkg = settings.showWkg,
            showPowerTrend = settings.showPowerTrend,
            showGrade = settings.showGrade,
            showElevation = settings.showElevation,
            showDistanceTime = settings.showDistanceTime,
            bodyWeightKg = settings.bodyWeightKg,
            customCaptions = settings.customCaptions,
            mapSizeScale = settings.mapSizeScale,
            mapType = settings.mapType,
            mapPosition = settings.mapPosition,
            hudBgAlpha = settings.hudBgAlpha,
            mapZoomScale = settings.mapZoomScale,
            mapZoomOffset = settings.mapZoomOffset,
            fixMapNorthUp = settings.fixMapNorthUp,
            mapMarkerSizeScale = settings.mapMarkerSizeScale,
            mapTextSizeScale = settings.mapTextSizeScale,
            trimStartSeconds = actualTrimStart,
            mapRangeMode = settings.mapRangeMode,
            textShadowAlpha = settings.textShadowAlpha
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
        val jobHash = calculateJobHash(
            fitPath = fitPath,
            videoPath = videoPath,
            startUtc = startUtc,
            maxDurationSeconds = maxDurationSeconds,
            actualTrimStart = actualTrimStart,
            actualTrimEnd = actualTrimEnd,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            exportWidth = exportWidth,
            exportHeight = exportHeight,
            config = config,
            settings = settings,
            plateCache = plateCache
        )
        val jobDir = File(workDir, "job_$jobHash")
        if (!jobDir.exists()) jobDir.mkdirs()
        try { File(jobDir, ".video_source").writeText(videoPath) } catch(e: Exception) { e.printStackTrace() }
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
        var resumePartIndex = 0
        if (shouldResume) {
            resumePartIndex = existingParts.size
            if (resumePartIndex > 0) {
                try {
                    val lastFile = existingParts.last()
                    lastFile.delete()
                    resumePartIndex--
                    println("笞・・Detected crash recovery: deleted potentially incomplete last chunk ${lastFile.name}. Resuming from chunk $resumePartIndex.")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            existingParts.forEach {
                try {
                    it.delete()
                    println("ｧｹ Discarded existing job cache file: ${it.name}")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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


            val targetResume = targetStart + ffmpegStartSeconds
            val ffmpegInputStartMapped = if (settings.speedSegments.isEmpty()) {
                actualTrimStart + ffmpegStartSeconds
            } else {
                SpeedMapper.mapTargetToSource(targetResume, settings.speedSegments)
            }
            
            val remainingSourceDuration = (actualTrimStart + targetDurationSeconds) - ffmpegInputStartMapped
            val remainingDuration = remainingSourceDuration

            val runBlur = settings.blurLicensePlates && plateCache != null && plateCache.records.isNotEmpty()
            val fpsDouble = videoFps.toDoubleOrNull() ?: 30.0
            val totalFrames = (finalOutputDuration * fpsDouble).toInt()
            val resumeFrames = (resumeSeconds * fpsDouble).toInt()
            val maskFramePlan = if (runBlur) {
                val maskPlanStartNs = System.nanoTime()
                val is90Or270 = videoRotation == 90 || videoRotation == -270 || videoRotation == 270 || videoRotation == -90
                val fallbackSourceW = if (is90Or270) videoHeight else videoWidth
                val fallbackSourceH = if (is90Or270) videoWidth else videoHeight
                val planned = plateCache?.buildMappedMaskFrames(
                    totalFrames = totalFrames,
                    fps = fpsDouble,
                    isBlurEnabled = settings.blurLicensePlates,
                    expandRatio = settings.plateMaskExpandRatio,
                    fallbackSourceWidth = fallbackSourceW,
                    fallbackSourceHeight = fallbackSourceH,
                    targetWidth = exportWidth.toFloat(),
                    targetHeight = exportHeight.toFloat(),
                    timeBufferMs = settings.plateMaskTimeBufferMs,
                    sourceStartTimeMs = (actualTrimStart * 1000.0).toLong(),
                    speedSegments = settings.speedSegments,
                    cropToSquare = settings.cropToSquare
                ) ?: emptyList()
                val maskedFrameCount = planned.count { it.isNotEmpty() }
                println("DEBUG: Precomputed plate mask plan: $maskedFrameCount/$totalFrames frames contain masks.")
                profiler.addMaskPlan(System.nanoTime() - maskPlanStartNs)
                planned
            } else {
                emptyList()
            }
            val jobState = JobStateManager.loadState(jobDir, jobHash).let {
                if (it.videoPath == null || groundTruthMetadata != null) {
                    val updated = it.copy(
                        videoPath = videoPath,
                        sourceVideoStartUtc = groundTruthMetadata?.sourceVideoStartUtc ?: it.sourceVideoStartUtc,
                        alignedVideoStartUtc = groundTruthMetadata?.alignedVideoStartUtc ?: it.alignedVideoStartUtc,
                        timeOffsetMillis = groundTruthMetadata?.timeOffsetMillis ?: it.timeOffsetMillis
                    )
                    JobStateManager.saveState(jobDir, updated)
                    updated
                } else it
            }
            val maskVideoFile = if (runBlur) File(jobDir, "plate_mask.mkv") else null
            val isMaskVideoValid = maskVideoFile != null && maskVideoFile.exists() && maskVideoFile.length() > 0L
            val shouldGenerateMaskVideo = runBlur && maskVideoFile != null &&
                (!shouldResume || !jobState.isPlateMaskStreamReady || !isMaskVideoValid)
            if (shouldGenerateMaskVideo) {
                val maskFile = maskVideoFile ?: throw Exception("Plate mask output path was not initialized.")
                onProgress(resumeSeconds.toFloat() / finalOutputDuration.toFloat(), "Preparing plate mask stream...")
                val maskVideoStartNs = System.nanoTime()
                generateMaskVideo(
                    ffmpegPath = ffmpegPath,
                    outputFile = maskFile,
                    maskFramePlan = maskFramePlan,
                    width = exportWidth,
                    height = exportHeight,
                    fps = videoFps
                )
                profiler.addMaskVideo(System.nanoTime() - maskVideoStartNs)
                JobStateManager.saveState(jobDir, jobState.copy(isPlateMaskStreamReady = true))
            }
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
                ffmpegInputStartMapped
            }
            pbArgs.add("-ss")
            pbArgs.add(seekStart.toString())
            pbArgs.add("-t")
            pbArgs.add(remainingDuration.toString())
            pbArgs.add("-i")
            pbArgs.add(localVideoPath)

            if (runBlur && maskVideoFile != null) {
                pbArgs.add("-ss")
                pbArgs.add(ffmpegStartSeconds.toString())
                pbArgs.add("-i")
                pbArgs.add(maskVideoFile.absolutePath)
            }
            
            pbArgs.add("-filter_complex")
            val hasSpeed = activeSegments.isNotEmpty()
            val cropExpr = if (settings.cropToSquare) "crop=min(in_w\\,in_h):min(in_w\\,in_h)," else ""
            if (hasSpeed) {
                val setptsExpr = generateSetptsExpression(activeSegments)
                
                val videoFilter = if (runBlur) {
                    "[0:v]setpts=PTS-STARTPTS[hud];" +
                    "[2:v]scale=$exportWidth:$exportHeight,format=yuv420p,setpts=PTS-STARTPTS[mask];" +
                    "[1:v]${cropExpr}scale=$exportWidth:$exportHeight,setpts='$setptsExpr',split[vid_orig][vid_blur_src];" +
                    "[vid_blur_src]scale=w=${exportWidth}/20:h=${exportHeight}/20,scale=w=$exportWidth:h=$exportHeight:flags=neighbor[vid_blurred];" +
                    "[vid_orig][vid_blurred][mask]maskedmerge[vid_merged];" +
                    "[vid_merged][hud]overlay=0:0:shortest=1[outv]"
                } else {
                    "[1:v]${cropExpr}scale=$exportWidth:$exportHeight,setpts='$setptsExpr'[vid];" +
                    "[vid][0:v]overlay=0:0:shortest=1[outv]"
                }
                
                if (hasAudioStream) {
                    val intervals = mutableListOf<TimelineInterval>()
                    var currentInt = 0.0
                    for (seg in activeSegments) {
                        if (seg.startSeconds > currentInt) {
                            intervals.add(TimelineInterval(currentInt, seg.startSeconds, isSpeed = false, speedFactor = 1.0))
                        }
                        if (seg.endSeconds > seg.startSeconds) {
                            intervals.add(TimelineInterval(seg.startSeconds, seg.endSeconds, isSpeed = true, speedFactor = seg.speedFactor))
                        }
                        currentInt = seg.endSeconds
                    }
                    if (targetDurationSeconds.toDouble() > currentInt) {
                        intervals.add(TimelineInterval(currentInt, targetDurationSeconds.toDouble(), isSpeed = false, speedFactor = 1.0))
                    }
                    
                    val audioFilterString = generateAudioFilterString(intervals)
                    pbArgs.add(videoFilter + ";" + audioFilterString)
                    
                    pbArgs.add("-map")
                    pbArgs.add("[outv]")
                    pbArgs.add("-map")
                    pbArgs.add("[outa]")
                } else {
                    pbArgs.add(videoFilter)
                    pbArgs.add("-map")
                    pbArgs.add("[outv]")
                }
            } else {
                if (runBlur) {
                    pbArgs.add(
                        "[0:v]setpts=PTS-STARTPTS[hud];" +
                        "[2:v]scale=$exportWidth:$exportHeight,format=yuv420p,setpts=PTS-STARTPTS[mask];" +
                        "[1:v]${cropExpr}scale=$exportWidth:$exportHeight,setpts=PTS-STARTPTS,split[vid_orig][vid_blur_src];" +
                        "[vid_blur_src]scale=w=${exportWidth}/20:h=${exportHeight}/20,scale=w=$exportWidth:h=$exportHeight:flags=neighbor[vid_blurred];" +
                        "[vid_orig][vid_blurred][mask]maskedmerge[vid_merged];" +
                        "[vid_merged][hud]overlay=0:0:shortest=1"
                    )
                } else {
                    pbArgs.add(
                        "[1:v]${cropExpr}scale=$exportWidth:$exportHeight,setpts=PTS-STARTPTS[vid];" +
                        "[vid][0:v]overlay=0:0:shortest=1"
                    )
                }
            }
            
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
            
            if (settings.exportResolution == "strava") {
                val fpsVal = videoFps.toIntOrNull() ?: 30
                val gopSize = fpsVal * 2
                pbArgs.add("-g")
                pbArgs.add(gopSize.toString())
                pbArgs.add("-keyint_min")
                pbArgs.add(gopSize.toString())
                pbArgs.add("-sc_threshold")
                pbArgs.add("0")
                pbArgs.add("-b:v")
                pbArgs.add("4M")
                pbArgs.add("-minrate")
                pbArgs.add("2.5M")
                pbArgs.add("-maxrate")
                pbArgs.add("5M")
                pbArgs.add("-bufsize")
                pbArgs.add("8M")
                if (encoderName.contains("h264") || encoderName.contains("x264")) {
                    pbArgs.add("-profile:v")
                    pbArgs.add("high")
                    pbArgs.add("-level")
                    pbArgs.add("4.1")
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
            val ffmpegActiveStartNs = System.nanoTime()

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
            val img = BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_4BYTE_ABGR)

            // Rings buffer allocation based on actual raw bytes size of the BufferedImage to prevent sizing discrepancies
            val sampleRawBytes = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
            val actualBufferSize = sampleRawBytes.size

            val bufferCount = 4
            val freeBuffers = java.util.concurrent.ArrayBlockingQueue<ByteArray>(bufferCount)
            for (i in 0 until bufferCount) {
                freeBuffers.add(ByteArray(actualBufferSize))
            }
            
            val frameQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(bufferCount)
            val pipeWriterException = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            
            val pipeWriterThread = Thread {
                try {
                    while (isEncodingActive.get() || frameQueue.isNotEmpty()) {
                        val bytes = frameQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (bytes != null) {
                            val pipeStartNs = System.nanoTime()
                            out.write(bytes)
                            out.flush()
                            profiler.addPipeWrite(System.nanoTime() - pipeStartNs, bytes.size)
                            freeBuffers.offer(bytes)
                        }
                    }
                } catch (e: Exception) {
                    pipeWriterException.set(e)
                    println("\n❌ Pipe Write Thread Error: ${e.message}")
                }
            }
            pipeWriterThread.start()
            val frameTimes = mutableListOf<Long>()
            
            // Pre-fill power buffer for context if we're not at the start
            // Pre-fill power buffer for context if we're not at the start
            if (resumeSeconds > 0) {
                val prefillStart = maxOf(0, resumeSeconds - settings.powerTrendSpanSeconds)
                for (preSec in prefillStart until resumeSeconds) {
                    val preSecInSource = if (settings.speedSegments.isEmpty()) {
                        actualTrimStart + preSec.toDouble()
                    } else {
                        SpeedMapper.mapTargetToSource(targetStart + preSec, settings.speedSegments)
                    }
                    val preUtc = (startTime.toEpochMilli() / 1000.0) + preSecInSource
                    val preFitTs = preUtc - fitEpoch
                    val pt = telemetry.find { it.timestamp >= preFitTs } ?: telemetry.last()
                    pBuf.add(pt.power)
                }
            }
            
            var telemetryIdx = 0
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
                    val currentSecInSource = if (settings.speedSegments.isEmpty()) {
                        actualTrimStart + currentSec
                    } else {
                        SpeedMapper.mapTargetToSource(targetStart + currentSec, settings.speedSegments)
                    }
                    val currentUtc = (startTime.toEpochMilli() / 1000.0) + currentSecInSource
                    val currentFitTs = currentUtc - fitEpoch

                    val telemetryStartNs = System.nanoTime()
                    val point = findTelemetryLerp(telemetry, currentFitTs)

                    val currentSecInt = currentSec.toInt()
                    if (currentSecInt > lastPowerSec) {
                        pBuf.add(point.power)
                        if (pBuf.size > settings.powerTrendSpanSeconds) pBuf.removeAt(0)
                        lastPowerSec = currentSecInt
                    }
                    profiler.addTelemetry(System.nanoTime() - telemetryStartNs)

                    val g = img.createGraphics()
                    g.composite = AlphaComposite.Clear
                    g.fillRect(0, 0, exportWidth, exportHeight)
                    g.composite = AlphaComposite.SrcOver

                    val isValid = currentFitTs >= telemetry.first().timestamp && currentFitTs <= telemetry.last().timestamp

                    // Render HUD in the top half
                    val gHud = g.create() as java.awt.Graphics2D
                    gHud.clipRect(0, 0, exportWidth, exportHeight)
                    val baseWidthForScale = if (settings.cropToSquare) exportHeight.toFloat() * (16f / 9f) else exportWidth.toFloat()
                    val scale = baseWidthForScale / 1920f
                    val canvas = DesktopHudCanvas(gHud, scale, exportWidth.toFloat() / scale, exportHeight.toFloat() / scale)
                    val hudStartNs = System.nanoTime()
                    if (customRenderer != null) {
                        customRenderer.invoke(canvas, point, telemetry, trimmedTelemetry, pBuf, currentSec.toFloat())
                    } else {
                        renderer.renderFrame(canvas, point, telemetry, trimmedTelemetry, pBuf, currentSec.toFloat(), isValid)
                    }
                    profiler.addHudRender(System.nanoTime() - hudStartNs)
                    gHud.dispose()
                    g.dispose()

                    val rawBytes = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
                    var targetBuf: ByteArray? = null
                    val bufferWaitStartNs = System.nanoTime()
                    var attempts = 0
                    while (isEncodingActive.get()) {
                        pipeWriterException.get()?.let { throw it }
                        targetBuf = freeBuffers.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (targetBuf != null) break
                        if (!process.isAlive) {
                            throw Exception("FFmpeg process terminated prematurely.")
                        }
                        if (cancelSupplier()) break
                        attempts++
                        if (attempts > 50) { // 100ms * 50 = 5 seconds stall detection
                            throw Exception("Encoding pipeline stalled: free buffers unavailable for 5 seconds.")
                        }
                    }
                    profiler.addBufferWait(System.nanoTime() - bufferWaitStartNs)

                    if (targetBuf != null) {
                        val copyStartNs = System.nanoTime()
                        System.arraycopy(rawBytes, 0, targetBuf, 0, minOf(rawBytes.size, targetBuf.size))
                        profiler.addRawCopy(System.nanoTime() - copyStartNs)
                        val queuePutStartNs = System.nanoTime()
                        frameQueue.put(targetBuf)
                        profiler.addQueuePut(System.nanoTime() - queuePutStartNs)
                    }
                    
                    val loopEnd = System.currentTimeMillis()
                    val elapsedLoop = loopEnd - loopStart
                    frameTimes.add(elapsedLoop)
                    if (frameTimes.size > 24) frameTimes.removeAt(0)
                    
                    val avgFrameTime = frameTimes.average()
                    val currentFps = if (avgFrameTime > 0) 1000.0 / avgFrameTime else 0.0
                    
                    val remainingFrames = totalFrames - f
                    val remainingSecondsETA = if (currentFps > 0) (remainingFrames / currentFps).toInt() else 0
                    
                    val processedStr = formatDuration(currentSec.toInt())
                    val totalStr = formatDuration(finalOutputDuration.toInt())
                    val remainingStr = formatDuration(remainingSecondsETA)
                    val progressRatio = f.toFloat() / totalFrames
                    val progressPercent = (progressRatio * 100).toInt()
                    
                    val fpsStr = "%.1f fps".format(currentFps)
                    val speedStr = "%.1fx".format(currentFps / fpsDouble)
                    
                    val statusText = "Encoding: $progressPercent% | $processedStr / $totalStr | Speed: $fpsStr ($speedStr) | ETA: $remainingStr"
                    val progressStartNs = System.nanoTime()
                    onProgress(progressRatio, statusText)
                    profiler.addProgress(System.nanoTime() - progressStartNs)

                    if (showLivePreviewSupplier()) {
                        val previewStartNs = System.nanoTime()
                        val targetW = 960
                        val targetH = (exportHeight * (targetW.toFloat() / exportWidth)).toInt().coerceAtLeast(1)
                        val copy = BufferedImage(targetW, targetH, img.type)
                        val g2d = copy.createGraphics()
                        if (runBlur) {
                            g2d.drawImage(img, 0, 0, targetW, targetH, 0, 0, exportWidth, exportHeight, null)
                        } else {
                            g2d.drawImage(img, 0, 0, targetW, targetH, null)
                        }
                        g2d.dispose()
                        onFrameRendered(copy)
                        profiler.addLivePreview(System.nanoTime() - previewStartNs)
                    }
                    profiler.addFrame()
                }
            } catch (e: Exception) {
                println("\n❌ Pipe Write Error: ${e.message}")
            } finally {
                isEncodingActive.set(false)
                try {
                    pipeWriterThread.join(5000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                profiler.addFfmpegActive(System.nanoTime() - ffmpegActiveStartNs)
                
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
                    val isNearEnd = (finalOutputDuration - lastProcessedSec) <= 3.0
                    if (isNearEnd && !cancelSupplier()) {
                        println("ℹ️ FFmpeg exited with code $exitCode near the end of video (${lastProcessedSec.toInt()}/${finalOutputDuration.toInt()} s). Treating as success (EOF reached).")
                        resumeSeconds = finalOutputDuration.toInt()
                    } else {
                        throw Exception("ffmpeg exited with error code $exitCode. See ffmpeg_log.txt for details.")
                    }
                } else {
                    // Success!
                    resumeSeconds = finalOutputDuration.toInt()
                }
            }
        }

        if (!cancelSupplier() && resumeSeconds >= finalOutputDuration.toInt()) {
            // Export trimmed FIT file
            if (hasTelemetry && parser != null) {
                try {
                    val fitStartUtcSeconds = (startTime.toEpochMilli() / 1000.0 + actualTrimStart).toLong()
                    val fitEndUtcSeconds = (startTime.toEpochMilli() / 1000.0 + actualTrimEnd).toLong()
                    val userOffsetSec = groundTruthMetadata?.timeOffsetMillis?.div(1000.0f)
                    val imuOffsetSec = groundTruthMetadata?.imuTimeOffsetMillis?.div(1000.0f) ?: 0.0f
                    val videoFileName = File(videoPath).name
                    val trimmedFitBytes = parser.trim(
                        videoStartUtcSeconds = fitStartUtcSeconds,
                        videoEndUtcSeconds = fitEndUtcSeconds,
                        userOffset = userOffsetSec,
                        imuOffset = imuOffsetSec,
                        videoName = videoFileName,
                        cancelCheck = cancelSupplier
                    )
                    val trimmedFitFile = File(output.replace(Regex("""\.(mp4|mov)$""", RegexOption.IGNORE_CASE), ".fit"))
                    trimmedFitFile.writeBytes(trimmedFitBytes)
                    println("⚡ Trimmed FIT file exported to: ${trimmedFitFile.absolutePath}")
                } catch (e: Exception) {
                    println("⚠️ Failed to export trimmed FIT file: ${e.message}")
                    e.printStackTrace()
                }
            }

            if (skipConcat) {
                println("DEBUG: skipConcat is true. Skipping segment merging inside NativeHudEncoder.encode. Temporary segments preserved in: ${jobDir.absolutePath}")
                onProgress(1.0f, "Encoding complete. Temporary segments preserved.")
                return
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
            
            val metadataArgs = groundTruthMetadata?.toFfmpegMetadataArgs()
                ?: listOf("-metadata", "comment=fit-trimmer-hud-burned")

            val concatArgs = listOf(
                ffmpegPath, "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", partsListFile.absolutePath,
                "-c", "copy",
            ) + metadataArgs + finalDest.absolutePath
            
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
            try {
                parts.forEach { it.delete() }
                partsListFile.delete()
                jobDir.deleteRecursively()
            } catch (e: Exception) {
                println("⚠️ Warning: Failed to clean up job directory: ${e.message}")
            }
            
            onProgress(1.0f, "✨ Finished Successfully!")
        }
        } finally {
            val report = profiler.report()
            println(report.toMetricLine())
            try {
                report.appendToHistory("encode")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                profileSink?.invoke(report)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    fun generateMaskVideoOnly(
        videoPath: String,
        jobDir: File,
        jobHash: String,
        plateCache: VideoPlatesCache,
        targetDurationSeconds: Double,
        videoRotation: Int,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: String,
        exportWidth: Int,
        exportHeight: Int,
        trimStartSeconds: Double = 0.0
    ) {
        try { jobDir.mkdirs(); File(jobDir, ".video_source").writeText(videoPath) } catch(e: Exception) { e.printStackTrace() }
        val runBlur = settings.blurLicensePlates && plateCache.records.isNotEmpty()
        if (!runBlur) return

        val fpsDouble = videoFps.toDoubleOrNull() ?: 30.0
        val totalFrames = (targetDurationSeconds * fpsDouble).toInt()

        val is90Or270 = videoRotation == 90 || videoRotation == -270 || videoRotation == 270 || videoRotation == -90
        val fallbackSourceW = if (is90Or270) videoHeight else videoWidth
        val fallbackSourceH = if (is90Or270) videoWidth else videoHeight
        
        val maskFramePlan = plateCache.buildMappedMaskFrames(
            totalFrames = totalFrames,
            fps = fpsDouble,
            isBlurEnabled = settings.blurLicensePlates,
            expandRatio = settings.plateMaskExpandRatio,
            fallbackSourceWidth = fallbackSourceW,
            fallbackSourceHeight = fallbackSourceH,
            targetWidth = exportWidth.toFloat(),
            targetHeight = exportHeight.toFloat(),
            timeBufferMs = settings.plateMaskTimeBufferMs,
            sourceStartTimeMs = (trimStartSeconds * 1000.0).toLong()
        )

        val maskVideoFile = File(jobDir, "plate_mask.mkv")
        if (maskVideoFile.exists()) {
            maskVideoFile.delete()
        }

        generateMaskVideo(
            ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" },
            outputFile = maskVideoFile,
            maskFramePlan = maskFramePlan,
            width = exportWidth,
            height = exportHeight,
            fps = videoFps
        )

        val jobState = JobStateManager.loadState(jobDir, jobHash)
        JobStateManager.saveState(jobDir, jobState.copy(videoPath = videoPath, isPlateMaskStreamReady = true))
    }

    private data class TimelineInterval(
        val start: Double,
        val end: Double,
        val isSpeed: Boolean,
        val speedFactor: Double
    )

    private fun generateSetptsExpression(activeSegments: List<SpeedSegment>): String {
        if (activeSegments.isEmpty()) {
            return "PTS-STARTPTS"
        }
        
        fun buildExpr(index: Int, currentSource: Double, currentTarget: Double): String {
            if (index >= activeSegments.size) {
                return "($currentTarget + (T - $currentSource))"
            }
            val seg = activeSegments[index]
            val s = seg.startSeconds
            val e = seg.endSeconds
            val f = seg.speedFactor
            
            val sTarget = currentTarget + (s - currentSource)
            val eTarget = sTarget + (e - s) / f
            
            val normalPart = "($currentTarget + (T - $currentSource))"
            val speedPart = "($sTarget + (T - $s) / $f)"
            
            return "if(lt(T, $s), $normalPart, if(lt(T, $e), $speedPart, ${buildExpr(index + 1, e, eTarget)}))"
        }
        
        val tOutExpr = buildExpr(0, 0.0, 0.0)
        return "($tOutExpr)/TB"
    }

    private fun getAudioSpeedFilter(factor: Double): String {
        val filters = mutableListOf<String>()
        var remaining = factor
        while (remaining > 2.0) {
            filters.add("atempo=2.0")
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            filters.add("atempo=0.5")
            remaining /= 0.5
        }
        if (Math.abs(remaining - 1.0) > 1e-4) {
            filters.add("atempo=$remaining")
        }
        return filters.joinToString(",")
    }

    private fun generateAudioFilterString(intervals: List<TimelineInterval>): String {
        val sb = StringBuilder()
        for (i in intervals.indices) {
            val int = intervals[i]
            val speedFilter = if (int.isSpeed) {
                val audioSpeed = getAudioSpeedFilter(int.speedFactor)
                if (audioSpeed.isNotEmpty()) ",${audioSpeed},volume=0" else ",volume=0"
            } else ""
            sb.append("[1:a]atrim=start=${int.start}:end=${int.end},asetpts=PTS-STARTPTS${speedFilter}[aud$i];")
        }
        for (i in intervals.indices) {
            sb.append("[aud$i]")
        }
        sb.append("concat=n=${intervals.size}:v=0:a=1[outa]")
        return sb.toString()
    }

    companion object : HudEncoderFactory {
        override fun create(
            settings: HudSettings,
            onProgress: (Float, String) -> Unit,
            onFrameRendered: (Any) -> Unit,
            pauseSupplier: () -> Boolean,
            cancelSupplier: () -> Boolean,
            customRenderer: ((HudCanvas, TelemetryPoint, List<TelemetryPoint>, List<TelemetryPoint>, List<Double>, Float) -> Unit)?,
            showLivePreviewSupplier: () -> Boolean,
            profileSink: ((EncodeProfileReport) -> Unit)?
        ): HudEncoder {
            return NativeHudEncoder(
                settings = settings,
                onProgress = onProgress,
                onFrameRendered = { img -> if (img is java.awt.image.BufferedImage) onFrameRendered(img) },
                pauseSupplier = pauseSupplier,
                cancelSupplier = cancelSupplier,
                customRenderer = customRenderer,
                showLivePreviewSupplier = showLivePreviewSupplier,
                profileSink = profileSink
            )
        }
        fun calculateJobHash(
            fitPath: String,
            videoPath: String,
            startUtc: String,
            maxDurationSeconds: Int,
            actualTrimStart: Double,
            actualTrimEnd: Double,
            videoWidth: Int,
            videoHeight: Int,
            exportWidth: Int,
            exportHeight: Int,
            config: HudConfig,
            settings: HudSettings,
            plateCache: fit.VideoPlatesCache?
        ): String {
            return kotlin.math.abs((
                fitPath + videoPath + startUtc + maxDurationSeconds + actualTrimStart + actualTrimEnd +
                    videoWidth + videoHeight + exportWidth + exportHeight + config.hashCode() +
                    settings.exportResolution + settings.blurLicensePlates + settings.plateMaskExpandRatio.toString() +
                    (plateCache?.sourceWidth ?: 0) + (plateCache?.sourceHeight ?: 0) +
                    (plateCache?.records?.hashCode() ?: 0)
            ).hashCode()).toString()
        }

        fun hasResumeCache(
            fitPath: String,
            videoPath: String,
            startUtc: String,
            maxDurationSeconds: Int,
            trimStartSeconds: Double,
            trimEndSeconds: Double,
            settings: HudSettings,
            plateCache: fit.VideoPlatesCache? = null
        ): Boolean {
            try {
                val ffmpegPath = findFfmpegPath()
                val workDir = PathResolver.getTempWorkDir(videoPath)
                
                // Parse video width/height/duration
                var videoWidth = 1920
                var videoHeight = 1080
                var videoDurationSeconds = 300
                try {
                    val pb = ProcessBuilder(ffmpegPath, "-i", videoPath)
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val outputInfo = p.inputStream.bufferedReader().readText()
                    p.waitFor()
                    
                    val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
                    val durMatch = durRegex.find(outputInfo)
                    if (durMatch != null) {
                        val h = durMatch.groupValues[1].toInt()
                        val m = durMatch.groupValues[2].toInt()
                        val s = durMatch.groupValues[3].toInt()
                        videoDurationSeconds = h * 3600 + m * 60 + s
                    }
                    
                    val lines = outputInfo.lines()
                    val videoLine = lines.find { it.contains("Video:") }
                    if (videoLine != null) {
                        val resRegex = Regex("""\b(\d{3,4})x(\d{3,4})\b""")
                        val resMatch = resRegex.find(videoLine)
                        if (resMatch != null) {
                            videoWidth = resMatch.groupValues[1].toInt()
                            videoHeight = resMatch.groupValues[2].toInt()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

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

                val startTime = try { Instant.parse(startUtc) } catch(e: Exception) { Instant.EPOCH }
                val fitEpoch = Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val startUtcSeconds = startTime.toEpochMilli() / 1000.0
                val videoStartFit = startUtcSeconds + actualTrimStart - fitEpoch
                val videoEndFit = startUtcSeconds + actualTrimEnd - fitEpoch
                
                // read telemetry
                val hasTelemetry = fitPath.isNotEmpty() && File(fitPath).exists()
                var telemetry = if (hasTelemetry) {
                    try {
                        val fitBytes = File(fitPath).readBytes()
                        val p = FitParser(fitBytes)
                        p.parse()
                        p.getTelemetry()
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else emptyList()
                val trimmedTelemetryRaw = telemetry.filter { it.timestamp in videoStartFit..videoEndFit }
                val trimmedTelemetry = if (trimmedTelemetryRaw.isNotEmpty()) trimmedTelemetryRaw else telemetry

                val config = HudConfig(
                    valSize = settings.valSize, tightness = settings.tightness, spacing = settings.spacing,
                    xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW,
                    captionPosition = settings.captionPosition,
                    roadCaptions = settings.roadCaptions,
                    powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
                    useImperialUnits = settings.useImperialUnits,
                    language = settings.language,
                    elevationGraphScope = settings.elevationGraphScope,
                    heartRateAccumulationScope = settings.heartRateAccumulationScope,
                    showSpeed = settings.showSpeed,
                    showCadence = settings.showCadence,
                    showHeartRate = settings.showHeartRate,
                    showPower = settings.showPower,
                    showWkg = settings.showWkg,
                    showPowerTrend = settings.showPowerTrend,
                    showGrade = settings.showGrade,
                    showElevation = settings.showElevation,
                    showDistanceTime = settings.showDistanceTime,
                    bodyWeightKg = settings.bodyWeightKg,
                    customCaptions = settings.customCaptions,
                    mapSizeScale = settings.mapSizeScale,
                    mapType = settings.mapType,
                    mapPosition = settings.mapPosition,
                    hudBgAlpha = settings.hudBgAlpha,
                    mapZoomScale = settings.mapZoomScale,
                    mapZoomOffset = settings.mapZoomOffset,
                    fixMapNorthUp = settings.fixMapNorthUp,
                    mapMarkerSizeScale = settings.mapMarkerSizeScale,
                    mapTextSizeScale = settings.mapTextSizeScale,
                    trimStartSeconds = actualTrimStart,
                    mapRangeMode = settings.mapRangeMode,
                    textShadowAlpha = settings.textShadowAlpha
                )

                val (exportWidth, exportHeight) = when (settings.exportResolution) {
                    "4K" -> 3840 to 2160
                    "1080p" -> 1920 to 1080
                    "strava" -> 1280 to 720
                    "720p" -> 1280 to 720
                    else -> videoWidth to videoHeight
                }

                val jobHash = calculateJobHash(
                    fitPath = fitPath,
                    videoPath = videoPath,
                    startUtc = startUtc,
                    maxDurationSeconds = maxDurationSeconds,
                    actualTrimStart = actualTrimStart,
                    actualTrimEnd = actualTrimEnd,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    config = config,
                    settings = settings,
                    plateCache = plateCache
                )
                val jobDir = File(workDir, "job_" + jobHash)
                if (jobDir.exists()) {
                    val existingParts = jobDir.listFiles { _, name -> name.matches(Regex("part_\\d{4}\\.ts")) }
                    return existingParts != null && existingParts.isNotEmpty()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }
    }
}

