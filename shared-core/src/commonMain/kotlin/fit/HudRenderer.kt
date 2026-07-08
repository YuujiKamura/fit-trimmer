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
    val bodyWeightKg: Double = 0.0,
    val customCaptions: List<CustomCaptionSegment> = emptyList(),
    val trimStartSeconds: Double = 0.0,
    val mapSizeScale: Float = 1.0f,
    val mapType: String = "auto",
    val mapPosition: String = "top_right",
    val hudBgAlpha: Float = 0.0f,
    val mapZoomScale: Float = 0.55f,
    val mapZoomOffset: Int = 0,
    val fixMapNorthUp: Boolean = false,
    val mapMarkerSizeScale: Float = 1.0f,
    val mapTextSizeScale: Float = 1.0f,
    val mapRangeMode: String = "full"
)


interface HudCanvas {
    val width: Float
    val height: Float
    fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean = false, anchor: String = "top-left")
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float = 1.0f, outline: Boolean = false, rx: Float = 0f, ry: Float = 0f)
    fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float = 1.0f)
    fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float = 1.0f)
    fun getTextWidth(text: String, size: Float, bold: Boolean): Float
    fun drawMapBackground(
        videoPoints: List<FitParser.TelemetryPoint>,
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
    ) {}
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
        // Dynamic scale factor based on configured valSize (relative to base size 40f)
        val sf = (config.valSize / 40.0).toFloat().coerceAtLeast(0.5f)



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
        
        val labelSize = 16f
        val valSize = config.valSize // 40f
        val unitSize = 18f
        val tightness = config.tightness // 1f
        val itemSpacing = config.spacing // 20f
        val graphW = config.graphW // 300f
        val graphH = config.graphH // 60f



        fun drawCell(label: String, value: String, unit: String, color: String) {
            val actualValSize = if (value.length > 8) {
                (valSize * 0.5f).coerceAtLeast(14f)
            } else {
                valSize
            }
            val valW = canvas.getTextWidth(value, actualValSize, true)
            val unitW = if (unit.isNotEmpty()) canvas.getTextWidth(unit, unitSize, true) else 0f
            val contentW = valW + (if (unit.isNotEmpty()) 8f + unitW else 0f)
            val cellW = maxOf(160f, contentW)
            val hasLabel = label.isNotEmpty()
            val cellH = if (hasLabel) labelSize + tightness + actualValSize else actualValSize
            
            if (config.hudBgAlpha > 0f) {
                val padX = 20f * sf
                val padY = 8f * sf
                canvas.drawRect(cx - padX, cy - padY, cellW + padX * 2f, cellH + padY * 2f, "#000000", alpha = config.hudBgAlpha, rx = 8f * sf, ry = 8f * sf)
            }
            
            // 1. Label (Light grey #e5e7eb) - only drawn if label is not empty
            if (hasLabel) {
                drawShadowedText(canvas, label, cx, cy, labelSize, "#e5e7eb", bold = true, sf = sf)
            }
            
            // 2. Value (White #ffffff)
            val valY = if (hasLabel) cy + labelSize + tightness else cy
            drawShadowedText(canvas, value, cx, valY, actualValSize, "#ffffff", bold = true, sf = sf)
            
            // 3. Unit (Color specified)
            if (unit.isNotEmpty()) {
                val unitX = cx + valW + 8f
                val unitY = valY + (actualValSize - unitSize)
                drawShadowedText(canvas, unit, unitX, unitY, unitSize, "#ffffff", bold = true, sf = sf)
            }
            
            // Increment cy for the next cell
            cy += cellH + itemSpacing
        }

        // 0. DATE & TIME & TEMPERATURE
        if (isValid) {
            val dtText = formatDateTime(telemetry.timestamp)
            val tempVal = telemetry.temperature
            val hasTemp = tempVal != 0.0
            
            if (hasTemp) {
                val isImperial = config.useImperialUnits
                val tempStr = if (isImperial) {
                    val f = (tempVal * 9.0 / 5.0) + 32.0
                    "${f.roundToInt()}°F"
                } else {
                    "${tempVal.roundToInt()}°C"
                }
                val combinedText = "$dtText  $tempStr"
                drawCell("", combinedText, "", "#ffffff")
            } else {
                drawCell("", dtText, "", "#ffffff")
            }
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
            val valW = canvas.getTextWidth(hrStr, valSize, true)
            val cellW = 160f
            if (config.hudBgAlpha > 0f) {
                val padX = 20f * sf
                val padY = 8f * sf
                val cellH = labelSize + tightness + valSize + 39f
                canvas.drawRect(cx - padX, cy - padY, cellW + padX * 2f, cellH + padY * 2f, "#000000", alpha = config.hudBgAlpha, rx = 8f * sf, ry = 8f * sf)
            }
            
            // 3.1. Draw standard HEART RATE label and value
            drawShadowedText(canvas, getLabel("HEART RATE"), cx, cy, labelSize, "#e5e7eb", bold = true, sf = sf)
            val valY = cy + labelSize + tightness
            drawShadowedText(canvas, hrStr, cx, valY, valSize, "#ffffff", bold = true, sf = sf)
            val unitX = cx + valW + 8f
            val unitY = valY + (valSize - unitSize)
            drawShadowedText(canvas, "bpm", unitX, unitY, unitSize, "#ffffff", bold = true, sf = sf)
            
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
                    drawShadowedText(canvas, zoneText, cx, subCy, 12f, "#ffffff", bold = true, sf = sf)
                    
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
                    drawShadowedText(canvas, "ZONE -: --:--", cx, subCy, 12f, "#9ca3af", bold = true, sf = sf)
                    val barY = subCy + 15f
                    canvas.drawRect(cx, barY, 120f, 6f, "#e5e7eb", alpha = 0.15f)
                }
            } else {
                drawShadowedText(canvas, "ZONE -: --:--", cx, subCy, 12f, "#9ca3af", bold = true, sf = sf)
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
            if (config.hudBgAlpha > 0f) {
                val padX = 20f * sf
                val padY = 8f * sf
                val cellH = labelSize + 4f + graphH + ((labelSize * 0.8f) + 4f)
                canvas.drawRect(cx - padX, cy - padY, graphW + padX * 2f, cellH + padY * 2f, "#000000", alpha = config.hudBgAlpha, rx = 8f * sf, ry = 8f * sf)
            }
            val spanText = if (config.powerTrendSpanSeconds >= 60) {
                val min = config.powerTrendSpanSeconds / 60
                val sec = config.powerTrendSpanSeconds % 60
                if (sec > 0) "${min}m ${sec}s" else "${min}m"
            } else {
                "${config.powerTrendSpanSeconds}s"
            }
            drawShadowedText(canvas, "${getLabel("POWER TREND")} ($spanText, 1s)", cx, cy, labelSize, "#e5e7eb", bold = true, sf = sf)
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
                    drawShadowedText(canvas, label, tickX - labelW / 2f, pGy + graphH + 2f, tickLabelSize, "#9ca3af", sf = sf)
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
            if (config.hudBgAlpha > 0f) {
                val padX = 20f * sf
                val padY = 8f * sf
                val cellH = if (config.showDistanceTime) {
                    val infoSize = 16f
                    (infoSize + 16f) + labelSize + 20f + graphH
                } else {
                    labelSize + 20f + graphH
                }
                canvas.drawRect(cx - padX, cy - padY, graphW + padX * 2f, cellH + padY * 2f, "#000000", alpha = config.hudBgAlpha, rx = 8f * sf, ry = 8f * sf)
            }
            // 8.5. Real-time Distance & Elapsed Time ABOVE Elevation Graph
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
                
                drawShadowedText(canvas, line, cx, cy, infoSize, "#ffffff", bold = true, sf = sf)
                cy += infoSize + 16f
            }

            drawShadowedText(canvas, getLabel("ELEVATION"), cx, cy, labelSize, "#e5e7eb", bold = true, sf = sf)
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
                
                val startBearingStr = ""
                val endBearingStr = ""

                val isJa = config.language == "ja"
                val startLabel = if (isJa) "起点 " else "START "
                val endLabel = if (isJa) "終点 " else "END "
                val peakLabel = if (isJa) "最高 " else "MAX "
                val valleyLabel = if (isJa) "最低 " else "MIN "

                val startAlt = elevGraphPoints.first().elevation
                val endAlt = elevGraphPoints.last().elevation
                val startText = startLabel + "▲ " + (if (config.useImperialUnits) {
                    "${(startAlt * 3.28084).roundToInt()}ft"
                } else {
                    "${startAlt.roundToInt()}m"
                }) + startBearingStr
                
                val endText = endLabel + "▲ " + (if (config.useImperialUnits) {
                    "${(endAlt * 3.28084).roundToInt()}ft"
                } else {
                    "${endAlt.roundToInt()}m"
                }) + endBearingStr
                
                val graphLabelSize = 9f
                drawShadowedText(canvas, startText, startPt.first, startPt.second - 4f, graphLabelSize, "#ffffff", bold = true, anchor = "bottom-left", sf = sf)
                drawShadowedText(canvas, endText, endPt.first, endPt.second - 4f, graphLabelSize, "#ffffff", bold = true, anchor = "bottom-right", sf = sf)

                // 8.3. Peak Elevation Marker and Label
                val peakIdx = drawPoints.indexOfFirst { it.elevation == maxAlt }
                val isPeakInMiddle = peakIdx > 0 && peakIdx < drawPoints.size - 1
                if (isPeakInMiddle) {
                    val peakPt = pts[peakIdx]
                    canvas.drawRect(peakPt.first - 2.5f, peakPt.second - 2.5f, 5f, 5f, "#ef4444", alpha = 1.0f)
                    
                    val peakBearingStr = ""

                    val peakText = peakLabel + "▲ " + (if (config.useImperialUnits) {
                        "${(maxAlt * 3.28084).roundToInt()}ft"
                    } else {
                        "${maxAlt.roundToInt()}m"
                    }) + peakBearingStr
                    
                    val peakAnchor = when {
                        peakPt.first - cx < 50f -> "bottom-left"
                        cx + graphW - peakPt.first < 50f -> "bottom-right"
                        else -> "bottom-center"
                    }
                    drawShadowedText(canvas, peakText, peakPt.first, peakPt.second - 4f, graphLabelSize, "#ffffff", bold = true, anchor = peakAnchor, sf = sf)
                }

                // 8.4. Valley (Lowest Elevation) Marker and Label
                val valleyIdx = drawPoints.indexOfFirst { it.elevation == minAlt }
                val isValleyInMiddle = valleyIdx > 0 && valleyIdx < drawPoints.size - 1
                if (isValleyInMiddle) {
                    val valleyPt = pts[valleyIdx]
                    canvas.drawRect(valleyPt.first - 2.5f, valleyPt.second - 2.5f, 5f, 5f, "#3b82f6", alpha = 1.0f)
                    
                    val valleyBearingStr = ""

                    val valleyText = valleyLabel + "▼ " + (if (config.useImperialUnits) {
                        "${(minAlt * 3.28084).roundToInt()}ft"
                    } else {
                        "${minAlt.roundToInt()}m"
                    }) + valleyBearingStr

                    val valleyAnchor = when {
                        valleyPt.first - cx < 50f -> "top-left"
                        cx + graphW - valleyPt.first < 50f -> "top-right"
                        else -> "top-center"
                    }
                    drawShadowedText(canvas, valleyText, valleyPt.first, valleyPt.second + 4f, graphLabelSize, "#ffffff", bold = true, anchor = valleyAnchor, sf = sf)
                }
            }

            // 8.4.5. Mini Route Map in top-right
            drawMiniMap(canvas, videoPoints, telemetry, isValid, sf)

            cy = eGy + graphH + itemSpacing
        } else {
            // If elevation is hidden, we can still render Distance & Time info block
            if (config.showDistanceTime && isValid && allPoints.isNotEmpty()) {
                if (config.hudBgAlpha > 0f) {
                    val padX = 20f * sf
                    val padY = 8f * sf
                    val cellH = 16f
                    canvas.drawRect(cx - padX, cy - padY, graphW + padX * 2f, cellH + padY * 2f, "#000000", alpha = config.hudBgAlpha)
                }
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
                
                drawShadowedText(canvas, line, cx, cy, infoSize, "#ffffff", bold = true, sf = sf)
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
            val padX = 20f
            val padY = 10f
            
            val lines = capText.split("\n")
            val maxLineWidth = lines.maxOfOrNull { canvas.getTextWidth(it, capSize, bold = true) } ?: 0f
            val lineHeight = capSize * 1.25f
            val totalLinesHeight = capSize + (lines.size - 1) * lineHeight
            
            val boxW = maxLineWidth + padX * 2f
            val boxH = totalLinesHeight + padY * 2f
            
            val margin = 40f
            val (boxX, boxY) = when (config.captionPosition) {
                "top_right" -> Pair(canvas.width - boxW - margin, margin)
                "top_left" -> Pair(margin, margin)
                "top_center" -> Pair(canvas.width / 2f - boxW / 2f, margin)
                else -> Pair(canvas.width / 2f - boxW / 2f, canvas.height - boxH - margin) // "bottom_center"
            }
            
            canvas.drawRect(boxX, boxY, boxW, boxH, "#000000", alpha = 0.65f)
            
            lines.forEachIndexed { i, line ->
                val lineY = boxY + padY + i * lineHeight + capSize / 2f
                drawShadowedText(canvas, line, boxX + boxW / 2f, lineY, capSize, "#ffffff", bold = true, anchor = "center", sf = sf)
            }
        }

        config.customCaptions.forEach { segment ->
            val frameTime = if (segment.isAbsoluteTime) {
                currentSeconds + config.trimStartSeconds
            } else {
                currentSeconds
            }
            if (segment.isEnabled && frameTime >= segment.startSeconds && frameTime <= segment.endSeconds && segment.text.isNotEmpty()) {
                val capText = segment.text
                val capSize = segment.fontSize
                val padX = 20f
                val padY = 10f
                
                val lines = capText.split("\n")
                val maxLineWidth = lines.maxOfOrNull { canvas.getTextWidth(it, capSize, bold = true) } ?: 0f
                val lineHeight = capSize * 1.25f
                val totalLinesHeight = capSize + (lines.size - 1) * lineHeight
                
                val boxW = maxLineWidth + padX * 2f
                val boxH = totalLinesHeight + padY * 2f
                
                val x = segment.positionX * canvas.width
                val y = segment.positionY * canvas.height
                
                val boxX = when (segment.align.lowercase()) {
                    "left" -> x
                    "right" -> x - boxW
                    else -> x - boxW / 2f // "center"
                }
                val boxY = y - boxH / 2f
                
                canvas.drawRect(boxX, boxY, boxW, boxH, segment.backgroundColor, alpha = segment.backgroundAlpha)
                
                lines.forEachIndexed { i, line ->
                    val lineY = boxY + padY + i * lineHeight + capSize / 2f
                    drawShadowedText(
                        canvas,
                        line, 
                        boxX + boxW / 2f, 
                        lineY, 
                        capSize, 
                        segment.textColor, 
                        bold = true, 
                        anchor = "center",
                        sf = sf
                    )
                }
            }
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
            "DATE/TIME" -> if (isJa) "日時" else "DATE/TIME"
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

    private fun calculateBearing(p1: FitParser.TelemetryPoint, p2: FitParser.TelemetryPoint): Double? {
        if (p1.lat == 0.0 && p1.lon == 0.0) return null
        if (p2.lat == 0.0 && p2.lon == 0.0) return null
        val lat1 = p1.lat
        val lon1 = p1.lon
        val lat2 = p2.lat
        val lon2 = p2.lon
        if (kotlin.math.abs(lat1 - lat2) < 1e-7 && kotlin.math.abs(lon1 - lon2) < 1e-7) return null
        
        val phi1 = lat1 * kotlin.math.PI / 180.0
        val phi2 = lat2 * kotlin.math.PI / 180.0
        val deltaLambda = (lon2 - lon1) * kotlin.math.PI / 180.0

        val y = kotlin.math.sin(deltaLambda) * kotlin.math.cos(phi2)
        val x = kotlin.math.cos(phi1) * kotlin.math.sin(phi2) - kotlin.math.sin(phi1) * kotlin.math.cos(phi2) * kotlin.math.cos(deltaLambda)
        val bearingRad = kotlin.math.atan2(y, x)
        return (bearingRad * 180.0 / kotlin.math.PI + 360.0) % 360.0
    }

    private fun bearingAtDistance(points: List<FitParser.TelemetryPoint>, distance: Double, fallback: Double): Double {
        if (points.size < 2) return fallback
        // Binary search for the segment that brackets the current distance
        var lo = 0
        var hi = points.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (points[mid].distance <= distance) lo = mid else hi = mid
        }
        return calculateBearing(points[lo], points[hi]) ?: fallback
    }


    private fun get16Direction(bearing: Double): String {
        val directions16 = arrayOf(
            "N", "NNE", "NE", "ENE",
            "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW",
            "W", "WNW", "NW", "NNW"
        )
        val index = (((bearing + 11.25) / 22.5).toInt()) % 16
        return directions16[index]
    }

    private fun drawMiniMap(
        canvas: HudCanvas,
        videoPoints: List<FitParser.TelemetryPoint>,
        telemetry: FitParser.TelemetryPoint,
        isValid: Boolean,
        sf: Float
    ) {
        // Filter out invalid GPS coordinates (0.0, 0.0)
        val baseRoutePoints = videoPoints.filter { it.lat != 0.0 || it.lon != 0.0 }
        if (baseRoutePoints.size < 2) return

        val highestPt = baseRoutePoints.maxByOrNull { it.elevation }
        val isBeforeHighest = highestPt != null && telemetry.timestamp <= highestPt.timestamp

        val validRoutePoints = if (config.mapRangeMode == "highest_elevation" && isBeforeHighest) {
            val idx = baseRoutePoints.indexOf(highestPt!!)
            if (idx >= 1) {
                baseRoutePoints.subList(0, idx + 1)
            } else {
                baseRoutePoints
            }
        } else {
            baseRoutePoints
        }

        // 1. Layout parameters (Scaled & Enlarged for high fidelity)
        val R = 110f * config.mapSizeScale * sf // 円の半径 (R) - スライダー可変対応
        val marginX = 45f * sf
        val marginY = 40f * sf
        val mcx = canvas.width - marginX - R // 円の中心 X
        val mcy = if (config.mapPosition == "bottom_right") {
            canvas.height - marginY - R
        } else {
            marginY + R // 円の中心 Y
        }


        // 2. Draw black semi-transparent circle background (32-sided polygon)
        val circlePoints = (0..32).map { i ->
            val angle = i * 2.0 * kotlin.math.PI / 32.0
            val px = mcx + R * kotlin.math.cos(angle).toFloat()
            val py = mcy + R * kotlin.math.sin(angle).toFloat()
            px to py
        }
        canvas.drawPolygon(circlePoints, "#000000", alpha = 0.5f)

        // 3. Coordinate alignment (Path-up projection)
        val startPt = validRoutePoints.first()
        val endPt = validRoutePoints.last()

        // Calculate aspect ratio correction cos(lat)
        val meanLat = (startPt.lat + endPt.lat) / 2.0
        val cosLat = kotlin.math.cos(meanLat * kotlin.math.PI / 180.0)

        // Path direction vector (start -> end) corrected by cosLat
        val dx = (endPt.lon - startPt.lon) * cosLat
        val dy = endPt.lat - startPt.lat
        val L = kotlin.math.sqrt(dx * dx + dy * dy)
        val L_proj = if (config.fixMapNorthUp) 0.0 else L
        
        // Target path length on the map is 2 * padR to leave padding inside circle
        val padR = R * config.mapZoomScale

        // Heading angle (Start to End) in degrees for compass rotation
        val pathBearing = calculateBearing(startPt, endPt) ?: 0.0
        val effectiveBearing = if (config.fixMapNorthUp) 0.0 else pathBearing

        // 1. Project all points onto local heading-up plane and calculate bounding box
        // lx: Rightward component (orthogonal to heading)
        // ly: Upward component (along heading, negated for screen Y coordinates)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val localCoords = validRoutePoints.map { pt ->
            val px = (pt.lon - startPt.lon) * cosLat
            val py = pt.lat - startPt.lat
            
            val lx = if (L_proj > 1e-7) (px * dy - py * dx) / L_proj else px
            val ly = if (L_proj > 1e-7) -(px * dx + py * dy) / L_proj else -py
            
            if (lx < minX) minX = lx
            if (lx > maxX) maxX = lx
            if (ly < minY) minY = ly
            if (ly > maxY) maxY = ly
            lx to ly
        }

        val Wl = maxX - minX
        val Hl = maxY - minY

        // 3. Compute fitting scale factor maintaining 1:1 aspect ratio
        val scaleX = if (Wl > 1e-7) (2.0 * padR) / Wl else Double.MAX_VALUE
        val scaleY = if (Hl > 1e-7) (2.0 * padR) / Hl else Double.MAX_VALUE
        val dynamicScale = minOf(scaleX, scaleY).takeIf { it != Double.MAX_VALUE && it > 0.0 } ?: 1.0

        // 4. Center coordinates inside bounding box
        val cxL = (minX + maxX) / 2.0
        val cyL = (minY + maxY) / 2.0

        if (isValid && validRoutePoints.isNotEmpty()) {
            canvas.drawMapBackground(
                videoPoints = validRoutePoints,
                mcx = mcx,
                mcy = mcy,
                R = R,
                padR = padR,
                sf = sf,
                pathBearing = effectiveBearing,
                cosLat = cosLat,
                dx = dx,
                dy = dy,
                L = L,
                cxL = cxL,
                cyL = cyL,
                dynamicScale = dynamicScale
            )
        }
        canvas.drawLine(circlePoints, "#ffffff", width = 1.8f * sf, alpha = 0.7f)

        // 5. Helper to project TelemetryPoint onto the screen canvas (applying dot-product projection)
        fun projectPoint(pt: FitParser.TelemetryPoint): Pair<Float, Float> {
            val px = (pt.lon - startPt.lon) * cosLat
            val py = pt.lat - startPt.lat
            
            val lx = if (L_proj > 1e-7) (px * dy - py * dx) / L_proj else px
            val ly = if (L_proj > 1e-7) -(px * dx + py * dy) / L_proj else -py
            
            val lxScaled = ((lx - cxL) * dynamicScale).toFloat()
            val lyScaled = ((ly - cyL) * dynamicScale).toFloat()
            
            // Clamp to circle boundary to prevent visual overflow
            val d = kotlin.math.sqrt(lxScaled * lxScaled + lyScaled * lyScaled)
            val limit = R - 4f * sf
            return if (d > limit) {
                val clampedLx = lxScaled * (limit / d)
                val clampedLy = lyScaled * (limit / d)
                (mcx + clampedLx) to (mcy + clampedLy)
            } else {
                (mcx + lxScaled) to (mcy + lyScaled)
            }
        }

        // 4. Downsample route points for drawing performance (max 100 points)
        val maxMapPoints = 100
        val drawPoints = if (validRoutePoints.size <= maxMapPoints) {
            validRoutePoints
        } else {
            val step = (validRoutePoints.size - 1).toFloat() / (maxMapPoints - 1)
            (0 until maxMapPoints).map { i ->
                val index = (i * step).roundToInt().coerceIn(validRoutePoints.indices)
                validRoutePoints[index]
            }
        }

        // Draw route line (with scaled line width and black outline shadow)
        val routeLinePoints = drawPoints.map { projectPoint(it) }
        canvas.drawLine(routeLinePoints, "#ffffff", width = 4.2f * sf, alpha = 0.8f)
        canvas.drawLine(routeLinePoints, "#007AFF", width = 2.8f * sf, alpha = 0.9f)

        // 5. Draw Start/End Markers (Scaled with black outline shadow)
        val startMapPt = projectPoint(startPt)
        val endMapPt = projectPoint(endPt)
        val mSize = 8f * sf * config.mapMarkerSizeScale
        val hmSize = mSize / 2f
        val outSize = mSize + 2f * sf
        val houtSize = outSize / 2f
        
        canvas.drawRect(startMapPt.first - houtSize, startMapPt.second - houtSize, outSize, outSize, "#555555", alpha = 0.8f)
        canvas.drawRect(startMapPt.first - hmSize, startMapPt.second - hmSize, mSize, mSize, "#00e676", alpha = 1.0f)
        
        canvas.drawRect(endMapPt.first - houtSize, endMapPt.second - houtSize, outSize, outSize, "#555555", alpha = 0.8f)
        canvas.drawRect(endMapPt.first - hmSize, endMapPt.second - hmSize, mSize, mSize, "#ef4444", alpha = 1.0f)

        // Draw distance labels near start/end & midpoint (Scaled font size & adjusted spacing, Metric is in meters 'm')
        val totalDist = endPt.distance - startPt.distance
        val totalDistText = if (config.useImperialUnits) {
            "${formatTwoDecimals(totalDist * 0.000621371)} mi"
        } else {
            "${formatTwoDecimals(totalDist / 1000.0)} km"
        }
        val startDistText = if (config.useImperialUnits) "0.00 mi" else "0.00 km"
        val distTextSize = 10.5f * sf * config.mapTextSizeScale
        
        // Midpoint calculation
        val midIdx = validRoutePoints.size / 2
        val midPt = validRoutePoints[midIdx]
        val midMapPt = projectPoint(midPt)
        val midDist = midPt.distance - startPt.distance
        val midDistText = if (config.useImperialUnits) {
            "${formatTwoDecimals(midDist * 0.000621371)} mi"
        } else {
            "${formatTwoDecimals(midDist / 1000.0)} km"
        }
        
        // Offset midpoint text away from the circle center to prevent overlapping
        val midDx = midMapPt.first - mcx
        val midDy = midMapPt.second - mcy
        val midAngle = kotlin.math.atan2(midDy, midDx)
        val midOffset = 8f * sf
        val midTx = midMapPt.first + midOffset * kotlin.math.cos(midAngle).toFloat()
        val midTy = midMapPt.second + midOffset * kotlin.math.sin(midAngle).toFloat()
        val midAnchor = when {
            kotlin.math.abs(kotlin.math.cos(midAngle)) > 0.707 -> if (midDx > 0) "left-center" else "right-center"
            else -> if (midDy > 0) "top-center" else "bottom-center"
        }
        
        drawShadowedText(canvas, startDistText, startMapPt.first, startMapPt.second + 4f * sf, distTextSize, "#ffffff", bold = true, anchor = "top-center", sf = sf)
        drawShadowedText(canvas, totalDistText, endMapPt.first, endMapPt.second - (distTextSize + 4f * sf), distTextSize, "#ffffff", bold = true, anchor = "bottom-center", sf = sf)
        drawShadowedText(canvas, midDistText, midTx, midTy, distTextSize, "#ffffff", bold = true, anchor = midAnchor, sf = sf)

        // 6. Draw Arrowhead Pin for Current Location
        if (isValid && telemetry.lat != 0.0 && telemetry.lon != 0.0) {
            val currentMapPt = projectPoint(telemetry)
            
            val currentBearing = bearingAtDistance(validRoutePoints, telemetry.distance, effectiveBearing)
            val phi = (currentBearing - effectiveBearing) * kotlin.math.PI / 180.0

            val sinPhi = kotlin.math.sin(phi).toFloat()
            val cosPhi = kotlin.math.cos(phi).toFloat()
            
            // Arrowhead dimensions (scaled)
            val L1 = 11f * sf * config.mapMarkerSizeScale  // Tip length (forward)
            val L2 = 9f * sf * config.mapMarkerSizeScale   // Rear wings length
            val L3 = 3.5f * sf * config.mapMarkerSizeScale // Rear inner indentation length
            
            // Tip point (pointing forward)
            val p1 = currentMapPt.first + L1 * sinPhi to currentMapPt.second - L1 * cosPhi
            
            // Left wing point (135 degrees counter-clockwise from heading)
            val angleLeft = phi - (135.0 * kotlin.math.PI / 180.0)
            val p2 = currentMapPt.first + L2 * kotlin.math.sin(angleLeft).toFloat() to currentMapPt.second - L2 * kotlin.math.cos(angleLeft).toFloat()
            
            // Inner indent point (180 degrees from heading)
            val p3 = currentMapPt.first - L3 * sinPhi to currentMapPt.second + L3 * cosPhi
            
            // Right wing point (135 degrees clockwise from heading)
            val angleRight = phi + (135.0 * kotlin.math.PI / 180.0)
            val p4 = currentMapPt.first + L2 * kotlin.math.sin(angleRight).toFloat() to currentMapPt.second - L2 * kotlin.math.cos(angleRight).toFloat()
            
            // Draw white shadow circle underneath the arrowhead marker
            val shR = L1 + 3f * sf
            val shSize = shR * 2f
            canvas.drawRect(
                x = currentMapPt.first - shR,
                y = currentMapPt.second - shR,
                w = shSize,
                h = shSize,
                color = "#ffffff",
                alpha = 1.0f,
                rx = shR,
                ry = shR
            )

            val L1_out = L1 + 2f * sf
            val L2_out = L2 + 2f * sf
            val L3_out = L3 + 2f * sf
            val p1_out = currentMapPt.first + L1_out * sinPhi to currentMapPt.second - L1_out * cosPhi
            val p2_out = currentMapPt.first + L2_out * kotlin.math.sin(angleLeft).toFloat() to currentMapPt.second - L2_out * kotlin.math.cos(angleLeft).toFloat()
            val p3_out = currentMapPt.first - L3_out * sinPhi to currentMapPt.second + L3_out * cosPhi
            val p4_out = currentMapPt.first + L2_out * kotlin.math.sin(angleRight).toFloat() to currentMapPt.second - L2_out * kotlin.math.cos(angleRight).toFloat()
            canvas.drawPolygon(listOf(p1_out, p2_out, p3_out, p4_out), "#555555", alpha = 0.8f)

            val pinPoly = listOf(p1, p2, p3, p4)
            canvas.drawPolygon(pinPoly, "#ef4444", alpha = 1.0f)
        }

        // 7. Draw 4 Directions (N, E, S, W) along the inner circle margin (Scaled & centered alignment)
        val angleN = -90.0 - effectiveBearing
        val compassPoints = mapOf(
            "N" to angleN,
            "E" to (angleN + 90.0),
            "S" to (angleN + 180.0),
            "W" to (angleN + 270.0)
        )
        
        val compR = R + 15f * sf // Position compass labels strictly OUTSIDE the circle boundary (R + 15f)
        val compassTextSize = 14f * sf * config.mapTextSizeScale
        for ((label, angleDeg) in compassPoints) {
            val angleRad = angleDeg * kotlin.math.PI / 180.0
            val tx = mcx + compR * kotlin.math.cos(angleRad).toFloat()
            val ty = mcy + compR * kotlin.math.sin(angleRad).toFloat()
            drawShadowedText(canvas, label, tx, ty, compassTextSize, "#ffffff", bold = true, anchor = "center", sf = sf)
        }
    }

    private fun drawShadowedText(
        canvas: HudCanvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: String,
        bold: Boolean = false,
        anchor: String = "left-center",
        sf: Float
    ) {
        val offset = 1.2f * sf
        canvas.drawText(text, x - offset, y - offset, size, "#555555", bold = bold, anchor = anchor)
        canvas.drawText(text, x + offset, y - offset, size, "#555555", bold = bold, anchor = anchor)
        canvas.drawText(text, x - offset, y + offset, size, "#555555", bold = bold, anchor = anchor)
        canvas.drawText(text, x + offset, y + offset, size, "#555555", bold = bold, anchor = anchor)
        canvas.drawText(text, x, y, size, color, bold = bold, anchor = anchor)
    }

}
// Hotreload forced trigger
