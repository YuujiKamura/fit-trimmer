package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeSynchronizerTest {

    @Test
    fun testTimeSynchronizationLogic() {
        var videoStart: Long? = 1782278400L // Some Unix Epoch Sec (e.g., 2026-07-08T18:40:00Z)
        var offsetMillis = 5000L // 5 seconds offset

        val synchronizer = TimeSynchronizerImpl(
            videoStartEpochSecProvider = { videoStart },
            offsetMillisProvider = { offsetMillis }
        )

        assertTrue(synchronizer.isReady)

        // 1989-12-31T00:00:00Z in Unix Epoch Seconds is 631065600L.
        // videoStartFit = 1782278400 - 631065600 + (5000 / 1000) = 1151212800 + 5 = 1151212805
        // If fitTimestamp is 1151212815, relative video seconds should be:
        // 1151212815 - 1151212805 = 10.0 seconds
        val fitTimestamp = 1151212815.0
        val videoSec = synchronizer.fitToVideoSeconds(fitTimestamp)
        assertEquals(10.0, videoSec)

        // Reverse conversion: video seconds 10.0 should convert back to fitTimestamp 1151212815
        val fitBack = synchronizer.videoSecondsToFit(10.0)
        assertEquals(1151212815.0, fitBack)

        // Test changes to offset dynamic updates
        offsetMillis = 15000L // Change offset to 15 seconds
        // videoStartFit is now 1151212800 + 15 = 1151212815
        // fitToVideoSeconds(1151212815.0) should now be 0.0 seconds
        assertEquals(0.0, synchronizer.fitToVideoSeconds(fitTimestamp))
        assertEquals(1151212815.0, synchronizer.videoSecondsToFit(0.0))

        // Test when video start is null (not ready)
        videoStart = null
        assertFalse(synchronizer.isReady)
        assertEquals(0.0, synchronizer.fitToVideoSeconds(fitTimestamp))
    }
}
