package fit

import kotlin.math.roundToInt
import kotlin.math.max

data class HudConfig(
    val valSize: Float,
    val tightness: Float,
    val spacing: Float,
    val xOffset: Float,
    val yOffset: Float,
    val graphH: Float,
    val graphW: Float
)

interface HudCanvas {
    fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean = false, anchor: String = "top-left")
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float = 1.0f, outline: Boolean = false)
    fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float = 1.0f)
    fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float = 1.0f)
    fun getTextWidth(text: String, size: Float, bold: Boolean): Float
}

class HudRenderer(val config: HudConfig) {
    fun renderFrame(
        canvas: HudCanvas, 
        telemetry: FitParser.TelemetryPoint, 
        originalPoints: List<FitParser.TelemetryPoint>, 
        pBuf: List<Double>, 
        currentRatio: Float
    ) {
        val allPoints = if (originalPoints.isEmpty()) listOf(telemetry) else originalPoints
        
        var cx = config.xOffset
        var cy = config.yOffset
        
        val labelSize = 11f
        val valSize = 40f
        val unitSize = 14f
        val tightness = config.tightness // 1f
        val itemSpacing = config.spacing // 20f
        val graphW = config.graphW // 300f
        val graphH = config.graphH // 60f

        fun drawCell(label: String, value: String, unit: String, color: String) {
            // 1. Label (Grey #a0a0a0)
            canvas.drawText(label, cx, cy, labelSize, "#a0a0a0", bold = true)
            
            // 2. Value (White #ffffff)
            val valY = cy + labelSize + tightness
            canvas.drawText(value, cx, valY, valSize, "#ffffff", bold = true)
            
            // 3. Unit (Color specified)
            val valW = canvas.getTextWidth(value, valSize, true)
            val unitX = cx + valW + 6f
            val unitY = valY + (valSize - unitSize)
            canvas.drawText(unit, unitX, unitY, unitSize, color, bold = true)
            
            // Increment cy for the next cell
            cy += labelSize + tightness + valSize + itemSpacing
        }

        // 1. SPEED
        val spdStr = formatOneDecimal(telemetry.speed)
        drawCell("SPEED", spdStr, "km/h", "#3b82f6")

        // 2. CADENCE
        val cadStr = telemetry.cadence.roundToInt().toString()
        drawCell("CADENCE", cadStr, "rpm", "#a78bfa")

        // 3. POWER
        val pwrStr = telemetry.power.roundToInt().toString()
        drawCell("POWER", pwrStr, "W", "#10b981")

        // 4. HEART RATE
        val hrStr = telemetry.heartRate.roundToInt().toString()
        drawCell("HEART RATE", hrStr, "bpm", "#ef4444")

        // 5. GRADE
        val grdStr = formatGrade(telemetry.grade)
        drawCell("GRADE", grdStr, "%", "#fbbf24")

        // 6. W/KG
        val wkgVal = telemetry.power / 83.3
        val wkgStr = formatOneDecimal(wkgVal)
        drawCell("W/KG", wkgStr, "w/kg", "#2dd4bf")

        // 7. POWER TREND (Bar graph)
        canvas.drawText("POWER TREND", cx, cy, labelSize, "#a0a0a0", bold = true)
        val pGy = cy + labelSize + 4f
        
        if (pBuf.isNotEmpty()) {
            val maxPoints = 30f
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
        cy += labelSize + 4f + graphH + itemSpacing

        // 8. ELEVATION (Line graph with terrain and pin)
        canvas.drawText("ELEVATION", cx, cy, labelSize, "#a0a0a0", bold = true)
        val eGy = cy + labelSize + 4f
        
        if (allPoints.size > 1) {
            val altitudes = allPoints.map { it.elevation }
            var minAlt = altitudes.firstOrNull() ?: 0.0
            var maxAlt = altitudes.firstOrNull() ?: 100.0
            for (alt in altitudes) {
                if (alt < minAlt) minAlt = alt
                if (alt > maxAlt) maxAlt = alt
            }
            val altDiff = max(maxAlt - minAlt, 10.0)

            val pts = allPoints.mapIndexed { idx, pt ->
                val px = cx + (idx.toFloat() / (allPoints.size - 1)) * graphW
                val py = (eGy + graphH - ((pt.elevation - minAlt) / altDiff) * graphH).toFloat()
                px to py
            }

            // Draw colored terrain polygon segments
            for (i in 0 until pts.size - 1) {
                val grade = allPoints[i].grade
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
            var currentIdx = allPoints.indexOfFirst { it.timestamp >= telemetry.timestamp }
            if (currentIdx == -1) {
                currentIdx = (allPoints.size * currentRatio).toInt().coerceIn(0, allPoints.size - 1)
            }
            if (currentIdx in pts.indices) {
                val currX = pts[currentIdx].first
                val currY = pts[currentIdx].second
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
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val absolute = kotlin.math.abs(value)
        val rounded = (absolute * 10).roundToInt()
        val sign = if (value < 0.0) "-" else ""
        return "$sign${rounded / 10}.${rounded % 10}"
    }

    private fun formatGrade(value: Double): String {
        val sign = if (value > 0.0) "+" else ""
        return sign + formatOneDecimal(value)
    }
}
