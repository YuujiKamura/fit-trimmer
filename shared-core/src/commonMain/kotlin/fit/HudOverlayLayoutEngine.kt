package fit

import kotlin.math.roundToInt

data class MetricLayout(
    val labelText: String,
    val valueText: String,
    val unitText: String,
    val colorHex: String,
    val isVisible: Boolean,
    
    // レイアウト配置された座標
    val x: Float,
    val y: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val actualValSize: Float,
    
    // 描画サポート用プロパティ
    val valWidth: Float,
    val unitWidth: Float
)

data class HeartRateLayout(
    val metric: MetricLayout,
    val showZoneBar: Boolean,
    val zoneLabel: String,
    val currentSec: Int,
    val totalSec: Int,
    val zoneColor: String,
    
    // Zoneバー用のレイアウト座標
    val barX: Float,
    val barY: Float,
    val barW: Float,
    val barH: Float
)

data class HudOverlayLayout(
    val dateDisplay: MetricLayout?,
    val speed: MetricLayout?,
    val cadence: MetricLayout?,
    val heartRate: HeartRateLayout?,
    val power: MetricLayout?,
    val wkg: MetricLayout?,
    val grade: MetricLayout?,
    val finalCy: Float,
    val isReady: Boolean
)

class HudOverlayLayoutEngine {

    // 定数の定義
    private val labelSize = 16f
    private val unitSize = 18f

    fun calculateLayout(
        config: HudConfig,
        point: TelemetryPoint?,
        isValid: Boolean,
        sf: Float,
        // 心拍ゾーンの統計情報
        zonesCurrent: IntArray,
        cachedZonesTotal: IntArray?,
        // テキスト幅測定用ラムダ
        getTextWidth: (text: String, size: Float, bold: Boolean) -> Float,
        // 日時、値フォーマッタ用ラムダ（Main側の依存を逃がす）
        formatDateTime: (Double) -> String,
        formatOneDecimal: (Double) -> String,
        formatGrade: (Double) -> String,
        getLabel: (String) -> String
    ): HudOverlayLayout {
        if (point == null) {
            return HudOverlayLayout(
                dateDisplay = null,
                speed = null,
                cadence = null,
                heartRate = null,
                power = null,
                wkg = null,
                grade = null,
                finalCy = config.yOffset,
                isReady = false
            )
        }

        val useImperial = config.useImperialUnits
        val bodyWeight = config.bodyWeightKg

        var cx = config.xOffset
        var cy = config.yOffset
        val valSize = config.valSize
        val tightness = config.tightness
        val itemSpacing = config.spacing

        // ヘルパー：一般的なセルのレイアウト座標決定ロジック
        fun layoutCell(label: String, value: String, unit: String, color: String, isVisible: Boolean): MetricLayout {
            if (!isVisible) {
                return MetricLayout("", value, unit, color, false, cx, cy, 0f, 0f, valSize, 0f, 0f)
            }

            val actualValSize = if (value.length > 8) {
                (valSize * 0.5f).coerceAtLeast(14f)
            } else {
                valSize
            }
            val valW = getTextWidth(value, actualValSize, true)
            val unitW = if (unit.isNotEmpty()) getTextWidth(unit, unitSize, true) else 0f
            val contentW = valW + (if (unit.isNotEmpty()) 8f + unitW else 0f)
            val cellW = maxOf(160f, contentW)
            val hasLabel = label.isNotEmpty()
            val cellH = if (hasLabel) labelSize + tightness + actualValSize else actualValSize

            val layout = MetricLayout(
                labelText = label,
                valueText = value,
                unitText = unit,
                colorHex = color,
                isVisible = true,
                x = cx,
                y = cy,
                cellWidth = cellW,
                cellHeight = cellH,
                actualValSize = actualValSize,
                valWidth = valW,
                unitWidth = unitW
            )

            // 次のセルのために基準Y座標を進める
            cy += cellH + itemSpacing
            return layout
        }

        // 0. DATE & TIME & TEMPERATURE
        val dateDisplay = run {
            if (isValid) {
                val dtText = formatDateTime(point.timestamp)
                val tempVal = point.temperature
                val hasTemp = tempVal != 0.0
                val combinedText = if (hasTemp) {
                    val tempStr = if (config.useImperialUnits) {
                        val f = (tempVal * 9.0 / 5.0) + 32.0
                        "${f.roundToInt()}°F"
                    } else {
                        "${tempVal.roundToInt()}°C"
                    }
                    "$dtText  $tempStr"
                } else {
                    dtText
                }
                layoutCell("", combinedText, "", "#ffffff", isVisible = true)
            } else {
                null
            }
        }

        // 1. SPEED
        val speed = if (config.showSpeed) {
            val speedVal = if (config.useImperialUnits) point.speed * 0.621371 else point.speed
            val speedUnit = if (config.useImperialUnits) "mph" else "km/h"
            val spdStr = if (isValid) formatOneDecimal(speedVal) else "-"
            layoutCell(getLabel("SPEED"), spdStr, speedUnit, "#3b82f6", isVisible = true)
        } else null

        // 2. CADENCE
        val cadence = if (config.showCadence) {
            val cadStr = if (isValid) point.cadence.toInt().toString() else "-"
            layoutCell(getLabel("CADENCE"), cadStr, "rpm", "#a78bfa", isVisible = true)
        } else null

        // 3. HEART RATE
        val heartRate = if (config.showHeartRate) {
            val hrStr = if (isValid) point.heartRate.toInt().toString() else "-"
            val valW = getTextWidth(hrStr, valSize, true)
            val cellW = 160f
            val cellH = labelSize + tightness + valSize + 39f // サブバーの高さを含む
            
            val valY = cy + labelSize + tightness
            
            // Sub zone bar calculations
            var showZoneBar = false
            var zoneLabel = ""
            var currentSec = 0
            var totalSec = 1
            var zoneColor = "#4b5563"
            
            val barW = 140f * sf
            val barH = 5f * sf
            val barX = cx
            val barY = valY + valSize + 22f * sf

            if (isValid) {
                val currentHr = point.heartRate
                val zIdx = getHrZoneIndex(currentHr)
                if (zIdx in 0..6) {
                    showZoneBar = true
                    currentSec = zonesCurrent[zIdx]
                    totalSec = cachedZonesTotal?.get(zIdx) ?: 1
                    zoneLabel = when (zIdx) {
                        6 -> "190+"
                        5 -> "180-189"
                        4 -> "170-179"
                        3 -> "160-169"
                        2 -> "150-159"
                        1 -> "140-149"
                        0 -> "130-139"
                        else -> ""
                    }
                    zoneColor = when (zIdx) {
                        6 -> "#ef4444" // Zone 6: Red
                        5 -> "#f97316" // Zone 5: Orange
                        4 -> "#eab308" // Zone 4: Yellow
                        3 -> "#22c55e" // Zone 3: Green
                        2 -> "#3b82f6" // Zone 2: Blue
                        1 -> "#6366f1" // Zone 1: Indigo
                        0 -> "#a855f7" // Zone 0: Purple
                        else -> "#4b5563"
                    }
                }
            }

            val metric = MetricLayout(
                labelText = getLabel("HEART RATE"),
                valueText = hrStr,
                unitText = "bpm",
                colorHex = "#ffffff",
                isVisible = true,
                x = cx,
                y = cy,
                cellWidth = cellW,
                cellHeight = cellH,
                actualValSize = valSize,
                valWidth = valW,
                unitWidth = getTextWidth("bpm", unitSize, true)
            )

            // 次のセルのために基準Y座標を進める
            cy += cellH + itemSpacing

            HeartRateLayout(
                metric = metric,
                showZoneBar = showZoneBar,
                zoneLabel = zoneLabel,
                currentSec = currentSec,
                totalSec = totalSec,
                zoneColor = zoneColor,
                barX = barX,
                barY = barY,
                barW = barW,
                barH = barH
            )
        } else null

        // 4. POWER
        val power = if (config.showPower) {
            val pwrStr = if (isValid) point.power.toInt().toString() else "-"
            layoutCell(getLabel("POWER"), pwrStr, "W", "#f59e0b", isVisible = true)
        } else null

        // 5. W/kg
        val wkg = if (config.showWkg && bodyWeight > 0.0) {
            val wkgVal = if (isValid) point.power / bodyWeight else 0.0
            val wkgStr = if (isValid) formatOneDecimal(wkgVal) else "-"
            layoutCell(getLabel("W/KG"), wkgStr, "W/kg", "#10b981", isVisible = true)
        } else null

        // 6. Simulating POWER TREND space to advance cy correctly
        if (config.showPowerTrend) {
            val tickLabelSize = labelSize * 0.8f
            val graphH = config.graphH
            cy += labelSize + 4f + graphH + (tickLabelSize + 4f) + itemSpacing
        }

        // 7. GRADE
        val grade = if (config.showGrade) {
            val grdStr = if (isValid) formatGrade(point.grade) else "-"
            layoutCell(getLabel("GRADE"), grdStr, "%", "#fbbf24", isVisible = true)
        } else null

        return HudOverlayLayout(
            dateDisplay = dateDisplay,
            speed = speed,
            cadence = cadence,
            heartRate = heartRate,
            power = power,
            wkg = wkg,
            grade = grade,
            finalCy = cy,
            isReady = true
        )
    }

    private fun getHrZoneIndex(hr: Double): Int {
        return when {
            hr >= 190.0 -> 6
            hr >= 180.0 -> 5
            hr >= 170.0 -> 4
            hr >= 160.0 -> 3
            hr >= 150.0 -> 2
            hr >= 140.0 -> 1
            hr >= 130.0 -> 0
            else -> -1
        }
    }
}
