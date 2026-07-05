package components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.material.IconButton
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import fit.FitParser
import utils.formatTime


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
    onSeekStart: () -> Unit = {},
    onSeekProgress: (Long) -> Unit = {},
    onSeekEnd: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    isEncoding: Boolean = false,
    isDetectingPlates: Boolean = false,
    plateCache: fit.VideoPlatesCache? = null,
    blurLicensePlates: Boolean = false,
    language: String = "ja",
    isFolded: Boolean = false,
    onFoldToggle: (Boolean) -> Unit = {},
    videoStartUtc: String = "",
    timeOffsetMillis: Long = 0L,
    onTimeOffsetChange: (Long) -> Unit = {},
    syncAnchorSec: Double? = null,
    syncCorrelation: Double? = null,
    isTelemetryCut: Boolean = false
) {
    val textMeasurer = rememberTextMeasurer()
    val videoDurationSec = videoLengthMs / 1000.0
    val fitDurationSec = remember(telemetryPoints) {
        if (telemetryPoints.isEmpty()) 0.0
        else {
            val fitTs = telemetryPoints.map { it.timestamp }
            fitTs.last() - fitTs.first()
        }
    }
    val timelineDurationSec = if (!isTelemetryCut && fitDurationSec > 0.0) fitDurationSec else videoDurationSec

    val fitStartUtc = remember(telemetryPoints) {
        if (telemetryPoints.isEmpty()) ""
        else {
            try {
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val fitStartSec = telemetryPoints.first().timestamp
                val fitInstant = java.time.Instant.ofEpochSecond((fitStartSec + fitEpoch).toLong())
                fitInstant.toString()
            } catch (e: Exception) {
                ""
            }
        }
    }

    val fitStartRelativeSec = remember(fitStartUtc, adjustedStartUtc) {
        if (fitStartUtc.isEmpty() || adjustedStartUtc.isEmpty()) Double.NaN
        else {
            try {
                val fitInstant = java.time.Instant.parse(fitStartUtc)
                val adjustedInstant = java.time.Instant.parse(adjustedStartUtc)
                (fitInstant.toEpochMilli() - adjustedInstant.toEpochMilli()) / 1000.0
            } catch (e: Exception) {
                Double.NaN
            }
        }
    }

    // Centralised coordinate system for all timeline ↔ video conversions.
    val coords = remember(timelineDurationSec, videoDurationSec, adjustedStartUtc, fitStartUtc, isTelemetryCut) {
        val parsedStartDiffSec = if (adjustedStartUtc.isNotEmpty() && fitStartUtc.isNotEmpty()) {
            try {
                val vUtc = java.time.Instant.parse(adjustedStartUtc).epochSecond
                val fUtc = java.time.Instant.parse(fitStartUtc).epochSecond
                (vUtc - fUtc).toDouble()
            } catch (e: Exception) { 0.0 }
        } else 0.0
        TimelineCoordinateSystem(
            timelineDurationSec = timelineDurationSec,
            videoDurationSec = videoDurationSec,
            startDiffSec = if (isTelemetryCut) 0.0 else parsedStartDiffSec,
            isTelemetryCut = isTelemetryCut
        )
    }

    val telemetryEvents = remember(telemetryPoints, adjustedStartUtc) {
        if (telemetryPoints.isEmpty() || adjustedStartUtc.isEmpty()) emptyList<TimelineEvent>()
        else {
            try {
                val startTime = java.time.Instant.parse(adjustedStartUtc)
                val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                val startFitTs = startTime.toEpochMilli() / 1000.0 - fitEpoch
                
                val events = mutableListOf<TimelineEvent>()
                
                // Max Power Point
                val maxPowerPt = telemetryPoints.maxByOrNull { it.power }
                if (maxPowerPt != null && maxPowerPt.power > 50.0) {
                    events.add(TimelineEvent(
                        label = "Max Power (${maxPowerPt.power.toInt()}W)",
                        seconds = maxPowerPt.timestamp - startFitTs,
                        color = Color(0xFFFF9500) // Orange
                    ))
                }


                // Max Speed Point
                val maxSpeedPt = telemetryPoints.maxByOrNull { it.speed }
                if (maxSpeedPt != null && maxSpeedPt.speed > 5.0) {
                    events.add(TimelineEvent(
                        label = "Max Speed (${String.format(java.util.Locale.US, "%.1f", maxSpeedPt.speed)} km/h)",
                        seconds = maxSpeedPt.timestamp - startFitTs,
                        color = Color(0xFF007AFF) // Blue
                    ))
                }

                events
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    val currentVideoLengthMs by rememberUpdatedState(videoLengthMs)
    val currentTrimStartSeconds by rememberUpdatedState(trimStartSeconds)
    val currentTrimEndSeconds by rememberUpdatedState(trimEndSeconds)
    val currentVideoCurrentTimeMs by rememberUpdatedState(videoCurrentTimeMs)
    
    val currentOnTrimStartChange by rememberUpdatedState(onTrimStartChange)
    val currentOnTrimEndChange by rememberUpdatedState(onTrimEndChange)
    val currentOnSeekStart by rememberUpdatedState(onSeekStart)
    val currentOnSeekProgress by rememberUpdatedState(onSeekProgress)
    val currentOnSeekEnd by rememberUpdatedState(onSeekEnd)

    val currentAdjustedStartUtc by rememberUpdatedState(adjustedStartUtc)
    val currentVideoStartUtc by rememberUpdatedState(videoStartUtc)
    val currentFitStartUtc by rememberUpdatedState(fitStartUtc)
    val currentTimelineDurationSec by rememberUpdatedState(timelineDurationSec)
    val currentVideoDurationSec by rememberUpdatedState(videoDurationSec)
    val currentIsTelemetryCut by rememberUpdatedState(isTelemetryCut)
    val currentCoords by rememberUpdatedState(coords)
    val currentOnTimeOffsetChange by rememberUpdatedState(onTimeOffsetChange)

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
                val sampleDuration = if (isTelemetryCut) videoDurationSec else timelineDurationSec
                val firstPoint = telemetryPoints.firstOrNull()
                val fitBaseTimestamp = firstPoint?.timestamp ?: 0.0
                
                List(numSamples) { i ->
                    val sec = (i.toFloat() / (numSamples - 1).toFloat()) * sampleDuration.toFloat()
                    val fitTs = if (isTelemetryCut) {
                        startFitTs + sec
                    } else {
                        fitBaseTimestamp + sec
                    }
                    
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
    var dragStartStartDiffSec by remember { mutableStateOf(0.0) }
    var dragStartX by remember { mutableStateOf(0.0f) }
    var isHoveringVideoRange by remember { mutableStateOf(false) }

    val customCursor = if (!isTelemetryCut && (isHoveringVideoRange || activeDragHandle == DragHandle.VIDEO_RANGE)) {
        PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR))
    } else {
        PointerIcon.Default
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerHoverIcon(customCursor)
            .pointerInput(coords) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && !coords.isTelemetryCut && coords.timelineDurationSec > 0) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0f) {
                                if (change.position.x in 0f..w && change.position.y in 0f..h) {
                                    isHoveringVideoRange = coords.containsVideoRangePixel(change.position.x, w)
                                } else {
                                    isHoveringVideoRange = false
                                }
                            }
                        } else {
                            isHoveringVideoRange = false
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(if (isFolded) 4.dp else 8.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "TELEMETRY TIMELINE & VIDEO TRIMMER",
                        color = Color(0xFF1C1C1E),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                    val toggleLabel = if (isFolded) "▼ ${utils.Localizer.get("expand_timeline", language)}" else "▲ ${utils.Localizer.get("fold_timeline", language)}"
                    Text(
                        text = toggleLabel,
                        color = Color(0xFF007AFF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { onFoldToggle(!isFolded) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                
                if (!isFolded) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(0xFF5856D6), RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(4.dp))
                            Text(utils.Localizer.get("plate_legend", language), color = Color(0xFF636366), fontSize = 9.sp)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isFolded) 28.dp else 100.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .alpha(if (isEncoding || isDetectingPlates) 0.6f else 1f)
                    .background(if (isEncoding || isDetectingPlates) Color(0xFFE5E5EA).copy(alpha = 0.5f) else Color(0xFFF2F2F7))
                    .border(1.dp, Color(0xFFE5E5EA), RoundedCornerShape(6.dp))
                    .pointerInput(isEncoding || isDetectingPlates) {
                        if (isEncoding || isDetectingPlates) return@pointerInput
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val w = size.width.toFloat()
                                if (w > 0f && currentTimelineDurationSec > 0) {
                                    val ratio = (offset.x / w).coerceIn(0f, 1f)
                                    val targetSec = ratio * currentTimelineDurationSec
                                    if (currentVideoStartUtc.isNotEmpty() && currentFitStartUtc.isNotEmpty()) {
                                        val newOffsetMs = utils.TelemetryAligner.calculateOffsetForVideoStartAtFitSec(
                                            videoStartUtc = currentVideoStartUtc,
                                            fitStartUtc = currentFitStartUtc,
                                            videoStartFitSec = targetSec
                                        )
                                        currentOnTimeOffsetChange(newOffsetMs)
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isEncoding || isDetectingPlates) {
                        if (isEncoding || isDetectingPlates) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                val vLength = currentVideoLengthMs
                                val c = currentCoords
                                if (c.timelineDurationSec > 0) {
                                    val w = size.width.toFloat()
                                    if (w > 0f) {
                                        // Use coords for all handle positions (correctly accounts for startDiffSec)
                                        val xStart = c.videoSecToPixelX(currentTrimStartSeconds, w)
                                        val xEnd = c.videoSecToPixelX(currentTrimEndSeconds, w)
                                        val xPlayhead = c.videoSecToPixelX(currentVideoCurrentTimeMs / 1000.0, w)
                                        val xFitStart = if (!fitStartRelativeSec.isNaN()) {
                                            c.absoluteSecToPixelX(fitStartRelativeSec, w).takeIf { it.isFinite() } ?: -999f
                                        } else -999f
                                        val threshold = 20.dp.toPx()
                                        activeDragHandle = when {
                                            kotlin.math.abs(offset.x - xStart) < threshold -> DragHandle.TRIM_START
                                            kotlin.math.abs(offset.x - xEnd) < threshold -> DragHandle.TRIM_END
                                            kotlin.math.abs(offset.x - xPlayhead) < threshold -> DragHandle.PLAYHEAD
                                            kotlin.math.abs(offset.x - xFitStart) < threshold -> DragHandle.FIT_START
                                            !c.isTelemetryCut && c.containsVideoRangePixel(offset.x, w) -> DragHandle.VIDEO_RANGE
                                            else -> null
                                        }
                                        
                                        if (activeDragHandle == DragHandle.PLAYHEAD) {
                                            currentOnSeekStart()
                                        }
                                        if (activeDragHandle == DragHandle.VIDEO_RANGE) {
                                            dragStartStartDiffSec = c.startDiffSec
                                            dragStartX = offset.x
                                        }
                                        
                                        // If no handle is grabbed, perform a seek click
                                        if (activeDragHandle == null) {
                                            val videoSec = c.pixelXToVideoSec(offset.x, w)
                                            val targetMs = (videoSec * 1000.0).toLong().coerceIn(0L, vLength)
                                            currentOnSeekEnd(targetMs)
                                        }
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val vLength = currentVideoLengthMs
                                val c = currentCoords
                                val w = size.width.toFloat()
                                if (c.timelineDurationSec > 0 && w > 0f && activeDragHandle != null) {
                                    val videoSec = c.pixelXToVideoSec(change.position.x.coerceIn(0f, w), w)
                                    val absoluteSec = c.pixelXToAbsoluteSec(change.position.x.coerceIn(0f, w), w)
                                    
                                    when (activeDragHandle) {
                                        DragHandle.TRIM_START -> {
                                            currentOnTrimStartChange(videoSec.coerceIn(0.0, currentTrimEndSeconds - 1.0))
                                        }
                                        DragHandle.TRIM_END -> {
                                            currentOnTrimEndChange(videoSec.coerceIn(currentTrimStartSeconds + 1.0, c.videoDurationSec))
                                        }
                                        DragHandle.PLAYHEAD -> {
                                            val targetTimeMs = (videoSec.coerceIn(0.0, c.videoDurationSec) * 1000.0).toLong()
                                            currentOnSeekProgress(targetTimeMs)
                                        }
                                        DragHandle.FIT_START -> {
                                            if (currentVideoStartUtc.isNotEmpty() && currentFitStartUtc.isNotEmpty()) {
                                                val newOffsetMs = utils.TelemetryAligner.calculateOffsetFromTargetSec(
                                                    videoStartUtc = currentVideoStartUtc,
                                                    fitStartUtc = currentFitStartUtc,
                                                    targetSec = absoluteSec
                                                )
                                                currentOnTimeOffsetChange(newOffsetMs)
                                            }
                                        }
                                        DragHandle.VIDEO_RANGE -> {
                                            if (!c.isTelemetryCut && currentVideoStartUtc.isNotEmpty() && currentFitStartUtc.isNotEmpty()) {
                                                val targetSec = c.videoRangeDragTargetStartSec(
                                                    dragStartStartDiffSec = dragStartStartDiffSec,
                                                    dragStartX = dragStartX,
                                                    currentX = change.position.x.coerceIn(0f, w),
                                                    w = w
                                                )
                                                
                                                val newOffsetMs = utils.TelemetryAligner.calculateOffsetForVideoStartAtFitSec(
                                                    videoStartUtc = currentVideoStartUtc,
                                                    fitStartUtc = currentFitStartUtc,
                                                    videoStartFitSec = targetSec
                                                )
                                                currentOnTimeOffsetChange(newOffsetMs)
                                            }
                                        }
                                        null -> {}
                                    }
                                }
                            },
                            onDragEnd = {
                                if (activeDragHandle == DragHandle.PLAYHEAD) {
                                    currentOnSeekEnd(currentVideoCurrentTimeMs)
                                }
                                activeDragHandle = null
                            },
                            onDragCancel = {
                                if (activeDragHandle == DragHandle.PLAYHEAD) {
                                    currentOnSeekEnd(currentVideoCurrentTimeMs)
                                }
                                activeDragHandle = null
                            }
                        )
                    }
            ) {
                // 1. Static Graph Background & Controls (Not depending on videoCurrentTimeMs)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    
                    val startDiffSec = coords.startDiffSec

                    if (videoDurationSec <= 0 || videoDurationSec.isNaN() || sampledPoints.isEmpty()) {
                        drawText(
                            textMeasurer,
                            "No Telemetry or Video loaded",
                            style = TextStyle(color = Color(0xFF8E8E93), fontSize = 12.sp),
                            topLeft = Offset(w / 2f - 70.dp.toPx(), h / 2f - 10.dp.toPx())
                        )
                        return@Canvas
                    }

                    // 1. Draw Grid Lines
                    if (!isFolded) {
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
                        }

                        // Mask non-video range
                        if (!isTelemetryCut && timelineDurationSec > 0.0) {
                            val xVideoStart = coords.videoStartPixelX(w).coerceIn(0f, w)
                            val xVideoEnd = coords.videoEndPixelX(w).coerceIn(0f, w)
                            
                            // Left exclusion
                            if (xVideoStart > 0f) {
                                drawRect(
                                    color = Color(0x1F000000), // Soft dark mask
                                    topLeft = Offset(0f, 0f),
                                    size = Size(xVideoStart, h)
                                )
                                drawLine(
                                    color = Color(0x55000000),
                                    start = Offset(xVideoStart, 0f),
                                    end = Offset(xVideoStart, h),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                                )
                            }
                            // Right exclusion
                            if (xVideoEnd < w) {
                                drawRect(
                                    color = Color(0x1F000000), // Soft dark mask
                                    topLeft = Offset(xVideoEnd, 0f),
                                    size = Size(w - xVideoEnd, h)
                                )
                                drawLine(
                                    color = Color(0x55000000),
                                    start = Offset(xVideoEnd, 0f),
                                    end = Offset(xVideoEnd, h),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                                )
                            }
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
                                val x = if (videoDurationSec > 0.0 && !videoDurationSec.isNaN()) {
                                    ((record.timeMs / 1000.0 / videoDurationSec) * w).toFloat().coerceIn(0f, w)
                                } else 0f
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
                     val safeTrimStart = if (trimStartSeconds.isNaN() || trimStartSeconds.isInfinite()) 0.0 else trimStartSeconds
                     val safeTrimEnd = if (trimEndSeconds.isNaN() || trimEndSeconds.isInfinite()) videoDurationSec else trimEndSeconds

                     val xStart = coords.videoSecToPixelX(safeTrimStart, w).let { if (it.isFinite()) it.coerceIn(0f, w) else 0f }
                     val xEnd = coords.videoSecToPixelX(safeTrimEnd, w).let { if (it.isFinite()) it.coerceIn(0f, w) else w }

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

                    // Draw Telemetry Start Indicator (Magenta)
                    val xFitStart = if (isTelemetryCut && timelineDurationSec > 0.0 && !fitStartRelativeSec.isNaN()) {
                        ((fitStartRelativeSec / timelineDurationSec) * w).toFloat().takeIf { it.isFinite() } ?: null
                    } else null

                    if (xFitStart != null) {
                        drawLine(
                            color = Color(0xFFE040FB), // Vivid Magenta
                            start = Offset(xFitStart, 0f),
                            end = Offset(xFitStart, h),
                            strokeWidth = 2.5.dp.toPx()
                        )
                        drawCircle(
                            color = Color(0xFFE040FB),
                            radius = 6.dp.toPx(),
                            center = Offset(xFitStart, 6.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = Offset(xFitStart, 6.dp.toPx())
                        )
                    }

                    // Draw Peak Events (Max Power, Max Speed, Max/Min Elevation)
                    telemetryEvents.forEach { event ->
                        val x = coords.videoSecToPixelX(event.seconds, w).takeIf { it.isFinite() }

                        if (x != null && x in 0f..w) {
                            drawLine(
                                color = event.color.copy(alpha = 0.6f),
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                            )
                            val labelLayout = textMeasurer.measure(
                                text = event.label,
                                style = TextStyle(color = event.color, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                            )
                            drawText(
                                textLayoutResult = labelLayout,
                                topLeft = Offset(
                                    x = (x + 3.dp.toPx()).coerceIn(4f, w - labelLayout.size.width - 4f),
                                    y = 20.dp.toPx()
                                )
                            )
                        }
                    }

                    // Draw IMU Auto-Sync Anchor
                    if (syncAnchorSec != null) {
                        val xSync = coords.videoSecToPixelX(syncAnchorSec, w).takeIf { it.isFinite() }

                        if (xSync != null && xSync in 0f..w) {
                            val anchorColor = Color(0xFFFFCC00)
                            drawLine(
                                color = anchorColor,
                                start = Offset(xSync, 0f),
                                end = Offset(xSync, h),
                                strokeWidth = 2.5.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                            )
                            val corrLabel = if (syncCorrelation != null) {
                                "IMU Anchor (r=${String.format(java.util.Locale.US, "%.2f", syncCorrelation)})"
                            } else "IMU Sync Anchor"
                            
                            val labelLayout = textMeasurer.measure(
                                text = corrLabel,
                                style = TextStyle(color = anchorColor, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                            )
                            drawText(
                                textLayoutResult = labelLayout,
                                topLeft = Offset(
                                    x = (xSync - labelLayout.size.width / 2f).coerceIn(4f, w - labelLayout.size.width - 4f),
                                    y = h / 2f - labelLayout.size.height / 2f
                                )
                            )
                        }
                    }

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
                            val xSplit = coords.videoSecToPixelX(splitSec, w).let { if (it.isFinite()) it else 0f }
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
            }

                // 2. Dynamic Playhead Canvas (Depends only on videoCurrentTimeMs)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    if (videoDurationSec > 0 && !sampledPoints.isEmpty()) {
                        val xPlayhead = coords.videoSecToPixelX(videoCurrentTimeMs / 1000.0, w)
                        
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

            if (!isFolded) {
                Spacer(Modifier.height(4.dp))
                val density = LocalDensity.current
                val rangeBarHeightPx = remember(density) { with(density) { 4.dp.toPx() } }
                val tickLengthPx = remember(density) { with(density) { 5.dp.toPx() } }
                val tickWidthPx = remember(density) { with(density) { 1.2.dp.toPx() } }
                val labelYOffsetPx = remember(density) { with(density) { 9.dp.toPx() } }
                val absLabelYOffsetPx = remember(density) { with(density) { 23.dp.toPx() } }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .pointerInput(isEncoding || isDetectingPlates) {
                            if (isEncoding || isDetectingPlates) return@pointerInput
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val c = currentCoords
                                    val w = size.width.toFloat()
                                    if (!c.isTelemetryCut && c.timelineDurationSec > 0.0 && w > 0f) {
                                        if (c.containsVideoRangePixel(offset.x, w)) {
                                            activeDragHandle = DragHandle.VIDEO_RANGE
                                            dragStartStartDiffSec = c.startDiffSec
                                            dragStartX = offset.x
                                        } else {
                                            activeDragHandle = null
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val c = currentCoords
                                    val w = size.width.toFloat()
                                    if (activeDragHandle == DragHandle.VIDEO_RANGE &&
                                        !c.isTelemetryCut &&
                                        c.timelineDurationSec > 0.0 &&
                                        w > 0f &&
                                        currentVideoStartUtc.isNotEmpty() &&
                                        currentFitStartUtc.isNotEmpty()
                                    ) {
                                        val targetSec = c.videoRangeDragTargetStartSec(
                                            dragStartStartDiffSec = dragStartStartDiffSec,
                                            dragStartX = dragStartX,
                                            currentX = change.position.x.coerceIn(0f, w),
                                            w = w
                                        )
                                        val newOffsetMs = utils.TelemetryAligner.calculateOffsetForVideoStartAtFitSec(
                                            videoStartUtc = currentVideoStartUtc,
                                            fitStartUtc = currentFitStartUtc,
                                            videoStartFitSec = targetSec
                                        )
                                        currentOnTimeOffsetChange(newOffsetMs)
                                    }
                                },
                                onDragEnd = {
                                    activeDragHandle = null
                                },
                                onDragCancel = {
                                    activeDragHandle = null
                                }
                            )
                        }
                ) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val ticks = 5
                    
                    val startDiffSec = coords.startDiffSec

                    val xVideoStart = coords.videoStartPixelX(w)
                    val xVideoEnd = coords.videoEndPixelX(w)

                    // Draw a subtle horizontal bar showing video range in the ruler background
                    if (!isTelemetryCut && timelineDurationSec > 0.0) {
                        val isDragging = activeDragHandle == DragHandle.VIDEO_RANGE
                        val barColor = when {
                            isDragging -> Color(0xBB30D158) // High opacity green when dragging
                            isHoveringVideoRange -> Color(0x7730D158) // Medium opacity green when hovering
                            else -> Color(0x3330D158) // Subtle soft green default
                        }
                        val barHeight = if (isDragging || isHoveringVideoRange) rangeBarHeightPx * 1.5f else rangeBarHeightPx

                        drawRect(
                            color = barColor,
                            topLeft = Offset(xVideoStart, 0f),
                            size = Size(xVideoEnd - xVideoStart, barHeight)
                        )

                        if (isDragging || isHoveringVideoRange) {
                            drawLine(
                                color = Color(0xFF30D158),
                                start = Offset(xVideoStart, barHeight),
                                end = Offset(xVideoEnd, barHeight),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    for (i in 0..ticks) {
                        val ratio = i.toFloat() / ticks.toFloat()
                        val tickX = ratio * w
                        val tickSec = calculateTickSeconds(ratio, timelineDurationSec, startDiffSec)
                        
                        // Draw a tiny Ruler Tick mark (メモリ線)
                        drawLine(
                            color = Color(0xFFC7C7CC),
                            start = Offset(tickX, 0f),
                            end = Offset(tickX, tickLengthPx),
                            strokeWidth = tickWidthPx
                        )
                        
                        val labelStr = if (tickSec < 0.0) {
                            "-" + formatTime((-tickSec * 1000).toLong())
                        } else {
                            formatTime((tickSec * 1000).toLong())
                        }
                        val labelLayout = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(
                                color = Color(0xFF3A3A3C), 
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.Medium
                            )
                        )
                        drawText(
                            textLayoutResult = labelLayout,
                            topLeft = Offset(
                                x = (tickX - labelLayout.size.width / 2f).coerceIn(4f, w - labelLayout.size.width - 4f),
                                y = labelYOffsetPx
                            )
                        )

                        // 2nd tier: absolute time (HH:mm:ss)
                        val absTimeStr = formatAbsoluteTime(tickSec, fitStartUtc, telemetryPoints)
                        if (absTimeStr.isNotEmpty()) {
                            val absLabelLayout = textMeasurer.measure(
                                text = "($absTimeStr)",
                                style = TextStyle(
                                    color = Color(0xFF8E8E93),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            )
                            drawText(
                                textLayoutResult = absLabelLayout,
                                topLeft = Offset(
                                    x = (tickX - absLabelLayout.size.width / 2f).coerceIn(4f, w - absLabelLayout.size.width - 4f),
                                    y = absLabelYOffsetPx
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Quick Info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Trim Range: ${formatTime((trimStartSeconds * 1000).toLong())} ~ ${formatTime((trimEndSeconds * 1000).toLong())}",
                        color = Color(0xFF1C1C1E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val currentLength = trimEndSeconds - trimStartSeconds
                    val maxLimit = videoLengthMs / 1000.0
                    
                    // Button to slide trim range backward (1s)
                    IconButton(
                        onClick = {
                            val newStart = (trimStartSeconds - 1.0).coerceAtLeast(0.0)
                            onTrimStartChange(newStart)
                            onTrimEndChange(newStart + currentLength)
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowLeft,
                            contentDescription = "Slide Backward",
                            tint = Color(0xFF8E8E93)
                        )
                    }
                    
                    // Button to slide trim range forward (1s)
                    IconButton(
                        onClick = {
                            val newEnd = (trimEndSeconds + 1.0).coerceAtMost(maxLimit)
                            onTrimEndChange(newEnd)
                            onTrimStartChange((newEnd - currentLength).coerceAtLeast(0.0))
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Slide Forward",
                            tint = Color(0xFF8E8E93)
                        )
                    }
                }
                Text(
                    text = "Tip: Drag the Green (Start) & Red (End) handles on the graph to trim.",
                    color = Color(0xFF8E8E93),
                    fontSize = 9.sp
                )
            }
        }
    }

data class TimelineEvent(
    val label: String,
    val seconds: Double,
    val color: Color
)

fun calculateTickSeconds(ratio: Float, timelineDurationSec: Double, startDiffSec: Double): Double {
    return (ratio.toDouble() * timelineDurationSec)
}

fun formatAbsoluteTime(tickSec: Double, fitStartUtc: String, telemetryPoints: List<fit.FitParser.TelemetryPoint>): String {
    if (fitStartUtc.isEmpty()) return ""
    return try {
        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
        val fitStartSec = telemetryPoints.firstOrNull()?.timestamp ?: 0.0
        val tickInstant = java.time.Instant.ofEpochSecond((fitStartSec + fitEpoch + tickSec).toLong())
        val localDateTime = java.time.LocalDateTime.ofInstant(tickInstant, java.time.ZoneId.systemDefault())
        localDateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    } catch (e: Exception) {
        ""
    }
}
