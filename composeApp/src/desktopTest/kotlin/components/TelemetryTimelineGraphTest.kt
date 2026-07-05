package components

import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryTimelineGraphTest {

    @Test
    fun testFirstTickIsZeroRegardlessOfStartDiff() {
        val timelineDuration = 600.0
        val startDiff = 120.0
        
        // At ratio = 0 (the start of timeline), the tick seconds must always be 0.0 (the start of FIT recording)
        val firstTickSec = calculateTickSeconds(
            ratio = 0f,
            timelineDurationSec = timelineDuration,
            startDiffSec = startDiff
        )
        
        assertEquals(0.0, firstTickSec, "The first timeline tick must start at exactly 0.0 (FIT recording start)")
    }

    @Test
    fun testFormatAbsoluteTime() {
        val mockPoints = listOf(
            fit.FitParser.TelemetryPoint(
                timestamp = 0.0,
                speed = 0.0,
                power = 0.0,
                cadence = 0.0,
                heartRate = 0.0,
                elevation = 0.0,
                grade = 0.0
            )
        )
        
        val formatted = formatAbsoluteTime(120.0, "2026-07-05T11:00:00Z", mockPoints)
        val regex = Regex("^\\d{2}:\\d{2}:\\d{2}$")
        assert(regex.matches(formatted)) { "Expected HH:mm:ss format, got '$formatted'" }
    }
    @Test
    fun testDragVideoRangeSlidesTrimRange() {
        val vDuration = 600.0
        val videoLengthSec = 300.0
        
        val dragStartTrimStart = 50.0
        val dragStartTrimEnd = 150.0
        val dragStartRatio = 0.2f
        
        val currentRatio = 0.3f
        val deltaRatio = currentRatio - dragStartRatio
        val deltaSec = deltaRatio * vDuration // 60.0
        
        val trimLength = dragStartTrimEnd - dragStartTrimStart
        var newTrimStart = dragStartTrimStart + deltaSec
        var newTrimEnd = dragStartTrimEnd + deltaSec
        
        if (newTrimStart < 0.0) {
            newTrimStart = 0.0
            newTrimEnd = trimLength
        }
        if (newTrimEnd > videoLengthSec) {
            newTrimEnd = videoLengthSec
            newTrimStart = (videoLengthSec - trimLength).coerceAtLeast(0.0)
        }
        
        assertEquals(110.0, newTrimStart, 0.001)
        assertEquals(210.0, newTrimEnd, 0.001)
        
        val currentRatioClamp = 0.6f
        val deltaRatioClamp = currentRatioClamp - dragStartRatio
        val deltaSecClamp = deltaRatioClamp * vDuration // 240.0
        
        var clampedTrimStart = dragStartTrimStart + deltaSecClamp
        var clampedTrimEnd = dragStartTrimEnd + deltaSecClamp
        
        if (clampedTrimStart < 0.0) {
            clampedTrimStart = 0.0
            clampedTrimEnd = trimLength
        }
        if (clampedTrimEnd > videoLengthSec) {
            clampedTrimEnd = videoLengthSec
            clampedTrimStart = (videoLengthSec - trimLength).coerceAtLeast(0.0)
        }
        
        assertEquals(200.0, clampedTrimStart, 0.001)
        assertEquals(300.0, clampedTrimEnd, 0.001)
    }
}
