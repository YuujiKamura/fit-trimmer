package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedMapperTest {

    @Test
    fun testEmptySegments() {
        val segments = emptyList<SpeedSegment>()
        assertEquals(10.0, SpeedMapper.mapSourceToTarget(10.0, segments))
        assertEquals(10.0, SpeedMapper.mapTargetToSource(10.0, segments))
    }

    @Test
    fun testSingleSpeedSegment() {
        // Segment from 10.0s to 20.0s at 4.0x speed.
        val segments = listOf(
            SpeedSegment("1", 10.0, 20.0, 4.0)
        )

        // Before segment (normal speed: 1.0x)
        assertEquals(5.0, SpeedMapper.mapSourceToTarget(5.0, segments))
        assertEquals(5.0, SpeedMapper.mapTargetToSource(5.0, segments))

        // Right at start of segment
        assertEquals(10.0, SpeedMapper.mapSourceToTarget(10.0, segments))
        assertEquals(10.0, SpeedMapper.mapTargetToSource(10.0, segments))

        // Middle of segment: source 15s is 5s into the 4.0x segment
        // Target time = 10.0 + 5.0 / 4.0 = 11.25
        assertEquals(11.25, SpeedMapper.mapSourceToTarget(15.0, segments))
        assertEquals(15.0, SpeedMapper.mapTargetToSource(11.25, segments))

        // End of segment: source 20s is 10s into 4.0x segment
        // Target time = 10.0 + 10.0 / 4.0 = 12.5
        assertEquals(12.5, SpeedMapper.mapSourceToTarget(20.0, segments))
        assertEquals(20.0, SpeedMapper.mapTargetToSource(12.5, segments))

        // After segment: source 25s is 5s after the segment (runs at 1.0x)
        // Target time = 12.5 + 5.0 = 17.5
        assertEquals(17.5, SpeedMapper.mapSourceToTarget(25.0, segments))
        assertEquals(25.0, SpeedMapper.mapTargetToSource(17.5, segments))
    }

    @Test
    fun testMultipleSpeedSegments() {
        // Segment 1: 10.0s to 20.0s at 4.0x speed.
        // Segment 2: 30.0s to 40.0s at 2.0x speed.
        val segments = listOf(
            SpeedSegment("1", 10.0, 20.0, 4.0),
            SpeedSegment("2", 30.0, 40.0, 2.0)
        )

        // 1. Before first segment
        assertEquals(5.0, SpeedMapper.mapSourceToTarget(5.0, segments))
        assertEquals(5.0, SpeedMapper.mapTargetToSource(5.0, segments))

        // 2. In first segment (e.g. source 14.0 -> target 10.0 + 4.0/4.0 = 11.0)
        assertEquals(11.0, SpeedMapper.mapSourceToTarget(14.0, segments))
        assertEquals(14.0, SpeedMapper.mapTargetToSource(11.0, segments))

        // 3. Between segments: source 25.0
        // Target end of segment 1 is 12.5.
        // Source duration from 20.0 to 25.0 is 5.0 at 1.0x speed.
        // Target time = 12.5 + 5.0 = 17.5.
        assertEquals(17.5, SpeedMapper.mapSourceToTarget(25.0, segments))
        assertEquals(25.0, SpeedMapper.mapTargetToSource(17.5, segments))

        // 4. In second segment: source 34.0
        // Target start of segment 2 (source 30.0) is 12.5 + (30.0 - 20.0) = 22.5.
        // Source duration from 30.0 to 34.0 is 4.0 at 2.0x speed.
        // Target time = 22.5 + 4.0 / 2.0 = 24.5.
        assertEquals(24.5, SpeedMapper.mapSourceToTarget(34.0, segments))
        assertEquals(34.0, SpeedMapper.mapTargetToSource(24.5, segments))

        // 5. After second segment: source 45.0
        // Target end of segment 2 (source 40.0) is 22.5 + (40.0 - 30.0) / 2.0 = 27.5.
        // Source duration from 40.0 to 45.0 is 5.0 at 1.0x speed.
        // Target time = 27.5 + 5.0 = 32.5.
        assertEquals(32.5, SpeedMapper.mapSourceToTarget(45.0, segments))
        assertEquals(45.0, SpeedMapper.mapTargetToSource(32.5, segments))
    }
}
