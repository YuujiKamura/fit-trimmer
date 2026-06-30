package components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import fit.*
import utils.*

class ComposeHudCanvas(
    private val drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    private val textMeasurer: TextMeasurer,
    private val scale: Float,
    private val density: Float
) : fit.HudCanvas {
    override val width: Float get() = drawScope.size.width / scale
    override val height: Float get() = drawScope.size.height / scale

    private fun parseColor(colorStr: String): Color {
        val clean = colorStr.replace("#", "")
        return try {
            if (clean.length == 8) {
                Color(clean.toLong(16))
            } else if (clean.length == 6) {
                Color((0xFF000000 or clean.toLong(16)))
            } else {
                Color.White
            }
        } catch (e: Exception) {
            Color.White
        }
    }

    override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
        val c = parseColor(color)
        val style = TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            color = c,
            fontSize = (size * scale / density).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        val layout = textMeasurer.measure(text, style)
        val drawX = x * scale - when (anchor) {
            "center" -> layout.size.width.toFloat() / 2f
            "top-right", "bottom-right" -> layout.size.width.toFloat()
            else -> 0f
        }
        val drawY = y * scale - when (anchor) {
            "center" -> layout.size.height.toFloat() / 2f
            "bottom-left", "bottom-right" -> layout.size.height.toFloat()
            else -> 0f
        }
        drawScope.drawText(layout, topLeft = Offset(drawX, drawY))
    }

    override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {
        val c = parseColor(color)
        drawScope.drawRect(
            color = c,
            topLeft = Offset(x * scale, y * scale),
            size = Size(w * scale, h * scale),
            alpha = alpha,
            style = if (outline) Stroke(width = 1f * scale) else androidx.compose.ui.graphics.drawscope.Fill
        )
    }

    override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {
        if (points.size < 2) return
        val c = parseColor(color)
        for (i in 0 until points.size - 1) {
            drawScope.drawLine(
                color = c,
                start = Offset(points[i].first * scale, points[i].second * scale),
                end = Offset(points[i + 1].first * scale, points[i + 1].second * scale),
                strokeWidth = width * scale,
                alpha = alpha
            )
        }
    }

    override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {
        if (points.size < 3) return
        val c = parseColor(color)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points[0].first * scale, points[0].second * scale)
            for (i in 1 until points.size) {
                lineTo(points[i].first * scale, points[i].second * scale)
            }
            close()
        }
        drawScope.drawPath(path = path, color = c, alpha = alpha)
    }

    override fun getTextWidth(text: String, size: Float, bold: Boolean): Float {
        val style = TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            fontSize = (size / density).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        val layout = textMeasurer.measure(text, style)
        return layout.size.width.toFloat()
    }
}

@Composable
fun VideoPreviewArea(
    videoPath: String,
    videoLengthMs: Long,
    adjustedStartUtc: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    trimmedTelemetryPoints: List<FitParser.TelemetryPoint>,
    settings: HudSettings,
    rendererProxy: fit.DynamicRendererProxy,
    textMeasurer: TextMeasurer,
    playerState: VideoPlayerState,
    videoCurrentTimeMs: Long,
    onCurrentTimeChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isEncoding: Boolean = false,
    previewQualityMode: String = "original",
    onPreviewQualityModeChange: (String) -> Unit = {},
    isFullscreen: Boolean = false,
    onFullscreenToggle: (() -> Unit)? = null,
    plateCache: utils.VideoPlatesCache? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var lastVolume by remember { mutableStateOf(1f) }
    var currentRenderTimeMs by remember { mutableStateOf(videoCurrentTimeMs) }
    var activePreviewSourceLabel by remember { mutableStateOf("Original") }

    LaunchedEffect(videoCurrentTimeMs) {
        currentRenderTimeMs = videoCurrentTimeMs
    }



    val togglePlay = {
        println("DEBUG: togglePlay called. current isPlaying=${playerState.isPlaying}")
        if (playerState.isPlaying) {
            println("DEBUG: togglePlay calling playerState.pause()")
            playerState.pause()
        } else {
            if (videoCurrentTimeMs >= videoLengthMs) {
                playerState.seekTo(0f)
            }
            println("DEBUG: togglePlay calling playerState.play()")
            playerState.play()
        }
    }

    val seekTo = { timeMs: Long ->
        val target = timeMs.coerceIn(0L, videoLengthMs)
        val ratio = if (videoLengthMs > 0) target.toFloat() / videoLengthMs.toFloat() else 0f
        playerState.seekTo(ratio * 1000f)
    }

    LaunchedEffect(playerState.isPlaying) {
        isPlaying = playerState.isPlaying
        println("DEBUG: PlayerState isPlaying state changed: isPlaying=${playerState.isPlaying}")
    }

    // High-performance 60fps frame interpolation loop.
    // By calculating nanoTime differences, we update videoCurrentTimeMs continuously at 60fps
    // and sync with the player's true position periodically to avoid audio/video drifts.
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) {
            var startSystemNanos = System.nanoTime()
            val getPlayerCurrentTimeMs = {
                val sliderPos = playerState.sliderPos
                val durationMs = playerState.metadata.duration ?: 0L
                fit.TimelineMapper.calculateCurrentTimeMs(sliderPos, durationMs, videoLengthMs)
            }
            var startPlayerTimeMs = getPlayerCurrentTimeMs()
            var lastSyncNanos = System.nanoTime()

            while (true) {
                withFrameNanos { frameNanos ->
                    val nowNanos = System.nanoTime()
                    val elapsedMs = (nowNanos - startSystemNanos) / 1_000_000.0
                    val interpolatedTimeMs = (startPlayerTimeMs + elapsedMs).toLong()

                    // Periodic alignment: Sync with real player playhead if drift exceeds 300ms
                    if (nowNanos - lastSyncNanos > 100_000_000L) { // 100ms
                        val playerTimeMs = getPlayerCurrentTimeMs()
                        val diff = kotlin.math.abs(interpolatedTimeMs - playerTimeMs)
                        if (diff > 300) {
                            startSystemNanos = nowNanos
                            startPlayerTimeMs = playerTimeMs
                        }
                        lastSyncNanos = nowNanos
                    }
                    val finalTime = interpolatedTimeMs.coerceIn(0L, videoLengthMs)
                    currentRenderTimeMs = finalTime
                    onCurrentTimeChange(finalTime)
                }
            }
        }
    }

    LaunchedEffect(playerState, videoLengthMs) {
        snapshotFlow { playerState.sliderPos to (playerState.metadata.duration ?: 0L) }
            .collect { (sliderPos, durationMs) ->
                val elapsedMs = fit.TimelineMapper.calculateCurrentTimeMs(
                    sliderPos = sliderPos,
                    durationMs = durationMs,
                    fullVideoLengthMs = videoLengthMs
                )
                onCurrentTimeChange(elapsedMs)
            }
    }

    fun findCompanionLrvFile(originalFile: File): File? {
        val parentDir = originalFile.parentFile ?: return null
        val originalName = originalFile.name
        val lrvName = when {
            originalName.startsWith("VID_", ignoreCase = true) ->
                originalName.replace("VID_", "LRV_", ignoreCase = true).replace(".mp4", ".lrv", ignoreCase = true)
            originalName.startsWith("PRO_VID_", ignoreCase = true) ->
                originalName.replace("PRO_VID_", "PRO_LRV_", ignoreCase = true).replace(".mp4", ".lrv", ignoreCase = true)
            else -> return null
        }
        return File(parentDir, lrvName).takeIf { it.exists() }
    }

    fun isCloudOrExternalPath(path: String): Boolean {
        val normalized = path.replace("\\", "/").lowercase()
        return !normalized.startsWith("c:/") ||
            normalized.contains("google drive") ||
            normalized.contains("マイドライブ") ||
            normalized.contains("my drive")
    }

    suspend fun materializeLrvProxy(lrvFile: File): String? = withContext(Dispatchers.IO) {
        try {
            val tempDir = System.getProperty("java.io.tmpdir")
            try {
                File(tempDir).listFiles { _, name ->
                    name.startsWith("fit_trimmer_lrv_proxy_") && name.endsWith(".mp4")
                }?.forEach { it.delete() }
            } catch (e: Exception) {}

            val proxyFile = File(tempDir, "fit_trimmer_lrv_proxy_${java.util.UUID.randomUUID()}.mp4")
            val ffmpegPath = fit.findFfmpegPath()
            val pb = ProcessBuilder(
                ffmpegPath, "-y",
                "-i", lrvFile.absolutePath,
                "-c", "copy",
                proxyFile.absolutePath
            )
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val p = pb.start()
            val exited = p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
            if (exited && p.exitValue() == 0 && proxyFile.exists()) {
                proxyFile.absolutePath
            } else {
                println("WARN: LRV proxy extraction failed or timed out (exitCode=${if (exited) p.exitValue() else "timeout"}).")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Video path or preview quality change side effect inside the player area
    LaunchedEffect(videoPath, previewQualityMode) {
        println("DEBUG: VideoPreviewArea LaunchedEffect(videoPath) triggered with path: $videoPath")
        if (videoPath.isNotEmpty() && File(videoPath).exists()) {
            val savedTimeMs = videoCurrentTimeMs
            val originalFile = File(videoPath)
            val lrvFile = findCompanionLrvFile(originalFile)
            val mode = previewQualityMode.lowercase()
            val shouldUseProxy = when (mode) {
                "proxy" -> lrvFile != null
                "auto" -> lrvFile != null && (isCloudOrExternalPath(originalFile.absolutePath) || isHevcOrHighRes(originalFile.absolutePath))
                else -> false
            }
            var targetVideoPath = if (shouldUseProxy && lrvFile != null) {
                val proxyPath = materializeLrvProxy(lrvFile)
                if (proxyPath != null) {
                    activePreviewSourceLabel = if (mode == "auto") "Auto: Proxy" else "Proxy"
                    proxyPath
                } else {
                    activePreviewSourceLabel = "Original"
                    originalFile.absolutePath
                }
            } else {
                activePreviewSourceLabel = if (mode == "auto") "Auto: Original" else "Original"
                originalFile.absolutePath
            }

            if (!targetVideoPath.startsWith("C:\\", ignoreCase = true) && targetVideoPath.contains(":\\")) {
                try {
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val junctionFolder = File(tempDir, "fit_trimmer_video_junction")
                    if (junctionFolder.exists()) {
                        ProcessBuilder("cmd.exe", "/c", "rmdir", junctionFolder.absolutePath).start().waitFor()
                    }
                    val activeParentPath = File(targetVideoPath).parentFile.absolutePath
                    val pb = ProcessBuilder("cmd.exe", "/c", "mklink", "/j", junctionFolder.absolutePath, activeParentPath)
                    val p = pb.start()
                    p.waitFor()
                    if (junctionFolder.exists()) {
                        targetVideoPath = File(junctionFolder, File(targetVideoPath).name).absolutePath
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            println("DEBUG: VideoPreviewArea calling openUri: $targetVideoPath")
            playerState.openUri(targetVideoPath)

            // Restore position after media is loaded
            if (savedTimeMs > 0L) {
                launch {
                    val startTime = System.currentTimeMillis()
                    // Wait for loading to start
                    while (!playerState.isLoading && System.currentTimeMillis() - startTime < 500) {
                        delay(10)
                    }
                    // Wait for loading to complete and hasMedia to be true
                    val loadStartTime = System.currentTimeMillis()
                    while ((playerState.isLoading || !playerState.hasMedia) && System.currentTimeMillis() - loadStartTime < 5000) {
                        delay(50)
                    }
                    if (playerState.hasMedia) {
                        val durationMs = (playerState.metadata.duration ?: 0.0).toLong()
                        val isProxy = targetVideoPath.contains("fit_trimmer_lrv_proxy_")
                        if (isProxy && !fit.TimelineMapper.isProxyDurationValid(durationMs, videoLengthMs)) {
                            println("WARN: Loaded proxy duration ($durationMs ms) is unsafe relative to full video ($videoLengthMs ms). Reloading original file.")
                            activePreviewSourceLabel = "Original"
                            playerState.openUri(videoPath)
                            return@launch
                        }
                        println("DEBUG: Restoring playhead position to $savedTimeMs ms")
                        seekTo(savedTimeMs)
                    }
                }
            }
        } else {
            playerState.stop()
        }
    }

    val currentPointAndIndex = remember(currentRenderTimeMs, telemetryPoints, adjustedStartUtc) {
        if (telemetryPoints.isEmpty() || adjustedStartUtc.isEmpty()) null
        else {
            try {
                val startTime = java.time.Instant.parse(adjustedStartUtc)
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val elapsedSeconds = currentRenderTimeMs / 1000.0
                val currentUtc = startTime.toEpochMilli() / 1000.0 + elapsedSeconds
                val currentFitTs = currentUtc - fitEpoch
                
                var idx = telemetryPoints.binarySearch { it.timestamp.compareTo(currentFitTs) }
                val (lerpedPoint, targetIdx) = if (idx >= 0) {
                    Pair(telemetryPoints[idx], idx)
                } else {
                    val insertIdx = -idx - 1
                    if (insertIdx <= 0) {
                        Pair(telemetryPoints.first(), 0)
                    } else if (insertIdx >= telemetryPoints.size) {
                        Pair(telemetryPoints.last(), telemetryPoints.size - 1)
                    } else {
                        val p0 = telemetryPoints[insertIdx - 1]
                        val p1 = telemetryPoints[insertIdx]
                        val t0 = p0.timestamp
                        val t1 = p1.timestamp
                        val alpha = if (t1 - t0 > 0) (currentFitTs - t0) / (t1 - t0) else 0.0
                        val lerp = { a: Double, b: Double -> a + (b - a) * alpha }

                        val p = fit.FitParser.TelemetryPoint(
                            timestamp = currentFitTs,
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
                        Pair(p, insertIdx - 1)
                    }
                }
                Pair(lerpedPoint, targetIdx)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    val currentPoint = currentPointAndIndex?.first
    val currentPointIdx = currentPointAndIndex?.second

    val currentTrendPoints = remember(currentPoint, currentPointIdx, telemetryPoints, settings.powerTrendSpanSeconds) {
        if (currentPoint == null || telemetryPoints.isEmpty() || currentPointIdx == null) emptyList<Double>()
        else {
            val idx = currentPointIdx
            val startIdx = maxOf(0, idx - settings.powerTrendSpanSeconds)
            telemetryPoints.subList(startIdx, idx + 1).map { it.power }
        }
    }

    @Composable
    fun PlayerControls(floating: Boolean) {
        val foreground = if (floating) Color.White else Color(0xFF1C1C1E)
        val mutedForeground = if (floating) Color(0xFFE5E5EA) else Color(0xFF1C1C1E)
        val inactiveTrack = if (floating) Color.White.copy(alpha = 0.25f) else Color(0xFFE5E5EA)
        val horizontalPadding = if (floating) 10.dp else 8.dp
        val controlButtonBg = if (floating) Color.White.copy(alpha = 0.10f) else Color.Transparent

        @Composable
        fun IconControlButton(
            label: String,
            onClick: () -> Unit,
            modifier: Modifier = Modifier,
            enabled: Boolean = true
        ) {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.size(30.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = mutedForeground,
                    backgroundColor = controlButtonBg
                )
            ) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = togglePlay,
                    enabled = !isEncoding,
                    modifier = Modifier.size(34.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF))
                ) {
                    Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Text(
                    text = "${formatTime(videoCurrentTimeMs)} / ${formatTime(videoLengthMs)}",
                    color = foreground,
                    fontSize = 11.sp,
                    maxLines = 1
                )

                Slider(
                    value = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f,
                    onValueChange = { ratio ->
                        val targetTime = (ratio * videoLengthMs).toLong()
                        seekTo(targetTime)
                    },
                    enabled = !isEncoding,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF007AFF),
                        activeTrackColor = Color(0xFF007AFF),
                        inactiveTrackColor = inactiveTrack
                    )
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val vol = playerState.volume
                    val isMute = vol == 0f
                    IconControlButton(
                        label = if (isMute) "🔇" else "🔊",
                        onClick = {
                            if (isMute) {
                                playerState.volume = if (lastVolume > 0f) lastVolume else 0.5f
                            } else {
                                lastVolume = vol
                                playerState.volume = 0f
                            }
                        }
                    )

                    Slider(
                        value = vol,
                        onValueChange = { newVol ->
                            playerState.volume = newVol
                            if (newVol > 0f) {
                                lastVolume = newVol
                            }
                        },
                        modifier = Modifier.width(60.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF007AFF),
                            activeTrackColor = Color(0xFF007AFF),
                            inactiveTrackColor = inactiveTrack
                        )
                    )
                }

                if (onFullscreenToggle != null) {
                    IconControlButton(
                        label = if (isFullscreen) "×" else "⛶",
                        onClick = onFullscreenToggle,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activePreviewSourceLabel,
                    color = foreground,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.width(if (floating) 90.dp else 82.dp)
                )
                val modes = listOf(
                    "original" to "Original",
                    "auto" to "Auto",
                    "proxy" to "Proxy"
                )
                for ((mode, label) in modes) {
                    val selected = previewQualityMode.equals(mode, ignoreCase = true)
                    Button(
                        onClick = { onPreviewQualityModeChange(mode) },
                        enabled = !isEncoding,
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (selected) Color(0xFF007AFF) else if (floating) Color.White.copy(alpha = 0.12f) else Color(0xFFE5E5EA),
                            contentColor = if (selected) Color.White else mutedForeground
                        )
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                Spacer(Modifier.width(4.dp))
                val seekBtnModifier = Modifier.weight(1f).height(24.dp)
                val seekBtnColors = ButtonDefaults.outlinedButtonColors(
                    contentColor = mutedForeground,
                    backgroundColor = controlButtonBg
                )
                val seekSpecs = listOf(
                    "-30" to -30000L,
                    "-10" to -10000L,
                    "-5" to -5000L,
                    "-1" to -1000L,
                    "+1" to 1000L,
                    "+5" to 5000L,
                    "+10" to 10000L,
                    "+30" to 30000L
                )
                for ((label, delta) in seekSpecs) {
                    OutlinedButton(
                        onClick = { seekTo((videoCurrentTimeMs + delta).coerceIn(0L, maxOf(0L, videoLengthMs))) },
                        modifier = seekBtnModifier,
                        colors = seekBtnColors,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(label, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    fun VideoLayer(modifier: Modifier) {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            val videoAspect = (playerState as? WindowsVideoPlayerState)?.videoAspectRatio?.takeIf { it > 0f } ?: 16f / 9f
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val containerWidth = maxWidth
                val containerHeight = maxHeight
                val frameModifier = if (containerWidth > 0.dp && containerHeight > 0.dp) {
                    val constrainedAspect = containerWidth.value / containerHeight.value
                    if (constrainedAspect > videoAspect) {
                        Modifier.height(containerHeight).width(containerHeight * videoAspect)
                    } else {
                        Modifier.width(containerWidth).height(containerWidth / videoAspect)
                    }
                } else {
                    Modifier.fillMaxSize()
                }

                Box(modifier = frameModifier) {
                    if (videoPath.isNotEmpty()) {
                        VideoPlayerSurface(
                            playerState = playerState,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }

                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                if (!isEncoding) {
                                    togglePlay()
                                }
                            }
                    ) {
                        val scale = size.width / 1920f
                        val currentSeconds = currentRenderTimeMs.toFloat() / 1000f

                        // License plate mask overlay
                        if (settings.blurLicensePlates && plateCache != null) {
                            val wState = playerState as? WindowsVideoPlayerState
                            val videoW = wState?.videoWidth?.takeIf { it > 0 } ?: 1920
                            val videoH = wState?.videoHeight?.takeIf { it > 0 } ?: 1080
                            
                            val scaleX = size.width / videoW.toFloat()
                            val scaleY = size.height / videoH.toFloat()
                            
                            val timeMs = currentRenderTimeMs
                            val record = plateCache.findClosestRecord(timeMs)
                            
                            if (record != null && kotlin.math.abs(record.timeMs - timeMs) < 150) {
                                for (box in record.boxes) {
                                    val x1 = box.x1 * scaleX
                                    val y1 = box.y1 * scaleY
                                    val x2 = box.x2 * scaleX
                                    val y2 = box.y2 * scaleY
                                    
                                    val w = x2 - x1
                                    val h = y2 - y1
                                    
                                    if (w > 0 && h > 0) {
                                        // Draw a solid dark-gray privacy block
                                        drawRect(
                                            color = Color(0xFF1C1C1E),
                                            topLeft = Offset(x1, y1),
                                            size = Size(w, h)
                                        )
                                        // Draw border
                                        drawRect(
                                            color = Color(0xFFE5E5EA),
                                            topLeft = Offset(x1, y1),
                                            size = Size(w, h),
                                            style = Stroke(width = 1f)
                                        )
                                    }
                                }
                            }
                        }

                        val telemetryPoint = currentPoint ?: fit.FitParser.TelemetryPoint(
                            timestamp = 0.0, speed = 26.2, power = 175.0, cadence = 79.0, heartRate = 148.0, elevation = 63.2, grade = 4.0
                        )
                        val pBuf = currentTrendPoints.map { it }
                        val composeCanvas = ComposeHudCanvas(this, textMeasurer, scale, density)
                        rendererProxy.renderFrame(
                            composeCanvas,
                            telemetryPoint,
                            telemetryPoints,
                            pBuf,
                            currentSeconds
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PreviewSurface(modifier: Modifier) {
        val shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp)
        val frameModifier = modifier
            .background(Color.Black, shape = shape)
            .then(
                if (isFullscreen) Modifier
                else Modifier.border(1.dp, Color(0xFFE5E5EA), shape = shape)
            )
            .clip(shape)

        if (isFullscreen) {
            Box(modifier = frameModifier) {
                VideoLayer(Modifier.fillMaxSize())
                if (videoPath.isNotEmpty() && File(videoPath).exists()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(vertical = 8.dp)
                    ) {
                        PlayerControls(floating = true)
                    }
                }
            }
        } else {
            Column(modifier = frameModifier) {
                VideoLayer(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                if (videoPath.isNotEmpty() && File(videoPath).exists()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.92f))
                            .padding(vertical = 6.dp)
                    ) {
                        PlayerControls(floating = true)
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            PreviewSurface(Modifier.fillMaxSize())
        }
    } else {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            PreviewSurface(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}
