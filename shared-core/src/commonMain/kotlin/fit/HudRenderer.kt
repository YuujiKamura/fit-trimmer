package fit

import kotlin.math.roundToInt

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
    fun renderFrame(canvas: HudCanvas, telemetry: FitParser.TelemetryPoint, allPoints: List<FitParser.TelemetryPoint>, pBuf: List<Double>, currentRatio: Float) {
        var cy = config.yOffset
        val x = config.xOffset

        cy += drawCell(canvas, x, cy, "SPEED", formatOneDecimal(telemetry.speed), "km/h", "#60A5FA")
        cy += drawCell(canvas, x, cy, "CADENCE", telemetry.cadence.toInt().toString(), "rpm", "#C084FC")
        cy += drawCell(canvas, x, cy, "POWER", telemetry.power.toInt().toString(), "W", "#34D399")
        cy += drawCell(canvas, x, cy, "HEART RATE", telemetry.heartRate.toInt().toString(), "bpm", "#F87171")
        cy += drawCell(canvas, x, cy, "GRADE", formatOneDecimal(telemetry.grade), "%", "#FCD34D")
        cy += drawCell(canvas, x, cy, "W/KG", formatOneDecimal(telemetry.power / 70.0), "w/kg", "#2DD4BF")

        cy += drawPowerGraph(canvas, x, cy, "POWER TREND", pBuf)
        drawElevationGraph(canvas, x, cy, "ELEVATION", allPoints, currentRatio)
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = (value * 10).roundToInt()
        return "${rounded / 10}.${rounded % 10}"
    }

    private fun drawCell(canvas: HudCanvas, x: Float, y: Float, label: String, value: String, unit: String, color: String): Float {
        val lblSize = (config.valSize * 0.45f).coerceAtLeast(10f)
        canvas.drawText(label, x, y, lblSize, "#b4b4b4", bold = true)
        
        val valY = y + lblSize + config.tightness
        canvas.drawText(value, x, valY, config.valSize, "#ffffff", bold = true)
        
        val valW = canvas.getTextWidth(value, config.valSize, true)
        canvas.drawText(unit, x + valW + 6, valY + config.valSize * 0.15f, config.valSize * 0.5f, color, bold = true)
        
        return lblSize + config.tightness + config.valSize + config.spacing
    }
 
    private fun drawPowerGraph(canvas: HudCanvas, x: Float, y: Float, label: String, data: List<Double>): Float {
        val lblSize = (config.valSize * 0.45f).coerceAtLeast(10f)
        canvas.drawText(label, x, y, lblSize, "#b4b4b4", bold = true)
        
        val gy = y + lblSize + (4 * 1.0f) // Keep float type consistency
        val gw = config.graphW
        val gh = config.graphH
        
        canvas.drawRect(x, gy, gw, gh, "#1e2023", alpha = 0.6f, outline = true)
        
        if (data.isNotEmpty()) {
            val maxPoints = 30f
            val bw = gw / maxPoints
            val maxP = maxOf(data.maxOrNull() ?: 250.0, 300.0)
            data.forEachIndexed { i, v ->
                val bh = (v / maxP).toFloat() * gh
                val barX = x + gw - (data.size - i) * bw
                if (barX >= x) {
                    canvas.drawRect(barX, gy + gh - bh, bw - 1f, bh, "#10b981")
                }
            }
        }
        
        return lblSize + 4f + gh + config.spacing
    }

    private fun drawElevationGraph(canvas: HudCanvas, x: Float, y: Float, label: String, allPoints: List<FitParser.TelemetryPoint>, currentRatio: Float) {
        val lblSize = (config.valSize * 0.45f).coerceAtLeast(10f)
        canvas.drawText(label, x, y, lblSize, "#b4b4b4", bold = true)
        
        val gy = y + lblSize + 4
        val gw = config.graphW
        val gh = config.graphH
        
        canvas.drawRect(x, gy, gw, gh, "#1e2023", alpha = 0.6f, outline = true)
        
        if (allPoints.size > 1) {
            val maxV = allPoints.maxOfOrNull { it.elevation } ?: 0.0
            val minV = allPoints.minOfOrNull { it.elevation } ?: 0.0
            val range = maxOf(maxV - minV, 10.0)
            
            val pts = allPoints.mapIndexed { i, p ->
                x + (i.toFloat() / (allPoints.size - 1)) * gw to gy + gh - ((p.elevation - minV) / range).toFloat() * gh
            }
            
            // Draw colored terrain
            for (i in 0 until pts.size - 1) {
                val grade = allPoints[i].grade
                val color = when {
                    grade < -4.0 -> "#3B82F6" // Blue
                    grade < 1.0 -> "#10B981"  // Green
                    grade < 5.0 -> "#FBBF24"  // Yellow
                    grade < 8.0 -> "#EF4444"  // Red
                    else -> "#991B1B"         // Dark Red
                }
                val p1 = pts[i]
                val p2 = pts[i+1]
                canvas.drawPolygon(listOf(
                    p1, p2,
                    p2.first to gy + gh,
                    p1.first to gy + gh
                ), color, alpha = 0.8f)
            }
            
            // Outline line
            canvas.drawLine(pts, "#FFFFFF", 1f, 0.5f)
            
            // Current position pin
            val split = ((allPoints.size - 1) * currentRatio).toInt().coerceIn(0, allPoints.size - 1)
            val currentPt = pts[split]
            val cx = currentPt.first
            val cyPos = currentPt.second
            
            // Vertical cursor line
            canvas.drawLine(listOf(cx to gy, cx to gy + gh), "#FFFFFF", 2f, 0.8f)
            
            // Pin marker
            canvas.drawPolygon(listOf(
                cx - 8 to cyPos - 12,
                cx + 8 to cyPos - 12,
                cx to cyPos
            ), "#EF4444", 1.0f)
        }
    }
}
