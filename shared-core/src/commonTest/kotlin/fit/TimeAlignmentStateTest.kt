package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeAlignmentStateTest {

    @Test
    fun testUpdateAndLimits() {
        val state = TimeAlignmentState(0)
        assertEquals(0, state.millis)
        assertEquals(0f, state.seconds)

        state.update(1000)
        assertEquals(1000, state.millis)
        assertEquals(1.0f, state.seconds)

        // Exceed limits
        state.update(TimeAlignmentState.MAX_OFFSET_MILLIS + 1000)
        assertEquals(TimeAlignmentState.MAX_OFFSET_MILLIS, state.millis)

        state.update(-TimeAlignmentState.MAX_OFFSET_MILLIS - 1000)
        assertEquals(-TimeAlignmentState.MAX_OFFSET_MILLIS, state.millis)
    }

    @Test
    fun testAdjustUtcTime() {
        val state = TimeAlignmentState(1000) // +1s
        val baseUtc = "2026-06-30T08:44:58Z"
        val adjusted = state.adjust(baseUtc)
        assertEquals("2026-06-30T08:44:59Z", adjusted)
    }

    @Test
    fun testUpdateTimeComponents() {
        val state = TimeAlignmentState(0)
        val baseUtc = "2026-06-30T08:00:00Z" // 17:00:00 JST
        // Update components in Tokyo timezone (JST)
        state.updateTimeComponents(18, 0, 0, baseUtc) // Shift JST to 18:00:00 (+1 hour)
        assertEquals(3600000, state.millis) // +3600s
    }
}
