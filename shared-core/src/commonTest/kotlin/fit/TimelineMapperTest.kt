package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineMapperTest {

    @Test
    fun testTimelineSynchronizationAndSpeedConsistency() {
        val fullVideoLengthMs = 1800000L // 30 minutes
        val proxyDurationMs = 30000L    // 30 seconds (short crop for preview)
        
        // Scenario 1: Raw player slider is at 10% (100f out of 1000f)
        val sliderPos = 100f
        
        // Calculate mapped playhead time
        val calculatedTimeMs = TimelineMapper.calculateCurrentTimeMs(
            sliderPos = sliderPos,
            durationMs = proxyDurationMs,
            fullVideoLengthMs = fullVideoLengthMs
        )
        
        // EXPECTED: For a 1:1 real-time playback sync, 10% of a 30s loaded file
        // MUST equal exactly 3 seconds (3,000 ms), NOT 3 minutes (180,000 ms).
        val expectedTimeMs = 3000L
        
        assertEquals(
            expectedTimeMs, 
            calculatedTimeMs, 
            "CRITICAL: Playback speed mismatch! 10% progress of 30s proxy must equal 3s, but was $calculatedTimeMs ms"
        )
    }

    @Test
    fun testTimelineWithFullVideoLoaded() {
        val fullVideoLengthMs = 1800000L // 30 minutes
        val rawVideoDurationMs = 1800000L // 30 minutes (full file)
        
        val sliderPos = 500f // 50% progress
        
        val calculatedTimeMs = TimelineMapper.calculateCurrentTimeMs(
            sliderPos = sliderPos,
            durationMs = rawVideoDurationMs,
            fullVideoLengthMs = fullVideoLengthMs
        )
        
        // For full video, 50% must equal 15 minutes (900,000 ms)
        assertEquals(900000L, calculatedTimeMs)
    }

    @Test
    fun testTimelineSyncAtArbitrarySeekPosition() {
        val fullVideoLengthMs = 1800000L // 30 minutes
        
        // Case 1: Unsafe buggy 30s crop. Must return false (invalid/unsafe)
        val croppedProxyDurationMs = 30000L
        val isCroppedValid = TimelineMapper.isProxyDurationValid(croppedProxyDurationMs, fullVideoLengthMs)
        kotlin.test.assertFalse(isCroppedValid, "CRITICAL: Cropped proxy of 30s for a 30m video must be flagged as INVALID/UNSAFE.")

        // Case 2: Safe full-length stream-copy. Must return true (valid/safe)
        val fullLengthProxyDurationMs = 1800000L
        val isFullLengthValid = TimelineMapper.isProxyDurationValid(fullLengthProxyDurationMs, fullVideoLengthMs)
        kotlin.test.assertTrue(isFullLengthValid, "Full-length stream-copy proxy must be marked as VALID.")
    }
}

