import org.junit.Test
import kotlin.test.assertEquals
import TimeAlignmentState

class TimeOffsetTest {

    @Test
    fun testUpdateTimeComponents() {
        val state = TimeAlignmentState(0)
        
        // UTC 2026-07-04 06:38:16 -> JST 2026-07-04 15:38:16
        val baseUtc = "2026-07-04T06:38:16Z"
        
        // Target: JST 16:04:16
        // Difference: 16:04:16 - 15:38:16 = 26 minutes = 1560 seconds = 1560000 ms
        state.updateTimeComponents(16, 4, 16, baseUtc)
        
        assertEquals(1560000, state.millis)
    }
}
