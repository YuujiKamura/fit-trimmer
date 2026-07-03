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
        
        data class RectInfo(val x: Float, val y: Float, val w: Float, val h: Float, val color: String)
        data class LineInfo(val points: List<Pair<Float, Float>>, val color: String, val width: Float)
        data class PolygonInfo(val points: List<Pair<Float, Float>>, val color: String)
        data class TextInfo(val text: String, val x: Float, val y: Float, val size: Float, val color: String, val bold: Boolean, val anchor: String)

        val drawnRects = mutableListOf<RectInfo>()
        val drawnLines = mutableListOf<LineInfo>()
        val drawnPolygons = mutableListOf<PolygonInfo>()
        val drawnTextInfos = mutableListOf<TextInfo>()
        val drawnShadowTextInfos = mutableListOf<TextInfo>()
        
        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
            if (color == "#111827") {
                drawnShadowTextInfos.add(TextInfo(text, x, y, size, color, bold, anchor))
                return
            }
            drawnTexts.add(text)
            drawnTextInfos.add(TextInfo(text, x, y, size, color, bold, anchor))
            if (text == "テストテロップ") {
                drawTextCalled = true
                lastDrawnText = text
            }
        }
        
        override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {
            drawnRects.add(RectInfo(x, y, w, h, color))
        }
        
        override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {
            drawnLines.add(LineInfo(points, color, width))
        }
        
        override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {
            drawnPolygons.add(PolygonInfo(points, color))
        }
        
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
            emptyList(),
            100.0f,
            isValid = true
        )
        
        val clipLine = canvas.drawnTexts.find { it.contains("距離:") }
        val tripLine = canvas.drawnTexts.find { it.contains("全体") }
        
        assertTrue(clipLine != null, "HUD should display '距離:' line (got ${canvas.drawnTexts})")
        assertTrue(tripLine == null, "HUD should NOT display overall '全体' line (got ${canvas.drawnTexts})")
        
        assertTrue(clipLine.contains("距離: 1.50 km"), "Clip distance should be 1.50 km (got '$clipLine')")
        assertTrue(clipLine.contains("時間: 01:40"), "Clip time should be 01:40 (got '$clipLine')")
        
        assertTrue(canvas.drawnTexts.contains("スピード"), "HUD should localize SPEED to Japanese")
        assertTrue(canvas.drawnTexts.contains("標高"), "HUD should localize ELEVATION to Japanese")
    }

    @Test
    fun testElevationGraph_DisplaysHeadingAndValleyPoints() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja", showElevation = true
        )
        val renderer = HudRenderer(config)
        
        // Setup 4 telemetry points with distinct lat/lon to compute bearings
        // P1: Start (50m) -> Moving North-East to P2
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        // P2: Peak (max 120m) -> Moving South to P3
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 120.0, grade = 2.0,
            distance = 1100.0, elapsedSeconds = 20, lat = 35.01, lon = 135.01
        )
        // P3: Valley (min 30m) -> Moving North to P4
        val p3 = FitParser.TelemetryPoint(
            timestamp = 1020.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 30.0, grade = 2.0,
            distance = 1200.0, elapsedSeconds = 30, lat = 34.99, lon = 135.01
        )
        // P4: End (60m)
        val p4 = FitParser.TelemetryPoint(
            timestamp = 1030.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 60.0, grade = 2.0,
            distance = 1300.0, elapsedSeconds = 40, lat = 35.0, lon = 135.01
        )
        val allPoints = listOf(p1, p2, p3, p4)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            p4,
            allPoints,
            emptyList(),
            emptyList(),
            100.0f,
            isValid = true
        )
        
        // Assert that the drawn texts on the elevation graph contain our 16-direction azimuth labels and tags
        val texts = canvas.drawnTexts
        
        // Check Start text (Expected format: "起点 50m (NE)" or similar depending on actual heading math)
        val startText = texts.find { it.contains("起点") }
        assertTrue(startText != null, "HUD elevation graph should draw '起点' (got $texts)")
        assertTrue(startText!!.contains("m"), "Start text should contain elevation units")
        assertTrue(startText.contains("(") && startText.contains(")"), "Start text should contain 16-direction heading (got '$startText')")
        
        // Check End text
        val endText = texts.find { it.contains("終点") }
        assertTrue(endText != null, "HUD elevation graph should draw '終点' (got $texts)")
        assertTrue(endText!!.contains("m"), "End text should contain elevation units")
        assertTrue(endText.contains("(") && endText.contains(")"), "End text should contain 16-direction heading (got '$endText')")
        
        // Check Peak (Max) text
        val peakText = texts.find { it.contains("最高") }
        assertTrue(peakText != null, "HUD elevation graph should draw '最高' (got $texts)")
        assertTrue(peakText!!.contains("120m"), "Peak text should contain peak elevation value (got '$peakText')")
        
        // Check Valley (Min) text
        val valleyText = texts.find { it.contains("最低") }
        assertTrue(valleyText != null, "HUD elevation graph should draw '最低' (got $texts)")
        assertTrue(valleyText!!.contains("30m"), "Valley text should contain valley elevation value (got '$valleyText')")
    }

    @Test
    fun testMiniMap_DisplaysRouteAndCompassOrientations() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        // Setup 4 telemetry points with distinct lat/lon to build a path direction
        // P1: Start (lat=35.0, lon=135.0) -> P2: lat=35.01, lon=135.01
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.0, elapsedSeconds = 20, lat = 35.01, lon = 135.01
        )
        val allPoints = listOf(p1, p2)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            p2,
            allPoints,
            emptyList(),
            emptyList(),
            100.0f,
            isValid = true
        )
        
        val texts = canvas.drawnTexts
        
        // Check Compass headings N, E, S, W exist
        assertTrue(texts.contains("N"), "Mini-map compass should draw 'N' (got $texts)")
        assertTrue(texts.contains("E"), "Mini-map compass should draw 'E' (got $texts)")
        assertTrue(texts.contains("S"), "Mini-map compass should draw 'S' (got $texts)")
        assertTrue(texts.contains("W"), "Mini-map compass should draw 'W' (got $texts)")
        
        // Check Distance labels: start distance "0.0" and total distance "1.5 km"
        assertTrue(texts.contains("0.0"), "Mini-map should label start distance '0.0' (got $texts)")
        assertTrue(texts.any { it.contains("1.5") && it.contains("km") }, "Mini-map should draw total distance (got $texts)")
    }

    @Test
    fun testMiniMap_CoordinateProjectionFidelity() {
        val config = HudConfig(
            valSize = 59.27f, // Use default size
            tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        // P1: Start (35.0, 135.0) -> P2: End (35.01, 135.01)
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.0, elapsedSeconds = 20, lat = 35.01, lon = 135.01
        )
        val allPoints = listOf(p1, p2)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(canvas, p2, allPoints, emptyList(), emptyList(), 100.0f, isValid = true)
        
        val rects = canvas.drawnRects
        
        // sf = 1.48175
        // R = 75 * sf = 111.13125f
        // marginX = 45 * sf = 66.67875f
        // marginY = 40 * sf = 59.27f
        // mcx = canvas.width - marginX - R = 1920 - 66.67875 - 111.13125 = 1742.19f
        // sf = 1.48175
        // R = 68 * sf = 100.759f
        // marginX = 45 * sf = 66.67875f
        // marginY = 40 * sf = 59.27f
        // mcx = canvas.width - marginX - R = 1920 - 66.67875 - 100.759 = 1752.56225f
        // mcy = marginY + R = 59.27 + 100.759 = 160.029f
        // padR = R - 10f * sf = 58 * sf = 85.9415f
        // Start marker should be at mcx, mcy + padR = (1752.562f, 245.9705f)
        // End marker should be at mcx, mcy - padR = (1752.562f, 74.0875f)
        // Marker size = 8f * sf = 11.854f, hmSize = 5.927f
        // Start rect left: 1752.562 - 5.927 = 1746.635f, top: 245.9705 - 5.927 = 240.043f
        // End rect left: 1752.562 - 5.927 = 1746.635f, top: 74.0875 - 5.927 = 68.160f
        
        val startRect = rects.find { kotlin.math.abs(it.x - 1746.635f) < 1.0f && kotlin.math.abs(it.y - 240.043f) < 1.0f }
        val endRect = rects.find { kotlin.math.abs(it.x - 1746.635f) < 1.0f && kotlin.math.abs(it.y - 68.160f) < 1.0f }
        
        assertTrue(startRect != null, "Start marker must align perfectly with lower bound (1746.635, 240.043) (rects: $rects)")
        assertTrue(endRect != null, "End marker must align perfectly with upper bound (1746.635, 68.160) (rects: $rects)")
    }
 
    @Test
    fun testMiniMap_LoopRouteFittedInsideCircle() {
        val config = HudConfig(
            valSize = 59.27f, // Use default size
            tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        // Loop route: Start (35.0, 135.0) -> Middle (35.01, 135.0) -> End (35.0, 135.0)
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1005.0, speed = 11.0, power = 105.0, cadence = 81.0, heartRate = 121.0, elevation = 51.0, grade = 2.1,
            distance = 1750.0, elapsedSeconds = 15, lat = 35.01, lon = 135.0
        )
        val p3 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.0, elapsedSeconds = 20, lat = 35.0, lon = 135.0
        )
        val allPoints = listOf(p1, p2, p3)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(canvas, p3, allPoints, emptyList(), emptyList(), 100.0f, isValid = true)
        
        // sf = 59.27 / 40.0 = 1.48175f
        // R = 75 * sf = 111.13125f
        // sf = 1.48175f
        // R = 68 * sf = 100.759f
        // marginX = 45 * sf = 66.67875f
        // marginY = 40 * sf = 59.27f
        // mcx = canvas.width - marginX - R = 1752.562f
        // mcy = marginY + R = 160.029f
        // padR = R - 10f * sf = 58 * sf = 85.9415f
        val mcx = 1752.562f
        val mcy = 160.029f
        val padR = 85.9415f
        
        // sf = 59.27 / 40.0 = 1.48175f. Route line width is 2.8f * sf = 4.1489f
        val routeLines = canvas.drawnLines.filter { it.color == "#ffffff" && kotlin.math.abs(it.width - 4.1489f) < 0.01f }
        assertTrue(routeLines.isNotEmpty(), "Loop route must draw some line segments")
        
        var maxDistance = 0f
        for (line in routeLines) {
            for (pt in line.points) {
                val dx = pt.first - mcx
                val dy = pt.second - mcy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                
                // Allow small margin of 2px for clamped limits
                assertTrue(dist <= padR + 2.0f, "Line coordinates must not exceed circle boundary (got distance $dist, padR $padR)")
                if (dist > maxDistance) {
                    maxDistance = dist
                }
            }
        }
        
        // One of the points (furthest from center) should be scaled close to padR boundary to utilize space efficiently
        assertTrue(maxDistance >= padR - 5.0f, "Loop route must scale up to utilize circle space (max distance from center: $maxDistance, expected near $padR)")
    }

    @Test
    fun testMiniMap_CompassAndRouteMargin() {
        val config = HudConfig(
            valSize = 59.27f, // sf = 1.48175f
            tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1005.0, speed = 11.0, power = 105.0, cadence = 81.0, heartRate = 121.0, elevation = 51.0, grade = 2.1,
            distance = 1750.0, elapsedSeconds = 15, lat = 35.01, lon = 135.0
        )
        val p3 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.0, elapsedSeconds = 20, lat = 35.0, lon = 135.0
        )
        val allPoints = listOf(p1, p2, p3)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(canvas, p3, allPoints, emptyList(), emptyList(), 100.0f, isValid = true)
        
        val sf = 1.48175f
        val R = 68f * sf
        val marginX = 45f * sf
        val mcx = canvas.width - marginX - R
        val mcy = 40f * sf + R
        
        val routeLines = canvas.drawnLines.filter { it.color == "#ffffff" && kotlin.math.abs(it.width - 4.1489f) < 0.01f }
        assertTrue(routeLines.isNotEmpty())
        
        var maxRouteDist = 0f
        for (line in routeLines) {
            for (pt in line.points) {
                val dx = pt.first - mcx
                val dy = pt.second - mcy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > maxRouteDist) {
                    maxRouteDist = dist
                }
            }
        }
        
        // Verify physical drawn coordinates of compass characters
        val compassTexts = listOf("N", "E", "S", "W")
        for (label in compassTexts) {
            val textInfo = canvas.drawnTextInfos.find { it.text == label }
            assertTrue(textInfo != null, "HUD must draw compass label $label")
            
            val dx = textInfo.x - mcx
            val dy = textInfo.y - mcy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            
            // Assert that compass characters are strictly placed outside the circle border
            assertTrue(dist >= R + 5f * sf - 1.0f, "Compass label $label must be placed outside the circle border (got distance $dist, expected >= ${R + 5f * sf})")
            
            // Assert that compass characters are far enough from the route path
            val fromRouteMargin = dist - maxRouteDist
            assertTrue(fromRouteMargin >= 15f * sf, "Compass label $label must be at least 15f * sf away from route (got $fromRouteMargin)")
        }
    }

    @Test
    fun testMiniMap_RouteCurvatureDirection() {
        val config = HudConfig(
            valSize = 59.27f, // sf = 1.48175f
            tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            language = "ja"
        )
        val renderer = HudRenderer(config)
        
        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 120.0, elevation = 50.0, grade = 2.0,
            distance = 1000.0, elapsedSeconds = 10, lat = 35.0, lon = 135.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1005.0, speed = 11.0, power = 105.0, cadence = 81.0, heartRate = 121.0, elevation = 51.0, grade = 2.1,
            distance = 1750.0, elapsedSeconds = 15, lat = 35.005, lon = 135.01
        )
        val p3 = FitParser.TelemetryPoint(
            timestamp = 1010.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 122.0, elevation = 52.0, grade = 2.2,
            distance = 2500.0, elapsedSeconds = 20, lat = 35.01, lon = 135.0
        )
        val allPoints = listOf(p1, p2, p3)
        
        val canvas = TestHudCanvas()
        renderer.renderFrame(canvas, p3, allPoints, emptyList(), emptyList(), 100.0f, isValid = true)
        
        val sf = 1.48175f
        val R = 75f * sf
        val marginX = 45f * sf
        val mcx = canvas.width - marginX - R
        
        val routeLines = canvas.drawnLines.filter { it.color == "#ffffff" && kotlin.math.abs(it.width - 4.1489f) < 0.01f }
        assertTrue(routeLines.isNotEmpty())
        
        val middlePtProjX = routeLines[0].points[1].first
        assertTrue(middlePtProjX > mcx + 2.0f, "Middle point in right-hand turn must be projected to the right of center mcx ($mcx), got $middlePtProjX")
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
            emptyList(),
            100.0f,
            isValid = true
        )
        
        val clipLine = canvas.drawnTexts.find { it.contains("Distance:") }
        val tripLine = canvas.drawnTexts.find { it.contains("Total") }
        
        assertTrue(clipLine != null, "HUD should display 'Distance:' line (got ${canvas.drawnTexts})")
        assertTrue(tripLine == null, "HUD should NOT display overall 'Total' line (got ${canvas.drawnTexts})")
        
        assertTrue(clipLine.contains("Distance: 1.50 km"), "Clip distance should be 1.50 km (got '$clipLine')")
        assertTrue(clipLine.contains("Time: 01:40"), "Clip time should be 01:40 (got '$clipLine')")
        
        assertTrue(canvas.drawnTexts.contains("SPEED"), "HUD should keep SPEED in English")
        assertTrue(canvas.drawnTexts.contains("ELEVATION"), "HUD should keep ELEVATION in English")
    }

    @Test
    fun testHeartRateZonesRendering() {
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            captionPosition = "bottom_center"
        )
        val renderer = HudRenderer(config)

        val p1 = FitParser.TelemetryPoint(
            timestamp = 1000.0, speed = 10.0, power = 100.0, cadence = 80.0, heartRate = 135.0, elevation = 50.0, grade = 2.0
        )
        val p2 = FitParser.TelemetryPoint(
            timestamp = 1001.0, speed = 12.0, power = 110.0, cadence = 82.0, heartRate = 145.0, elevation = 52.0, grade = 2.2
        )
        val p3 = FitParser.TelemetryPoint(
            timestamp = 1002.0, speed = 11.0, power = 120.0, cadence = 81.0, heartRate = 175.0, elevation = 51.0, grade = 2.1
        )
        val allPoints = listOf(p1, p2, p3)

        val canvas = TestHudCanvas()
        renderer.renderFrame(
            canvas,
            p2, // Current frame is p2
            allPoints,
            emptyList(),
            emptyList(),
            1.0f,
            isValid = true
        )

        // Check if the HEART RATE ZONES header is drawn
        val hasHeader = canvas.drawnTexts.any { it.contains("HEART RATE ZONES") }
        assertFalse(hasHeader, "HUD should NOT draw HEART RATE ZONES header anymore")

        // Check if current active HR zone cell is drawn under HEART RATE
        assertTrue(canvas.drawnTexts.contains("ZONE 140-149: 00:01"), "Should draw active zone label and time under heart rate")

        // Check that bottom table labels are NOT drawn
        assertFalse(canvas.drawnTexts.contains("130-139"), "Zone 130-139 should NOT be drawn as a separate table row")
        assertFalse(canvas.drawnTexts.contains("190+"), "Zone 190+ should NOT be drawn as a separate table row")
    }

    class CustomCaptionTestHudCanvas : HudCanvas {
        override val width: Float = 1920f
        override val height: Float = 1080f
        
        data class RectCall(val x: Float, val y: Float, val w: Float, val h: Float, val color: String, val alpha: Float)
        data class TextCall(val text: String, val x: Float, val y: Float, val size: Float, val color: String, val anchor: String)
        
        val rectCalls = mutableListOf<RectCall>()
        val textCalls = mutableListOf<TextCall>()
        
        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
            if (color == "#111827") return
            textCalls.add(TextCall(text, x, y, size, color, anchor))
        }
        
        override fun drawRect(x: Float, y: Float, w: Float, h: Float, color: String, alpha: Float, outline: Boolean) {
            rectCalls.add(RectCall(x, y, w, h, color, alpha))
        }
        
        override fun drawLine(points: List<Pair<Float, Float>>, color: String, width: Float, alpha: Float) {}
        override fun drawPolygon(points: List<Pair<Float, Float>>, color: String, alpha: Float) {}
        override fun getTextWidth(text: String, size: Float, bold: Boolean): Float = text.length * 10f
    }

    @Test
    fun testCustomCaptionRendering() {
        val segment = CustomCaptionSegment(
            id = "custom-1",
            startSeconds = 5.0,
            endSeconds = 15.0,
            text = "Hello Custom Caption",
            isEnabled = true,
            fontSize = 32f,
            textColor = "#ff0000",
            backgroundColor = "#00ff00",
            backgroundAlpha = 0.8f,
            positionX = 0.25f,
            positionY = 0.75f,
            align = "center"
        )
        
        val config = HudConfig(
            valSize = 40f, tightness = 1f, spacing = 20f,
            xOffset = 40f, yOffset = 100f, graphH = 60f, graphW = 300f,
            customCaptions = listOf(segment)
        )
        
        val renderer = HudRenderer(config)
        val startPoint = FitParser.TelemetryPoint(
            timestamp = 0.0, speed = 0.0, power = 0.0, cadence = 0.0, heartRate = 0.0, elevation = 0.0, grade = 0.0
        )
        val originalPoints = listOf(startPoint)
        
        // 1. 表示時間内 (10秒)
        val canvas1 = CustomCaptionTestHudCanvas()
        renderer.renderFrame(
            canvas1,
            startPoint,
            originalPoints,
            emptyList(),
            emptyList(),
            10.0f,
            isValid = true
        )
        
        val textCall = canvas1.textCalls.find { it.text == "Hello Custom Caption" }
        val rectCall = canvas1.rectCalls.find { it.color == "#00ff00" && it.alpha == 0.8f }
        
        assertTrue(textCall != null, "Custom caption should be drawn inside range")
        assertTrue(rectCall != null, "Background rect should be drawn inside range")
        
        assertEquals(32f, textCall.size, "Font size should match")
        assertEquals("#ff0000", textCall.color, "Text color should match")
        assertEquals("center", textCall.anchor, "Anchor should be center")
        
        assertEquals(360f, rectCall.x, "Rect X should be aligned center")
        assertEquals(784f, rectCall.y, "Rect Y should be centered vertically")
        assertEquals(240f, rectCall.w, "Rect width should match text width + padding")
        assertEquals(52f, rectCall.h, "Rect height should match font size + padding")
        
        // 2. 表示時間外 (20秒)
        val canvas2 = CustomCaptionTestHudCanvas()
        renderer.renderFrame(
            canvas2,
            startPoint,
            originalPoints,
            emptyList(),
            emptyList(),
            20.0f,
            isValid = true
        )
        assertFalse(canvas2.textCalls.any { it.text == "Hello Custom Caption" }, "Custom caption should not be drawn out of range")
    }
}

