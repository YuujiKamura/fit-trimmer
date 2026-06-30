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
    val language: String = ""
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
    // Cache fields for datetime formatting L1 cache to avoid massive allocations
    private var lastTimestampSeconds = -1L
    private var lastFormattedDateTime = "----- --:--:--"
    private val systemTimeZone by lazy { TimeZone.currentSystemDefault() }

    // Cache fields for elevation points to avoid recalculation/reallocation every frame
    private var cachedOriginalPoints: List<FitParser.TelemetryPoint>? = null
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
        val speedVal = if (config.useImperialUnits) telemetry.speed * 0.621371 else telemetry.speed
        val speedUnit = if (config.useImperialUnits) "mph" else "km/h"
        val spdStr = if (isValid) formatOneDecimal(speedVal) else "-"
        drawCell(getLabel("SPEED"), spdStr, speedUnit, "#3b82f6")

        // 2. CADENCE
        val cadStr = if (isValid) telemetry.cadence.roundToInt().toString() else "-"
        drawCell(getLabel("CADENCE"), cadStr, "rpm", "#a78bfa")

        // 3. HEART RATE
        val hrStr = if (isValid) telemetry.heartRate.roundToInt().toString() else "-"
        drawCell(getLabel("HEART RATE"), hrStr, "bpm", "#ef4444")

        // 4. POWER
        val pwrStr = if (isValid) telemetry.power.roundToInt().toString() else "-"
        drawCell(getLabel("POWER"), pwrStr, "W", "#10b981")

        // 5. W/KG
        val wkgVal = telemetry.power / 83.3
        val wkgStr = if (isValid) formatOneDecimal(wkgVal) else "-"
        drawCell("W/KG", wkgStr, "w/kg", "#2dd4bf")

        // 6. POWER TREND (Bar graph)
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

        // 7. GRADE
        val grdStr = if (isValid) formatGrade(telemetry.grade) else "-"
        drawCell(getLabel("GRADE"), grdStr, "%", "#fbbf24")

        // 8. ELEVATION (Line graph with terrain and pin)
        canvas.drawText(getLabel("ELEVATION"), cx, cy, labelSize, "#e5e7eb", bold = true)
        val eGy = cy + labelSize + 4f
        
        if (allPoints.size > 1) {
            val pts: List<Pair<Float, Float>>
            val drawPoints: List<FitParser.TelemetryPoint>
            var minAlt = 0.0
            var maxAlt = 100.0
            var altDiff = 10.0

            if (allPoints === cachedOriginalPoints &&
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
                val altitudes = allPoints.map { it.elevation }
                minAlt = altitudes.firstOrNull() ?: 0.0
                maxAlt = altitudes.firstOrNull() ?: 100.0
                for (alt in altitudes) {
                    if (alt < minAlt) minAlt = alt
                    if (alt > maxAlt) maxAlt = alt
                }
                altDiff = max(maxAlt - minAlt, 10.0)

                // Downsample to max 150 points for rendering performance
                val maxRenderPoints = 150
                if (allPoints.size <= maxRenderPoints) {
                    drawPoints = allPoints
                } else {
                    val step = (allPoints.size - 1).toFloat() / (maxRenderPoints - 1)
                    drawPoints = (0 until maxRenderPoints).map { i ->
                        val index = (i * step).roundToInt().coerceIn(allPoints.indices)
                        allPoints[index]
                    }
                }

                pts = drawPoints.mapIndexed { idx, pt ->
                    val px = cx + (idx.toFloat() / (drawPoints.size - 1)) * graphW
                    val py = (eGy + graphH - ((pt.elevation - minAlt) / altDiff) * graphH).toFloat()
                    px to py
                }
                
                cachedOriginalPoints = allPoints
                cachedCx = cx
                cachedEGy = eGy
                cachedGraphW = graphW
                cachedGraphH = graphH
                cachedPts = pts
                cachedDrawPoints = drawPoints
                cachedMinAlt = minAlt
                cachedMaxAlt = maxAlt
                cachedAltDiff = altDiff
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

            // Current position marker pin and vertical guide line
            if (isValid) {
                var currentIdx = allPoints.binarySearch {
                    it.timestamp.compareTo(telemetry.timestamp)
                }
                if (currentIdx < 0) {
                    currentIdx = -currentIdx - 1
                }
                currentIdx = currentIdx.coerceIn(allPoints.indices)

                val progress = currentIdx.toFloat() / (allPoints.size - 1)
                val currX = cx + progress * graphW
                val currY = (eGy + graphH - ((telemetry.elevation - minAlt) / altDiff) * graphH).toFloat()

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
            val startAlt = allPoints.first().elevation
            val endAlt = allPoints.last().elevation
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
        }

        // 8.5. Real-time Distance & Elapsed Time below Elevation Graph
        if (isValid && allPoints.isNotEmpty()) {
            val startPoint = allPoints.first()
            val currentSeconds = currentRatio.toDouble()
            
            // 1. TRIP (FIT Activity Total) - Directly read pre-computed fields
            val rawFitDist = telemetry.distance
            val fitElapsedSeconds = telemetry.elapsedSeconds
            
            // 2. CLIP (Video Trim Segment Total) - Calculate relative to video start
            val videoStartFitTimestamp = telemetry.timestamp - currentSeconds
            val videoStartPoint = allPoints.minByOrNull { kotlin.math.abs(it.timestamp - videoStartFitTimestamp) } ?: startPoint
            val rawVideoDist = maxOf(0.0, telemetry.distance - videoStartPoint.distance)
            val videoElapsedSeconds = maxOf(0.0, currentSeconds).roundToInt()
            
            // Format Distance
            val fitDistText: String
            val videoDistText: String
            if (config.useImperialUnits) {
                fitDistText = "${formatTwoDecimals(rawFitDist * 0.000621371)} mi"
                videoDistText = "${formatTwoDecimals(rawVideoDist * 0.000621371)} mi"
            } else {
                fitDistText = "${formatTwoDecimals(rawFitDist / 1000.0)} km"
                videoDistText = "${formatTwoDecimals(rawVideoDist / 1000.0)} km"
            }
            
            // Format Time Helper
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
            val line1 = if (isJa) {
                "全体走行距離: $fitDistText   全体経過時間: $fitTimeText"
            } else {
                "Total Distance: $fitDistText   Total Elapsed: $fitTimeText"
            }
            val line2 = if (isJa) {
                "区間走行距離: $videoDistText   区間経過時間: $videoTimeText"
            } else {
                "Split Distance: $videoDistText   Split Elapsed: $videoTimeText"
            }
            
            // Draw immediately below the elevation graph box in two lines
            canvas.drawText(line1, cx, eGy + graphH + 16f, infoSize, "#ffffff", bold = true)
            canvas.drawText(line2, cx, eGy + graphH + 34f, infoSize, "#ffffff", bold = true)
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
}
