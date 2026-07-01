package components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fit.FitParser
import utils.formatTime

data class SampledPoint(
    val seconds: Float,
    val power: Double,
    val speed: Double,
    val elevation: Double,
    val isValid: Boolean
)

enum class DragHandle {
    TRIM_START,
    TRIM_END,
    PLAYHEAD
}

data class GraphLimits(
    val maxPower: Double,
    val maxSpeed: Double,
    val minElev: Double,
    val maxElev: Double
)

@Composable
fun TelemetryTimelineGraph(
    videoLengthMs: Long,
    adjustedStartUtc: String,
    telemetryPoints: List<FitParser.TelemetryPoint>,
    trimStartSeconds: Double,
    trimEndSeconds: Double,
    splitPoints: List<Double>,
    videoCurrentTimeMs: Long,
    onTrimStartChange: (Double) -> Unit,
    onTrimEndChange: (Double) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isEncoding: Boolean = false,
    plateCache: fit.VideoPlatesCache? = null,
    blurLicensePlates: Boolean = false
) {
    val textMeasurer = rememberTextMeasurer()
    val videoDurationSec = videoLengthMs / 1000.0

    val currentVideoLengthMs by rememberUpdatedState(videoLengthMs)
    val currentTrimStartSeconds by rememberUpdatedState(trimStartSeconds)
    val currentTrimEndSeconds by rememberUpdatedState(trimEndSeconds)
    val currentVideoCurrentTimeMs by rememberUpdatedState(videoCurrentTimeMs)
    
    val currentOnTrimStartChange by rememberUpdatedState(onTrimStartChange)
    val currentOnTrimEndChange by rememberUpdatedState(onTrimEndChange)
    val currentOnSeek by rememberUpdatedState(onSeek)

    var lastSeekTime by remember { mutableStateOf(0L) }
    var pendingSeekTimeMs by remember { mutableStateOf<Long?>(null) }

    // Sample telemetry points to match video seconds
    val sampledPoints = remember(telemetryPoints, adjustedStartUtc, videoLengthMs) {
        if (telemetryPoints.isEmpty() || adjustedStartUtc.isEmpty() || videoLengthMs <= 0) {
            println("DEBUG: TelemetryTimelineGraph sampledPoints empty check failed. telemetryPoints.size=${telemetryPoints.size}, adjustedStartUtc='$adjustedStartUtc', videoLengthMs=$videoLengthMs")
            emptyList()
        } else {
            try {
                val startTime = java.time.Instant.parse(adjustedStartUtc)
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val startFitTs = startTime.toEpochMilli() / 1000.0 - fitEpoch
                println("DEBUG: TelemetryTimelineGraph sampledPoints remember triggered. adjustedStartUtc=$adjustedStartUtc, startFitTs=$startFitTs, telemetryPoints.size=${telemetryPoints.size}")
                val numSamples = 400
                List(numSamples) { i ->
                    val sec = (i.toFloat() / (numSamples - 1).toFloat()) * videoDurationSec.toFloat()
                    val fitTs = startFitTs + sec
                    
                    // Binary search for the closest telemetry point
                    var low = 0
                    var high = telemetryPoints.size - 1
                    var bestIdx = 0
                    var minDiff = Double.MAX_VALUE
                    while (low <= high) {
                        val mid = (low + high) / 2
                        val diff = kotlin.math.abs(telemetryPoints[mid].timestamp - fitTs)
                        if (diff < minDiff) {
                            minDiff = diff
                            bestIdx = mid
                        }
                        if (telemetryPoints[mid].timestamp < fitTs) {
                            low = mid + 1
                        } else {
                            high = mid - 1
                        }
                    }
                    
                    val closest = telemetryPoints[bestIdx]
                    val isValid = minDiff < 5.0
                    
                    SampledPoint(
                        seconds = sec,
                        power = if (isValid) closest.power else 0.0,
                        speed = if (isValid) closest.speed else 0.0,
                        elevation = if (isValid) closest.elevation else 0.0,
                        isValid = isValid
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    // Cache metrics limits
    val limits = remember(sampledPoints) {
        if (sampledPoints.isEmpty()) {
            GraphLimits(300.0, 40.0, 0.0, 100.0)
        } else {
            val validPoints = sampledPoints.filter { it.isValid }
            val maxP = validPoints.maxOfOrNull { it.power }?.coerceAtLeast(200.0) ?: 300.0
            val maxS = validPoints.maxOfOrNull { it.speed }?.coerceAtLeast(30.0) ?: 45.0
            val minE = validPoints.minOfOrNull { it.elevation } ?: 0.0
            val maxE = validPoints.maxOfOrNull { it.elevation } ?: 100.0
            val diffE = maxE - minE
            val finalMinE = if (diffE < 10.0) minE - 5.0 else minE
            val finalMaxE = if (diffE < 10.0) maxE + 5.0 else maxE
            
            GraphLimits(
                maxPower = maxP,
                maxSpeed = maxS,
                minElev = finalMinE,
                maxElev = finalMaxE
            )
        }
    }

    // Calculate stopped (speed < 2.0 km/h) regions
    val stoppedSegments = remember(sampledPoints) {
        val list = mutableListOf<Pair<Float, Float>>()
        var startRatio: Float? = null
        for (i in sampledPoints.indices) {
            val pt = sampledPoints[i]
            val ratio = i.toFloat() / (sampledPoints.size - 1).toFloat()
            if (pt.isValid && pt.speed < 2.0) {
                if (startRatio == null) {
                    startRatio = ratio
                }
            } else {
                if (startRatio != null) {
                    list.add(Pair(startRatio, ratio))
                    startRatio = null
                }
            }
        }
        if (startRatio != null) {
            list.add(Pair(startRatio, 1.0f))
        }
        list
    }

    var activeDragHandle by remember { mutableStateOf<DragHandle?>(null) }

    Card(
        backgroundColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E5EA)),
        elevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TELEMETRY TIMELINE & VIDEO TRIMMER",
                    color = Color(0xFF1C1C1E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
                
                // Color legend
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF9500), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text("Power", color = Color(0xFF636366), fontSize = 9.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF007AFF), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text("Speed", color = Color(0xFF636366), fontSize = 9.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF34C759), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text("Elevation", color = Color(0xFF636366), fontSize = 9.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF3B30).copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text("Stopped (停車)", color = Color(0xFFFF3B30), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .alpha(if (isEncoding) 0.6f else 1f)
                    .background(if (isEncoding) Color(0xFFE5E5EA).copy(alpha = 0.5f) else Color(0xFFF2F2F7))
                    .border(1.dp, Color(0xFFE5E5EA), RoundedCornerShape(6.dp))
                    .pointerInput(isEncoding) {
                        if (isEncoding) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                val vLength = currentVideoLengthMs
                                val vDuration = vLength / 1000.0
                                if (vDuration > 0) {
                                    val w = size.width.toFloat()
                                    if (w > 0f) {
                                        val xStart = (currentTrimStartSeconds / vDuration) * w
                                        val xEnd = (currentTrimEndSeconds / vDuration) * w
                                        val xPlayhead = (currentVideoCurrentTimeMs / 1000.0 / vDuration) * w
                                        
                                        val threshold = 20.dp.toPx()
                                        activeDragHandle = when {
                                            kotlin.math.abs(offset.x - xStart) < threshold -> DragHandle.TRIM_START
                                            kotlin.math.abs(offset.x - xEnd) < threshold -> DragHandle.TRIM_END
                                            kotlin.math.abs(offset.x - xPlayhead) < threshold -> DragHandle.PLAYHEAD
                                            else -> null
                                        }
                                        
                                        // If no handle is grabbed, perform a seek click
                                        if (activeDragHandle == null) {
                                            val ratio = (offset.x / w).coerceIn(0f, 1f)
                                            currentOnSeek((ratio * vLength).toLong())
                                        }
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val vLength = currentVideoLengthMs
                                val vDuration = vLength / 1000.0
                                val w = size.width.toFloat()
                                if (vDuration > 0 && w > 0f && activeDragHandle != null) {
                                    val ratio = (change.position.x / w).coerceIn(0f, 1f)
                                    val targetSec = ratio * vDuration
                                    
                                    when (activeDragHandle) {
                                        DragHandle.TRIM_START -> {
                                            currentOnTrimStartChange(targetSec.coerceIn(0.0, currentTrimEndSeconds - 1.0))
                                        }
                                        DragHandle.TRIM_END -> {
                                            currentOnTrimEndChange(targetSec.coerceIn(currentTrimStartSeconds + 1.0, vDuration))
                                        }
                                        DragHandle.PLAYHEAD -> {
                                            val targetTimeMs = (targetSec * 1000.0).toLong()
                                            val now = System.currentTimeMillis()
                                            if (now - lastSeekTime > 80) {
                                                currentOnSeek(targetTimeMs)
                                                lastSeekTime = now
                                                pendingSeekTimeMs = null
                                            } else {
                                                pendingSeekTimeMs = targetTimeMs
                                            }
                                        }
                                        null -> {}
                                    }
                                }
                            },
                            onDragEnd = {
                                if (activeDragHandle == DragHandle.PLAYHEAD && pendingSeekTimeMs != null) {
                                    currentOnSeek(pendingSeekTimeMs!!)
                                }
                                activeDragHandle = null
                                pendingSeekTimeMs = null
                            },
                            onDragCancel = {
                                if (activeDragHandle == DragHandle.PLAYHEAD && pendingSeekTimeMs != null) {
                                    currentOnSeek(pendingSeekTimeMs!!)
                                }
                                activeDragHandle = null
                                pendingSeekTimeMs = null
                            }
                        )
                    }
            ) {
                // 1. Static Graph Background & Controls (Not depending on videoCurrentTimeMs)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    
                    if (videoDurationSec <= 0 || sampledPoints.isEmpty()) {
                        drawText(
                            textMeasurer,
                            "No Telemetry or Video loaded",
                            style = TextStyle(color = Color(0xFF8E8E93), fontSize = 12.sp),
                            topLeft = Offset(w / 2f - 70.dp.toPx(), h / 2f - 10.dp.toPx())
                        )
                        return@Canvas
                    }

                    // 1. Draw Grid Lines
                    val numHorizGrids = 4
                    for (i in 1 until numHorizGrids) {
                        val gridY = (i.toFloat() / numHorizGrids.toFloat()) * h
                        drawLine(
                            color = Color(0xFFE5E5EA),
                            start = Offset(0f, gridY),
                            end = Offset(w, gridY),
                            strokeWidth = 1f
                        )
                    }
                    
                    // Draw Time Grid Ticks (every 10% of duration)
                    val ticks = 10
                    for (i in 0..ticks) {
                        val ratio = i.toFloat() / ticks.toFloat()
                        val tickX = ratio * w
                        drawLine(
                            color = Color(0xFFE5E5EA),
                            start = Offset(tickX, 0f),
                            end = Offset(tickX, h),
                            strokeWidth = 1f
                        )
                        // Label
                        val tickSec = ratio * videoDurationSec
                        val labelStr = formatTime((tickSec * 1000).toLong()).substring(0, 5) // mm:ss
                        val labelLayout = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(color = Color(0xFF8E8E93), fontSize = 8.sp)
                        )
                        drawText(
                            textLayoutResult = labelLayout,
                            topLeft = Offset(
                                x = (tickX - labelLayout.size.width / 2f).coerceIn(4f, w - labelLayout.size.width - 4f),
                                y = h - labelLayout.size.height - 2f
                            )
                        )
                    }

                    // 2. Draw Stopped (停車した位置) Highlight Regions
                    for (seg in stoppedSegments) {
                        val xS = seg.first * w
                        val xE = seg.second * w
                        drawRect(
                            color = Color(0x1FEE2C38), // Soft warning red
                            topLeft = Offset(xS, 0f),
                            size = Size(xE - xS, h)
                        )
                    }

                    // Draw license-plate detection distribution. This is intentionally
                    // independent from telemetry validity so privacy scan coverage is visible.
                    val plateRecords = plateCache?.records ?: emptyList()
                    if (blurLicensePlates && plateRecords.isNotEmpty()) {
                        val markerColor = Color(0xFF5856D6)
                        val bandTop = 4.dp.toPx()
                        val bandHeight = 12.dp.toPx()
                        plateRecords.forEach { record ->
                            val x = ((record.timeMs / 1000.0 / videoDurationSec) * w).toFloat().coerceIn(0f, w)
                            val markerWidth = if (record.boxes.size > 1) 3.dp.toPx() else 2.dp.toPx()
                            drawRect(
                                color = markerColor.copy(alpha = 0.75f),
                                topLeft = Offset(x - markerWidth / 2f, bandTop),
                                size = Size(markerWidth, bandHeight)
                            )
                        }
                    }

                    // 3. Draw Elevation Area Chart (background, bottom 40% height)
                    val elevPath = Path()
                    var startedElev = false
                    var pathCreatedElev = false
                    val maxE = limits.maxElev
                    val minE = limits.minElev
                    val diffE = maxOf(0.1, maxE - minE)
                    
                    for (i in sampledPoints.indices) {
                        val pt = sampledPoints[i]
                        val x = (i.toFloat() / (sampledPoints.size - 1).toFloat()) * w
                        if (pt.isValid) {
                            val ratio = (pt.elevation - minE) / diffE
                            val y = h - (ratio.toFloat() * (h * 0.4f)) // bottom 40% height scale
                            if (!startedElev) {
                                elevPath.moveTo(x, h)
                                elevPath.lineTo(x, y)
                                startedElev = true
                                pathCreatedElev = true
                            } else {
                                elevPath.lineTo(x, y)
                            }
                        } else {
                            if (startedElev) {
                                elevPath.lineTo(x, h)
                                startedElev = false
                            }
                        }
                    }
                    if (startedElev) {
                        elevPath.lineTo(w, h)
                    }
                    if (pathCreatedElev) {
                        elevPath.close()
                        drawPath(elevPath, Color(0x3334C759)) // light green fill
                    }

                    // 4. Draw Power Line
                    val powerPath = Path()
                    var startedPower = false
                    var pathCreatedPower = false
                    val maxP = limits.maxPower
                    for (i in sampledPoints.indices) {
                        val pt = sampledPoints[i]
                        val x = (i.toFloat() / (sampledPoints.size - 1).toFloat()) * w
                        if (pt.isValid) {
                            val y = h - ((pt.power / maxP).toFloat() * h).coerceIn(0f, h)
                            if (!startedPower) {
                                powerPath.moveTo(x, y)
                                startedPower = true
                                pathCreatedPower = true
                            } else {
                                powerPath.lineTo(x, y)
                            }
                        } else {
                            startedPower = false
                        }
                    }
                    if (pathCreatedPower) {
                        drawPath(
                            path = powerPath,
                            color = Color(0xFFFF9500), // Orange
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // 5. Draw Speed Line
                    val speedPath = Path()
                    var startedSpeed = false
                    var pathCreatedSpeed = false
                    val maxS = limits.maxSpeed
                    for (i in sampledPoints.indices) {
                        val pt = sampledPoints[i]
                        val x = (i.toFloat() / (sampledPoints.size - 1).toFloat()) * w
                        if (pt.isValid) {
                            val y = h - ((pt.speed / maxS).toFloat() * h).coerceIn(0f, h)
                            if (!startedSpeed) {
                                speedPath.moveTo(x, y)
                                startedSpeed = true
                                pathCreatedSpeed = true
                            } else {
                                speedPath.lineTo(x, y)
                            }
                        } else {
                            startedSpeed = false
                        }
                    }
                    if (pathCreatedSpeed) {
                        drawPath(
                            path = speedPath,
                            color = Color(0xFF007AFF), // Blue
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // 6. Draw Trim Boundary Excluded Overlays
                    val xStart = ((trimStartSeconds / videoDurationSec) * w).toFloat()
                    val xEnd = ((trimEndSeconds / videoDurationSec) * w).toFloat()

                    drawRect(
                        color = Color(0x77000000),
                        topLeft = Offset(0f, 0f),
                        size = Size(xStart, h)
                    )
                    drawRect(
                        color = Color(0x77000000),
                        topLeft = Offset(xEnd, 0f),
                        size = Size(w - xEnd, h)
                    )

                    // 7. Draw Trim Handles and lines
                    // Trim Start (Green)
                    drawLine(
                        color = Color(0xFF34C759),
                        start = Offset(xStart, 0f),
                        end = Offset(xStart, h),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawCircle(
                        color = Color(0xFF34C759),
                        radius = 8.dp.toPx(),
                        center = Offset(xStart, h / 2f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = Offset(xStart, h / 2f)
                    )
                    
                    // Trim End (Red)
                    drawLine(
                        color = Color(0xFFFF3B30),
                        start = Offset(xEnd, 0f),
                        end = Offset(xEnd, h),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawCircle(
                        color = Color(0xFFFF3B30),
                        radius = 8.dp.toPx(),
                        center = Offset(xEnd, h / 2f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = Offset(xEnd, h / 2f)
                    )

                    // 7.5. Draw Split Points (Purple dashed lines)
                    splitPoints.forEach { splitSec ->
                        if (splitSec in trimStartSeconds..trimEndSeconds) {
                            val xSplit = ((splitSec / videoDurationSec) * w).toFloat()
                            drawLine(
                                color = Color(0xFFAF52DE), // System Purple
                                start = Offset(xSplit, 0f),
                                end = Offset(xSplit, h),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )
                        }
                    }
                }

                // 2. Dynamic Playhead Canvas (Depends only on videoCurrentTimeMs)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    if (videoDurationSec > 0 && !sampledPoints.isEmpty()) {
                        val xPlayhead = ((videoCurrentTimeMs / 1000.0 / videoDurationSec) * w).toFloat()
                        // Draw Playhead (Blue)
                        drawLine(
                            color = Color(0xFF007AFF),
                            start = Offset(xPlayhead, 0f),
                            end = Offset(xPlayhead, h),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawCircle(
                            color = Color(0xFF007AFF),
                            radius = 4.dp.toPx(),
                            center = Offset(xPlayhead, 0f)
                        )
                    }
                }
            }

            // Quick Info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Trim Range: ${formatTime((trimStartSeconds * 1000).toLong())} ~ ${formatTime((trimEndSeconds * 1000).toLong())}",
                    color = Color(0xFF1C1C1E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tip: Drag the Green (Start) & Red (End) handles on the graph to trim.",
                    color = Color(0xFF8E8E93),
                    fontSize = 9.sp
                )
            }
        }
    }
}
