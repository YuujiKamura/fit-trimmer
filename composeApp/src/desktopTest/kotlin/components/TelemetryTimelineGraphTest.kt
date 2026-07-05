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
}
