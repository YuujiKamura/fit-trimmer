package fit

import kotlin.math.roundToInt
import kotlin.math.max
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class HudConfig(
    val valSize: Float,
    val tightness: Float,
    val spacing: Float,
    val xOffset: Float,
    val yOffset: Float,
    val graphH: Float,
    val graphW: Float,
    val captionPosition: String = "top_center",
    val roadCaptions: List<RoadCaptionSegment> = emptyList(),
    val powerTrendSpanSeconds: Int = 60,
    val useImperialUnits: Boolean = false,
    val language: String = "",
    val elevationGraphScope: String = "video",
    val heartRateAccumulationScope: String = "activity",
    val showSpeed: Boolean = true,
    val showCadence: Boolean = true,
    val showHeartRate: Boolean = true,
    val showPower: Boolean = true,
    val showWkg: Boolean = true,
    val showPowerTrend: Boolean = true,
    val showGrade: Boolean = true,
    val showElevation: Boolean = true,
    val showDistanceTime: Boolean = true,
    val bodyWeightKg: Double = 0.0
)

interface HudCanvas {
    val width: Float
    val height: Float
    fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean = false, anchor: String = "top-left")
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float = 1.0f, outline: Boolean = false)
    fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float = 1.0f)
    fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float = 1.0f)
    fun getTextWidth(text: String, size: Float, bold: Boolean): Float
}

class HudRenderer(val config: HudConfig) {
    // Cache fields for heart rate zones
    private var cachedZonesTotal: IntArray? = null
    private var cachedZonesMaxTotal: Int = 1

    // Cache fields for datetime formatting L1 cache to avoid massive allocations
    private var lastTimestampSeconds = -1L
    private var lastFormattedDateTime = "----- --:--:--"
    private val systemTimeZone by lazy { TimeZone.currentSystemDefault() }

    // Cache fields for elevation points to avoid recalculation/reallocation every frame
    private var cachedOriginalPoints: List<FitParser.TelemetryPoint>? = null
    private var cachedHrAccumPoints: List<FitParser.TelemetryPoint>? = null
    private var cachedCx: Float = 0f
    private var cachedEGy: Float = 0f
    private var cachedGraphW: Float = 0f
    private var cachedGraphH: Float = 0f
    private var cachedPts: List<Pair<Float, Float>> = emptyList()
    private var cachedDrawPoints: List<FitParser.TelemetryPoint> = emptyList()
    private var cachedMinAlt: Double = 0.0
    private var cachedMaxAlt: Double = 100.0
    private var cachedAltDiff: Double = 10.0

    @kotlin.jvm.JvmOverloads
    fun renderFrame(
        canvas: HudCanvas, 
        telemetry: FitParser.TelemetryPoint, 
        originalPoints: List<FitParser.TelemetryPoint>, 
        trimmedPoints: List<FitParser.TelemetryPoint>, 
        pBuf: List<Double>, 
        currentRatio: Float,
        isValid: Boolean = true
    ) {
        // Draw Date & Time overlay in the top-left corner
        val timeX = 40f
        val timeY = 40f
        val dtText = if (isValid) formatDateTime(telemetry.timestamp) else "----- --:--:--"
        val dtTextSize = 24f
        val dtTextWidth = canvas.getTextWidth(dtText, dtTextSize, bold = true)
        val dtTextHeight = dtTextSize
        val dtPadX = 12f
        val dtPadY = 8f
        val dtBoxW = dtTextWidth + dtPadX * 2f
        val dtBoxH = dtTextHeight + dtPadY * 2f
        
        canvas.drawRect(timeX, timeY, dtBoxW, dtBoxH, "#000000", alpha = 0.5f)
        canvas.drawText(dtText, timeX + dtPadX, timeY + dtPadY, dtTextSize, "#ffffff", bold = true)

        val allPoints = if (originalPoints.isEmpty()) listOf(telemetry) else originalPoints
        val videoPoints = if (trimmedPoints.isEmpty()) allPoints else trimmedPoints

        // 心拍・その他の累積統計用のターゲットポイントリスト
        val hrAccumPoints = if (config.heartRateAccumulationScope == "video") videoPoints else allPoints

        // 標高グラフ描画用のターゲットポイントリスト
        val elevGraphPoints = if (config.elevationGraphScope == "video") videoPoints else allPoints

        // Calculate heart rate zones current accumulation for this frame
        val zonesCurrent = IntArray(7)
        if (isValid && hrAccumPoints.isNotEmpty()) {
            for (pt in hrAccumPoints) {
                if (pt.timestamp > telemetry.timestamp) break
                val zIdx = getHrZoneIndex(pt.heartRate)
                if (zIdx in 0..6) {
                    zonesCurrent[zIdx]++
                }
            }
        }
        
        var cx = config.xOffset
        var cy = config.yOffset
        
        val labelSize = 13f
        val valSize = config.valSize // 40f
        val unitSize = 18f
        val tightness = config.tightness // 1f
        val itemSpacing = config.spacing // 20f
        val graphW = config.graphW // 300f
        val graphH = config.graphH // 60f

        fun drawCell(label: String, value: String, unit: String, color: String) {
            // 1. Label (Light grey #e5e7eb)
            canvas.drawText(label, cx, cy, labelSize, "#e5e7eb", bold = true)
            
            // 2. Value (White #ffffff)
            val valY = cy + labelSize + tightness
            canvas.drawText(value, cx, valY, valSize, "#ffffff", bold = true)
            
            // 3. Unit (Color specified)
            val valW = canvas.getTextWidth(value, valSize, true)
            val unitX = cx + valW + 8f
            val unitY = valY + (valSize - unitSize)
            canvas.drawText(unit, unitX, unitY, unitSize, color, bold = true)
            
            // Increment cy for the next cell
            cy += labelSize + tightness + valSize + itemSpacing
        }

        // 1. SPEED
        if (config.showSpeed) {
            val speedVal = if (config.useImperialUnits) telemetry.speed * 0.621371 else telemetry.speed
            val speedUnit = if (config.useImperialUnits) "mph" else "km/h"
            val spdStr = if (isValid) formatOneDecimal(speedVal) else "-"
            drawCell(getLabel("SPEED"), spdStr, speedUnit, "#3b82f6")
        }

        // 2. CADENCE
        if (config.showCadence) {
            val cadStr = if (isValid) telemetry.cadence.roundToInt().toString() else "-"
            drawCell(getLabel("CADENCE"), cadStr, "rpm", "#a78bfa")
        }

        // 3. HEART RATE
        if (config.showHeartRate) {
            val hrStr = if (isValid) telemetry.heartRate.roundToInt().toString() else "-"
            
            // 3.1. Draw standard HEART RATE label and value
            canvas.drawText(getLabel("HEART RATE"), cx, cy, labelSize, "#e5e7eb", bold = true)
            val valY = cy + labelSize + tightness
            canvas.drawText(hrStr, cx, valY, valSize, "#ffffff", bold = true)
            val valW = canvas.getTextWidth(hrStr, valSize, true)
            val unitX = cx + valW + 8f
            val unitY = valY + (valSize - unitSize)
            canvas.drawText("bpm", unitX, unitY, unitSize, "#ffffff", bold = true)
            
            // 3.2. Draw Zone accumulation bar and text immediately below the value
            val subCy = valY + valSize + 6f
            if (isValid) {
                val currentHr = telemetry.heartRate
                val zIdx = getHrZoneIndex(currentHr)
                if (zIdx in 0..6) {
                    val currentSec = zonesCurrent[zIdx]
                    val totalSec = cachedZonesTotal?.get(zIdx) ?: 1
                    val zoneLabel = when (zIdx) {
                        6 -> "190+"
                        5 -> "180-189"
                        4 -> "170-179"
                        3 -> "160-169"
                        2 -> "150-159"
                        1 -> "140-149"
                        0 -> "130-139"
                        else -> ""
                    }
                    val minSecText = formatMinSec(currentSec)
                    
                    // Draw text: e.g. "ZONE 170-179: 12:34"
                    val zoneText = "ZONE $zoneLabel: $minSecText"
                    canvas.drawText(zoneText, cx, subCy, 12f, "#ffffff", bold = true)
                    
                    // Draw 1 single bar representing current zone accumulation progress relative to total zone time
                    val barY = subCy + 15f
                    val maxBarW = 120f
                    val barH = 6f
                    // Background bar (thin/transparent) representing total time
                    canvas.drawRect(cx, barY, maxBarW, barH, "#f87171", alpha = 0.25f)
                    // Foreground bar (solid red) representing current accumulation
                    val progressW = if (totalSec > 0) {
                        val ratio = currentSec.toFloat() / totalSec.toFloat()
                        ratio.coerceIn(0f, 1.0f) * maxBarW
                    } else {
                        0f
                    }
                    if (progressW > 0f) {
                        canvas.drawRect(cx, barY, progressW, barH, "#ef4444", alpha = 1.0f)
                    }
                } else {
                    canvas.drawText("ZONE -: --:--", cx, subCy, 12f, "#9ca3af", bold = true)
                    val barY = subCy + 15f
                    canvas.drawRect(cx, barY, 120f, 6f, "#e5e7eb", alpha = 0.15f)
                }
            } else {
                canvas.drawText("ZONE -: --:--", cx, subCy, 12f, "#9ca3af", bold = true)
                val barY = subCy + 15f
                canvas.drawRect(cx, barY, 120f, 6f, "#e5e7eb", alpha = 0.15f)
            }
            
            // Move cy down to cover this custom sub-section and preserve layout tightness
            cy = valY + valSize + 6f + 12f + 15f + 6f + itemSpacing
        }

        // 4. POWER
        if (config.showPower) {
            val pwrStr = if (isValid) telemetry.power.roundToInt().toString() else "-"
            drawCell(getLabel("POWER"), pwrStr, "W", "#10b981")
        }

        // 5. W/KG
        if (config.showWkg && config.bodyWeightKg > 0.0) {
            val weight = config.bodyWeightKg
            val wkgVal = telemetry.power / weight
            val wkgStr = if (isValid) formatOneDecimal(wkgVal) else "-"
            drawCell("W/KG", wkgStr, "w/kg", "#2dd4bf")
        }

        // 6. POWER TREND (Bar graph)
        if (config.showPowerTrend) {
            val spanText = if (config.powerTrendSpanSeconds >= 60) {
                val min = config.powerTrendSpanSeconds / 60
                val sec = config.powerTrendSpanSeconds % 60
                if (sec > 0) "${min}m ${sec}s" else "${min}m"
            } else {
                "${config.powerTrendSpanSeconds}s"
            }
            canvas.drawText("${getLabel("POWER TREND")} ($spanText, 1s)", cx, cy, labelSize, "#e5e7eb", bold = true)
            val pGy = cy + labelSize + 4f
            
            if (isValid && pBuf.isNotEmpty()) {
                val maxPoints = config.powerTrendSpanSeconds.toFloat()
                val bw = graphW / maxPoints
                var maxPVal = 250.0
                for (v in pBuf) {
                    if (v > maxPVal) {
                        maxPVal = v
                    }
                }
                val maxP = max(maxPVal, 300.0).toFloat()
                pBuf.forEachIndexed { i, v ->
                    val bh = ((v / maxP) * graphH).toFloat()
                    val barX = cx + graphW - (pBuf.size - i) * bw
                    if (barX >= cx) {
                        val barColor = if (v < 250.0) "#10b981" else "#eab308"
                        canvas.drawRect(barX, pGy + graphH - bh, max(1f, bw - 1f), bh, barColor, alpha = 1.0f)
                    }
                }
            } else {
                canvas.drawRect(cx, pGy, graphW, graphH, "#e5e7eb", alpha = 0.08f)
            }

            // Draw time ticks (grid lines & labels)
            val tickIntervalSeconds = when (config.powerTrendSpanSeconds) {
                30 -> 10
                60 -> 20
                180 -> 60
                300 -> 60
                600 -> 120
                1200 -> 300
                else -> config.powerTrendSpanSeconds / 3
            }
            
            var t = tickIntervalSeconds
            val tickLabelSize = labelSize * 0.8f
            while (t < config.powerTrendSpanSeconds) {
                val ratio = t.toFloat() / config.powerTrendSpanSeconds.toFloat()
                val tickX = cx + graphW - (ratio * graphW)
                if (tickX >= cx) {
                    // Thin vertical grid line
                    canvas.drawRect(tickX, pGy, 1f, graphH, "#e5e7eb", alpha = 0.15f)
                    
                    // Centered time label (e.g., -1m, -10s)
                    val label = if (t >= 60) "-${t / 60}m" else "-${t}s"
                    val labelW = canvas.getTextWidth(label, tickLabelSize, false)
                    canvas.drawText(label, tickX - labelW / 2f, pGy + graphH + 2f, tickLabelSize, "#9ca3af")
                }
                t += tickIntervalSeconds
            }
            
            cy += labelSize + 4f + graphH + (tickLabelSize + 4f) + itemSpacing
        }

        // 7. GRADE
        if (config.showGrade) {
            val grdStr = if (isValid) formatGrade(telemetry.grade) else "-"
            drawCell(getLabel("GRADE"), grdStr, "%", "#fbbf24")
        }

        // 8. ELEVATION (Line graph with terrain and pin)
        if (config.showElevation) {
            canvas.drawText(getLabel("ELEVATION"), cx, cy, labelSize, "#e5e7eb", bold = true)
            val eGy = cy + labelSize + 4f + 16f
            
            if (elevGraphPoints.size > 1) {
                val pts: List<Pair<Float, Float>>
                val drawPoints: List<FitParser.TelemetryPoint>
                var minAlt = 0.0
                var maxAlt = 100.0
                var altDiff = 10.0

                if (elevGraphPoints === cachedOriginalPoints &&
                    hrAccumPoints === cachedHrAccumPoints &&
                    cx == cachedCx &&
                    eGy == cachedEGy &&
                    graphW == cachedGraphW &&
                    graphH == cachedGraphH
                ) {
                    pts = cachedPts
                    drawPoints = cachedDrawPoints
                    minAlt = cachedMinAlt
                    maxAlt = cachedMaxAlt
                    altDiff = cachedAltDiff
                } else {
                    val altitudes = elevGraphPoints.map { it.elevation }
                    minAlt = altitudes.firstOrNull() ?: 0.0
                    maxAlt = altitudes.firstOrNull() ?: 100.0
                    for (alt in altitudes) {
                        if (alt < minAlt) minAlt = alt
                        if (alt > maxAlt) maxAlt = alt
                    }
                    altDiff = max(maxAlt - minAlt, 10.0)

                    // Downsample to max 150 points for rendering performance
                    val maxRenderPoints = 150
                    if (elevGraphPoints.size <= maxRenderPoints) {
                        drawPoints = elevGraphPoints
                    } else {
                        val step = (elevGraphPoints.size - 1).toFloat() / (maxRenderPoints - 1)
                        drawPoints = (0 until maxRenderPoints).map { i ->
                            val index = (i * step).roundToInt().coerceIn(elevGraphPoints.indices)
                            elevGraphPoints[index]
                        }
                    }

                    val actualAltDiff = max(maxAlt - minAlt, 10.0)
                    val buffer = actualAltDiff * 0.15
                    val minAltAdj = minAlt - buffer
                    val altDiffAdj = actualAltDiff + (buffer * 2.0)

                    pts = drawPoints.mapIndexed { idx, pt ->
                        val px = cx + (idx.toFloat() / (drawPoints.size - 1)) * graphW
                        val py = (eGy + graphH - ((pt.elevation - minAltAdj) / altDiffAdj) * graphH).toFloat()
                        px to py
                    }
                    
                    cachedOriginalPoints = elevGraphPoints
                    cachedHrAccumPoints = hrAccumPoints
                    cachedCx = cx
                    cachedEGy = eGy
                    cachedGraphW = graphW
                    cachedGraphH = graphH
                    cachedPts = pts
                    cachedDrawPoints = drawPoints
                    cachedMinAlt = minAlt
                    cachedMaxAlt = maxAlt
                    cachedAltDiff = altDiff

                    val zonesTotal = IntArray(7)
                    for (pt in hrAccumPoints) {
                        val zIdx = getHrZoneIndex(pt.heartRate)
                        if (zIdx in 0..6) {
                            zonesTotal[zIdx]++
                        }
                    }
                    cachedZonesTotal = zonesTotal
                    cachedZonesMaxTotal = zonesTotal.maxOrNull()?.coerceAtLeast(1) ?: 1
                }

                // Draw colored terrain polygon segments
                for (i in 0 until pts.size - 1) {
                    val grade = drawPoints[i].grade
                    val segColor = when {
                        grade < -4.0 -> "#3b82f6" // Blue
                        grade < 1.0 -> "#10b981"  // Green
                        grade < 5.0 -> "#fbbf24"  // Yellow
                        grade < 8.0 -> "#ef4444"  // Red
                        else -> "#991b1b"         // Dark Red
                    }
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val polySegment = listOf(
                        p1,
                        p2,
                        p2.first to (eGy + graphH),
                        p1.first to (eGy + graphH)
                    )
                    canvas.drawPolygon(polySegment, segColor, alpha = 0.8f)
                }

                // White terrain border line
                canvas.drawLine(pts, "#ffffff", 1f, alpha = 0.5f)

                val actualAltDiff = max(maxAlt - minAlt, 10.0)
                val buffer = actualAltDiff * 0.15
                val minAltAdj = minAlt - buffer
                val altDiffAdj = actualAltDiff + (buffer * 2.0)

                // Current position marker pin and vertical guide line
                if (isValid) {
                    var currentIdx = elevGraphPoints.binarySearch {
                        it.timestamp.compareTo(telemetry.timestamp)
                    }
                    if (currentIdx < 0) {
                        currentIdx = -currentIdx - 1
                    }
                    currentIdx = currentIdx.coerceIn(elevGraphPoints.indices)

                    val progress = currentIdx.toFloat() / (elevGraphPoints.size - 1)
                    val currX = cx + progress * graphW
                    val currY = (eGy + graphH - ((telemetry.elevation - minAltAdj) / altDiffAdj) * graphH).toFloat()

                    // Vertical guide line
                    canvas.drawLine(listOf(currX to eGy, currX to eGy + graphH), "#ffffff", 1f, alpha = 0.3f)
                    // Red pin triangle pointing to the current position
                    val pinPoly = listOf(
                        currX - 8f to currY - 8f,
                        currX + 8f to currY - 8f,
                        currX to currY
                    )
                    canvas.drawPolygon(pinPoly, "#ef4444", alpha = 1.0f)
                }

                // Start/End Elevation Markers and Labels
                val startPt = pts.first()
                val endPt = pts.last()
                
                // Draw marker dots (small white squares)
                canvas.drawRect(startPt.first - 2.5f, startPt.second - 2.5f, 5f, 5f, "#ffffff", alpha = 1.0f)
                canvas.drawRect(endPt.first - 2.5f, endPt.second - 2.5f, 5f, 5f, "#ffffff", alpha = 1.0f)
                
                // Draw start and end elevation text labels
                val startAlt = elevGraphPoints.first().elevation
                val endAlt = elevGraphPoints.last().elevation
                val startText = if (config.useImperialUnits) {
                    "${(startAlt * 3.28084).roundToInt()}ft"
                } else {
                    "${startAlt.roundToInt()}m"
                }
                val endText = if (config.useImperialUnits) {
                    "${(endAlt * 3.28084).roundToInt()}ft"
                } else {
                    "${endAlt.roundToInt()}m"
                }
                
                val graphLabelSize = 9f
                canvas.drawText(startText, startPt.first, startPt.second - 4f, graphLabelSize, "#ffffff", bold = true, anchor = "bottom-left")
                canvas.drawText(endText, endPt.first, endPt.second - 4f, graphLabelSize, "#ffffff", bold = true, anchor = "bottom-right")

                // 8.3. Peak Elevation Marker and Label (Draw peak in the middle of the graph if not start/end)
                val peakIdx = drawPoints.indexOfFirst { it.elevation == maxAlt }
                val isPeakInMiddle = peakIdx > 0 && peakIdx < drawPoints.size - 1
                if (isPeakInMiddle) {
                    val peakPt = pts[peakIdx]
                    canvas.drawRect(peakPt.first - 2.5f, peakPt.second - 2.5f, 5f, 5f, "#ef4444", alpha = 1.0f)
                    
                    val peakText = if (config.useImperialUnits) {
                        "${(maxAlt * 3.28084).roundToInt()}ft"
                    } else {
                        "${maxAlt.roundToInt()}m"
                    }
                    canvas.drawText(peakText, peakPt.first, peakPt.second - 4f, graphLabelSize, "#ef4444", bold = true, anchor = "bottom-center")
                }
            }

            // 8.5. Real-time Distance & Elapsed Time below Elevation Graph
            if (config.showDistanceTime && isValid && allPoints.isNotEmpty()) {
                val startPoint = allPoints.first()
                val currentSeconds = currentRatio.toDouble()
                
                val rawFitDist = telemetry.distance
                val fitElapsedSeconds = telemetry.elapsedSeconds
                
                val videoStartFitTimestamp = telemetry.timestamp - currentSeconds
                val videoStartPoint = allPoints.minByOrNull { kotlin.math.abs(it.timestamp - videoStartFitTimestamp) } ?: startPoint
                val rawVideoDist = maxOf(0.0, telemetry.distance - videoStartPoint.distance)
                val videoElapsedSeconds = maxOf(0.0, currentSeconds).roundToInt()
                
                val fitDistText: String
                val videoDistText: String
                if (config.useImperialUnits) {
                    fitDistText = "${formatTwoDecimals(rawFitDist * 0.000621371)} mi"
                    videoDistText = "${formatTwoDecimals(rawVideoDist * 0.000621371)} mi"
                } else {
                    fitDistText = "${formatTwoDecimals(rawFitDist / 1000.0)} km"
                    videoDistText = "${formatTwoDecimals(rawVideoDist / 1000.0)} km"
                }
                
                fun formatTime(seconds: Int): String {
                    val hh = seconds / 3600
                    val mm = (seconds % 3600) / 60
                    val ss = seconds % 60
                    return if (hh > 0) {
                        "${hh}:${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
                    } else {
                        "${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
                    }
                }
                
                val fitTimeText = formatTime(fitElapsedSeconds)
                val videoTimeText = formatTime(videoElapsedSeconds)
                
                val infoSize = 16f
                val isJa = config.language.lowercase().let { it == "ja" || it.startsWith("ja-") }
                val line = if (isJa) {
                    "距離: $videoDistText   時間: $videoTimeText"
                } else {
                    "Distance: $videoDistText   Time: $videoTimeText"
                }
                
                canvas.drawText(line, cx, eGy + graphH + 16f, infoSize, "#ffffff", bold = true)
                cy = eGy + graphH + 16f + infoSize + itemSpacing
            } else {
                cy = eGy + graphH + itemSpacing
            }
        } else {
            // If elevation is hidden, we can still render Distance & Time info block
            if (config.showDistanceTime && isValid && allPoints.isNotEmpty()) {
                val startPoint = allPoints.first()
                val currentSeconds = currentRatio.toDouble()
                
                val rawFitDist = telemetry.distance
                val fitElapsedSeconds = telemetry.elapsedSeconds
                
                val videoStartFitTimestamp = telemetry.timestamp - currentSeconds
                val videoStartPoint = allPoints.minByOrNull { kotlin.math.abs(it.timestamp - videoStartFitTimestamp) } ?: startPoint
                val rawVideoDist = maxOf(0.0, telemetry.distance - videoStartPoint.distance)
                val videoElapsedSeconds = maxOf(0.0, currentSeconds).roundToInt()
                
                val fitDistText: String
                val videoDistText: String
                if (config.useImperialUnits) {
                    fitDistText = "${formatTwoDecimals(rawFitDist * 0.000621371)} mi"
                    videoDistText = "${formatTwoDecimals(rawVideoDist * 0.000621371)} mi"
                } else {
                    fitDistText = "${formatTwoDecimals(rawFitDist / 1000.0)} km"
                    videoDistText = "${formatTwoDecimals(rawVideoDist / 1000.0)} km"
                }
                
                fun formatTime(seconds: Int): String {
                    val hh = seconds / 3600
                    val mm = (seconds % 3600) / 60
                    val ss = seconds % 60
                    return if (hh > 0) {
                        "${hh}:${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
                    } else {
                        "${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
                    }
                }
                
                val fitTimeText = formatTime(fitElapsedSeconds)
                val videoTimeText = formatTime(videoElapsedSeconds)
                
                val infoSize = 16f
                val isJa = config.language.lowercase().let { it == "ja" || it.startsWith("ja-") }
                val line = if (isJa) {
                    "距離: $videoDistText   時間: $videoTimeText"
                } else {
                    "Distance: $videoDistText   Time: $videoTimeText"
                }
                
                canvas.drawText(line, cx, cy, infoSize, "#ffffff", bold = true)
                cy += infoSize + itemSpacing
            }
        }

        // Draw Road Caption overlay
        val currentSeconds = currentRatio.toDouble()
        val activeCaption = config.roadCaptions.find { 
            it.isEnabled && currentSeconds >= it.startSeconds && currentSeconds <= it.endSeconds 
        }
        if (activeCaption != null && activeCaption.text.isNotEmpty()) {
            val capText = activeCaption.text
            val capSize = 24f
            val capWidth = canvas.getTextWidth(capText, capSize, bold = true)
            val capHeight = capSize
            val padX = 20f
            val padY = 10f
            
            val boxW = capWidth + padX * 2f
            val boxH = capHeight + padY * 2f
            
            val margin = 40f
            val (boxX, boxY) = when (config.captionPosition) {
                "top_right" -> Pair(canvas.width - boxW - margin, margin)
                "top_left" -> {
                    // Avoid overlapping with Date & Time overlay in top-left by placing it immediately to its right.
                    Pair(40f + dtBoxW + 20f, 40f)
                }
                "top_center" -> Pair(canvas.width / 2f - boxW / 2f, margin)
                else -> Pair(canvas.width / 2f - boxW / 2f, canvas.height - boxH - margin) // "bottom_center"
            }
            
            canvas.drawRect(boxX, boxY, boxW, boxH, "#000000", alpha = 0.65f)
            canvas.drawText(capText, boxX + boxW / 2f, boxY + padY + capHeight / 2f, capSize, "#ffffff", bold = true, anchor = "center")
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val absolute = kotlin.math.abs(value)
        val rounded = (absolute * 10).roundToInt()
        val sign = if (value < 0.0) "-" else ""
        return "$sign${rounded / 10}.${rounded % 10}"
    }

    private fun formatTwoDecimals(value: Double): String {
        val absolute = kotlin.math.abs(value)
        val rounded = (absolute * 100).roundToInt()
        val sign = if (value < 0.0) "-" else ""
        val intPart = rounded / 100
        val fracPart = rounded % 100
        val fracStr = if (fracPart < 10) "0$fracPart" else "$fracPart"
        return "$sign$intPart.$fracStr"
    }

    private fun formatGrade(value: Double): String {
        val sign = if (value > 0.0) "+" else ""
        return sign + formatOneDecimal(value)
    }

    private fun formatDateTime(timestamp: Double): String {
        val epochSeconds = 631065600L + timestamp.toLong()
        if (epochSeconds == lastTimestampSeconds) {
            return lastFormattedDateTime
        }
        return try {
            val instant = Instant.fromEpochSeconds(epochSeconds)
            val localDateTime = instant.toLocalDateTime(systemTimeZone)
            val year = localDateTime.year
            val month = localDateTime.monthNumber.toString().padStart(2, '0')
            val day = localDateTime.dayOfMonth.toString().padStart(2, '0')
            val hour = localDateTime.hour.toString().padStart(2, '0')
            val minute = localDateTime.minute.toString().padStart(2, '0')
            val second = localDateTime.second.toString().padStart(2, '0')
            val formatted = "$year-$month-$day $hour:$minute:$second"
            lastTimestampSeconds = epochSeconds
            lastFormattedDateTime = formatted
            formatted
        } catch (e: Exception) {
            "----- --:--:--"
        }
    }

    private fun getLabel(key: String): String {
        val lang = config.language.lowercase()
        val isJa = lang == "ja" || lang.startsWith("ja-" )
        return when (key) {
            "SPEED" -> if (isJa) "スピード" else "SPEED"
            "CADENCE" -> if (isJa) "ケイデンス" else "CADENCE"
            "HEART RATE" -> if (isJa) "心拍数" else "HEART RATE"
            "POWER" -> if (isJa) "パワー" else "POWER"
            "GRADE" -> if (isJa) "斜度" else "GRADE"
            "ELEVATION" -> if (isJa) "標高" else "ELEVATION"
            "POWER TREND" -> if (isJa) "パワートレンド" else "POWER TREND"
            else -> key
        }
    }

    private fun formatMinSec(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    private fun getHrZoneIndex(hr: Double): Int {
        val bpm = hr.roundToInt()
        if (bpm < 130) return -1
        if (bpm >= 190) return 6
        return (bpm - 130) / 10
    }
}
