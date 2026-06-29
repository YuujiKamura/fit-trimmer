package fit

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class HudRendererTest {

    class TestHudCanvas : HudCanvas {
        override val width: Float = 1920f
        override val height: Float = 1080f
        
        var drawTextCalled = false
        var lastDrawnText = ""
        
        override fun drawText(text: String, x: Float, y: Float, size: Float, color: String, bold: Boolean, anchor: String) {
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
}
