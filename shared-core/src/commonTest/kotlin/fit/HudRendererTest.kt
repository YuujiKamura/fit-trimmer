package fit

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class HudRendererTest {

    class TestHudCanvas : HudCanvas {
        override val width: Float = 1920f
        override val height: Float = 1080f
        
        val drawnTexts = mutableListOf<String>()
        var drawTextCalled = false
        var lastDrawnText = ""
        
        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
            drawnTexts.add(text)
            if (text == "テストテロップ") {
                drawTextCalled = true
                lastDrawnText = text
            }
        }
        
        override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {}
        override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {}
        override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {}
        override fun getTextWidth(text: String, size: Float, bold: Boolean): Float = text.length * 10f
    }

    @Test
    fun testRoadCaptionRenderingTimeCheck() {
        val testCaption = RoadCaptionSegment(
            id = "test-1",
            startSeconds = 10.0,
            endSeconds = 20.0,
            text = "テストテロップ",
            isEnabled = true
        )
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            captionPosition = "bottom_center",
            roadCaptions = listOf(testCaption)
        )
        val renderer = HudRenderer(config)
        
        val startPoint = FitParser.TelemetryPoint(
            timestamp = 0.0, speed = 0.0, power = 0.0, cadence = 0.0, heartRate = 0.0, elevation = 0.0, grade = 0.0
        )
        val endPoint = FitParser.TelemetryPoint(
            timestamp = 3600.0, speed = 0.0, power = 0.0, cadence = 0.0, heartRate = 0.0, elevation = 0.0, grade = 0.0
        )
        val originalPoints = listOf(startPoint, endPoint)
        
        // 1. テロップ表示範囲内 (現在の動画内秒数: 15秒)
        val canvas1 = TestHudCanvas()
        renderer.renderFrame(
            canvas1,
            startPoint,
            originalPoints,
            emptyList(),
            15.0f, // 15秒を渡す
            isValid = true
        )
        assertTrue(canvas1.drawTextCalled, "Caption should be drawn when current video time (15s) is within 10s-20s range")
        
        // 2. テロップ表示範囲外 (現在の動画内秒数: 5秒)
        val canvas2 = TestHudCanvas()
        renderer.renderFrame(
            canvas2,
            startPoint,
            originalPoints,
            emptyList(),
            5.0f, // 5秒を渡す
            isValid = true
        )
        assertFalse(canvas2.drawTextCalled, "Caption should not be drawn when current video time (5s) is out of range")
    }

    @Test
    fun testHudNumericAndGradeFormatting() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            captionPosition = "bottom_center"
        )
        val renderer = HudRenderer(config)

        // エッジケースのテストデータ (スピード異常値, 斜度の境界値)
        val telemetry = FitParser.TelemetryPoint(
            timestamp = 1151028589.0, // FIT timestamp
            speed = 8.356,             // 8.356 m/s -> km/h は約 30.08 km/h.丸めチェック
            power = 150.4,
            cadence = 90.0,
            heartRate = 140.0,
            elevation = 120.0,
            grade = -5.67              // 負の斜度
        )

        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            telemetry,
            listOf(telemetry),
            emptyList(),
            0.0f,
            isValid = true
        )

        // formatOneDecimal丸めと描画テキスト検証
        // スピード: 8.356 m/s (HudRendererでそのまま渡される。 speed: Doubleの単位はkm/hが想定されているか？)
        // プロダクションコード: `val spdStr = if (isValid) formatOneDecimal(telemetry.speed) else "-"`
        // 8.356 -> "8.4"
        assertTrue(canvas.drawnTexts.contains("8.4"), "Speed should be formatted to 1 decimal place: 8.4 (got ${canvas.drawnTexts})")
        
        // 斜度: -5.67 -> "-5.7"
        assertTrue(canvas.drawnTexts.contains("-5.7"), "Negative grade should be formatted to: -5.7 (got ${canvas.drawnTexts})")

        // 正の斜度のテスト
        val telemetryPositiveGrade = FitParser.TelemetryPoint(
            timestamp = 1151028589.0,
            speed = 10.0,
            power = 150.0,
            cadence = 90.0,
            heartRate = 140.0,
            elevation = 120.0,
            grade = 3.42               // 正の斜度
        )
        val canvasPositive = TestHudCanvas()
        renderer.renderFrame(
            canvasPositive,
            telemetryPositiveGrade,
            listOf(telemetryPositiveGrade),
            emptyList(),
            0.0f,
            isValid = true
        )
        // 斜度: 3.42 -> "+3.4"
        assertTrue(canvasPositive.drawnTexts.contains("+3.4"), "Positive grade should have a + sign and be formatted to: +3.4 (got ${canvasPositive.drawnTexts})")

        // 斜度 0 の場合のテスト
        val telemetryZeroGrade = telemetryPositiveGrade.copy(grade = 0.0)
        val canvasZero = TestHudCanvas()
        renderer.renderFrame(
            canvasZero,
            telemetryZeroGrade,
            listOf(telemetryZeroGrade),
            emptyList(),
            0.0f,
            isValid = true
        )
        // 0.0 -> "+0.0" または "0.0" -> 現在の実装では sign = if (value > 0.0) "+" else "" なので "0.0"
        assertTrue(canvasZero.drawnTexts.contains("0.0"), "Zero grade should be formatted to: 0.0 (got ${canvasZero.drawnTexts})")
    }

    @Test
    fun testHudInvalidTelemetryHandling() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f
        )
        val renderer = HudRenderer(config)
        val emptyPoint = FitParser.TelemetryPoint(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            emptyPoint,
            emptyList(),
            emptyList(),
            0.0f,
            isValid = false
        )
        
        // isValid = false の場合、各値はハイフンであること
        assertTrue(canvas.drawnTexts.contains("-"), "Invalid state should display '-' for values")
    }

    @Test
    fun testHudDistanceAndTimeLabelsAndFormatting() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        val startPoint = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10
        )
        val currentPoint = FitParser.TelemetryPoint(
            timestamp = 1100.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.5, elapsedSeconds = 110
        )
        val allPoints = listOf(startPoint, currentPoint)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            currentPoint,
            allPoints,
            emptyList(),
            100.0f,
            isValid = true
        )
        
        val tripLine = canvas.drawnTexts.find { it.contains("全体走行距離:") }
        val clipLine = canvas.drawnTexts.find { it.contains("区間走行距離:") }
        
        assertTrue(tripLine != null, "HUD should display '全体走行距離:' (got ${canvas.drawnTexts})")
        assertTrue(clipLine != null, "HUD should display '区間走行距離:' (got ${canvas.drawnTexts})")
        
        assertTrue(tripLine.contains("全体経過時間:"), "HUD should display '全体経過時間:' (got '$tripLine')")
        assertTrue(clipLine.contains("区間経過時間:"), "HUD should display '区間経過時間:' (got '$clipLine')")
        
        assertTrue(tripLine.contains("2.50 km"), "Overall distance should show 2 decimal places: 2.50 km (got '$tripLine')")
        assertTrue(clipLine.contains("1.50 km"), "Clip distance should show 2 decimal places: 1.50 km (got '$clipLine')")
        
        assertTrue(canvas.drawnTexts.contains("スピード"), "HUD should localize SPEED to Japanese")
        assertTrue(canvas.drawnTexts.contains("標高"), "HUD should localize ELEVATION to Japanese")
    }

    @Test
    fun testHudLocalizationEnglishFallback() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "en"
        )
        val renderer = HudRenderer(config)
        
        val startPoint = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10
        )
        val currentPoint = FitParser.TelemetryPoint(
            timestamp = 1100.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.5, elapsedSeconds = 110
        )
        val allPoints = listOf(startPoint, currentPoint)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            currentPoint,
            allPoints,
            emptyList(),
            100.0f,
            isValid = true
        )
        
        val tripLine = canvas.drawnTexts.find { it.contains("Total Distance:") }
        val clipLine = canvas.drawnTexts.find { it.contains("Split Distance:") }
        
        assertTrue(tripLine != null, "HUD should display 'Total Distance:' (got ${canvas.drawnTexts})")
        assertTrue(clipLine != null, "HUD should display 'Split Distance:' (got ${canvas.drawnTexts})")
        
        assertTrue(tripLine.contains("Total Elapsed:"), "HUD should display 'Total Elapsed:' (got '$tripLine')")
        assertTrue(clipLine.contains("Split Elapsed:"), "HUD should display 'Split Elapsed:' (got '$clipLine')")
        
        assertTrue(canvas.drawnTexts.contains("SPEED"), "HUD should keep SPEED in English")
        assertTrue(canvas.drawnTexts.contains("ELEVATION"), "HUD should keep ELEVATION in English")
    }
}

