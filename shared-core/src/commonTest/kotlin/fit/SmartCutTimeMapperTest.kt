package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class SmartCutTimeMapperTest {
    @Test
    fun testMapVideoTimeToFitTimeWithCuts() {
        val cutSpans = listOf(
            CutSpan(10.0, 20.0), // 10s cut
            CutSpan(50.0, 60.0)  // 10s cut
        )

        // 0.0 ~ 10.0 (no cuts) -> videoTime matches fitTime
        assertEquals(5.0, SmartCutTimeMapper.mapVideoTimeToFitTime(5.0, cutSpans))
        assertEquals(10.0, SmartCutTimeMapper.mapVideoTimeToFitTime(10.0, cutSpans))

        // After first cut (10s shift)
        // videoTime 15.0 represents fitTime 25.0
        assertEquals(25.0, SmartCutTimeMapper.mapVideoTimeToFitTime(15.0, cutSpans))
        assertEquals(40.0, SmartCutTimeMapper.mapVideoTimeToFitTime(30.0, cutSpans))

        // After second cut (20s shift)
        // videoTime 45.0 represents fitTime 65.0
        assertEquals(65.0, SmartCutTimeMapper.mapVideoTimeToFitTime(45.0, cutSpans))
        assertEquals(75.0, SmartCutTimeMapper.mapVideoTimeToFitTime(55.0, cutSpans))
    }
}
