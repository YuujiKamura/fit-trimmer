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
    isGeneratingProxy: Boolean,
    proxyProgress: Float,
    proxyVideoPath: String?,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var lastVolume by remember { mutableStateOf(1f) }

    var activePath by remember(videoPath, proxyVideoPath) { mutableStateOf("") }

    LaunchedEffect(videoPath, proxyVideoPath) {
        if (videoPath.isEmpty() || !File(videoPath).exists()) {
            activePath = ""
            return@LaunchedEffect
        }
        val isProxyNeeded = withContext(Dispatchers.IO) {
            isHevcOrHighRes(videoPath)
        }
        activePath = if (isProxyNeeded) {
            if (proxyVideoPath != null && File(proxyVideoPath).exists()) {
                proxyVideoPath
            } else {
                ""
            }
        } else {
            videoPath
        }
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
        if (isPlaying) {
            playerState.volume = 0f
            println("DEBUG: Enforced volume = 0f on play start")
        }
        println("DEBUG: PlayerState isPlaying state changed: isPlaying=${playerState.isPlaying}")
    }

    LaunchedEffect(playerState.sliderPos, playerState.metadata.duration) {
        val durationMs = playerState.metadata.duration ?: 0L
        val currentDuration = if (videoLengthMs > 0L) videoLengthMs else durationMs
        onCurrentTimeChange(((playerState.sliderPos / 1000f) * currentDuration).toLong())
    }

    // Video path change side effect inside the player area
    LaunchedEffect(activePath) {
        println("DEBUG: VideoPreviewArea LaunchedEffect(activePath) triggered with path: $activePath")
        if (activePath.isNotEmpty() && File(activePath).exists()) {
            val savedTimeMs = videoCurrentTimeMs
            val originalFile = File(activePath)
            var targetVideoPath = activePath
            if (activePath.startsWith("H:\\", ignoreCase = true)) {
                try {
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val junctionFolder = File(tempDir, "fit_trimmer_video_junction")
                    if (junctionFolder.exists()) {
                        ProcessBuilder("cmd.exe", "/c", "rmdir", junctionFolder.absolutePath).start().waitFor()
                    }
                    val parentDir = originalFile.parentFile.absolutePath
                    val pb = ProcessBuilder("cmd.exe", "/c", "mklink", "/j", junctionFolder.absolutePath, parentDir)
                    val p = pb.start()
                    p.waitFor()
                    if (junctionFolder.exists()) {
                        targetVideoPath = File(junctionFolder, originalFile.name).absolutePath
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
                        println("DEBUG: Restoring playhead position to $savedTimeMs ms")
                        seekTo(savedTimeMs)
                    }
                }
            }
        } else {
            playerState.stop()
        }
    }

    val currentPoint = remember(videoCurrentTimeMs, telemetryPoints, adjustedStartUtc) {
        if (telemetryPoints.isEmpty() || adjustedStartUtc.isEmpty()) null
        else {
            try {
                val startTime = java.time.Instant.parse(adjustedStartUtc)
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val elapsedSeconds = videoCurrentTimeMs / 1000.0
                val currentUtc = startTime.toEpochMilli() / 1000.0 + elapsedSeconds
                val currentFitTs = currentUtc - fitEpoch
                telemetryPoints.find { it.timestamp >= currentFitTs } ?: telemetryPoints.lastOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    val currentTrendPoints = remember(currentPoint, telemetryPoints) {
        if (currentPoint == null || telemetryPoints.isEmpty()) emptyList<Double>()
        else {
            val idx = telemetryPoints.indexOfFirst { it.timestamp >= currentPoint.timestamp }
            if (idx == -1) {
                emptyList()
            } else {
                val startIdx = maxOf(0, idx - 30)
                telemetryPoints.subList(startIdx, idx + 1).map { it.power }
            }
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f)
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFE5E5EA), shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (activePath.isNotEmpty()) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }

            val density = androidx.compose.ui.platform.LocalDensity.current.density
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = size.width / 1920f
                val currentRatio = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f
                val telemetryPoint = currentPoint ?: fit.FitParser.TelemetryPoint(
                    timestamp = 0.0, speed = 26.2, power = 175.0, cadence = 79.0, heartRate = 148.0, elevation = 63.2, grade = 4.0
                )
                val pBuf = currentTrendPoints.map { it }
                val composeCanvas = ComposeHudCanvas(this, textMeasurer, scale, density)
                rendererProxy.renderFrame(
                    composeCanvas,
                    telemetryPoint,
                    trimmedTelemetryPoints,
                    pBuf,
                    currentRatio
                )
            }

            if (isGeneratingProxy) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = proxyProgress,
                            modifier = Modifier.size(16.dp),
                            color = Color(0xFF007AFF),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Generating low-res preview proxy... (${(proxyProgress * 100).toInt()}%)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Video Player Control UI
        if (activePath.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = togglePlay,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF))
                ) {
                    Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Text(
                    text = "${formatTime(videoCurrentTimeMs)} / ${formatTime(videoLengthMs)}",
                    color = Color(0xFF1C1C1E),
                    fontSize = 11.sp
                )

                Slider(
                    value = if (videoLengthMs > 0) videoCurrentTimeMs.toFloat() / videoLengthMs.toFloat() else 0f,
                    onValueChange = { ratio ->
                        val targetTime = (ratio * videoLengthMs).toLong()
                        seekTo(targetTime)
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF007AFF),
                        activeTrackColor = Color(0xFF007AFF),
                        inactiveTrackColor = Color(0xFFE5E5EA)
                    )
                )

                // Volume Controls (Mute & Slider)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val vol = playerState.volume
                    val isMute = vol == 0f
                    OutlinedButton(
                        onClick = {
                            if (isMute) {
                                playerState.volume = if (lastVolume > 0f) lastVolume else 0.5f
                            } else {
                                lastVolume = vol
                                playerState.volume = 0f
                            }
                        },
                        modifier = Modifier.size(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                    ) {
                        Text(if (isMute) "🔇" else "🔊", fontSize = 11.sp)
                    }

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
                            inactiveTrackColor = Color(0xFFE5E5EA)
                        )
                    )
                }

                Text(
                    text = if (proxyVideoPath != null) "PROXY" else "NATIVE",
                    color = if (proxyVideoPath != null) Color(0xFF007AFF) else Color(0xFF2E7D32),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val seekBtnModifier = Modifier.weight(1f).height(24.dp)
                val seekBtnColors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                val seekSpecs = listOf(
                    "-30s" to -30000L,
                    "-10s" to -10000L,
                    "-5s" to -5000L,
                    "-1s" to -1000L,
                    "+1s" to 1000L,
                    "+5s" to 5000L,
                    "+10s" to 10000L,
                    "+30s" to 30000L
                )
                for ((label, delta) in seekSpecs) {
                    OutlinedButton(
                        onClick = { seekTo((videoCurrentTimeMs + delta).coerceIn(0L, maxOf(0L, videoLengthMs))) },
                        modifier = seekBtnModifier,
                        colors = seekBtnColors,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(label, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
