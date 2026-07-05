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
}
