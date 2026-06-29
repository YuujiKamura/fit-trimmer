package fit

object TimelineMapper {
    /**
     * Calculates the actual synchronized playhead time in milliseconds.
     * Handles short proxy videos (e.g. 30s clips of a 30m video) to ensure 1:1 real-time speed.
     *
     * @param sliderPos The raw slider position from the media player (0f to 1000f)
     * @param durationMs The actual loaded media file duration in milliseconds
     * @param fullVideoLengthMs The full video length of the session in milliseconds
     */
    fun calculateCurrentTimeMs(
        sliderPos: Float,
        durationMs: Long,
        fullVideoLengthMs: Long
    ): Long {
        val isProxy = durationMs > 0L && fullVideoLengthMs > 0L && durationMs < (fullVideoLengthMs * 0.9).toLong()
        val currentDuration = if (isProxy) durationMs else (if (fullVideoLengthMs > 0L) fullVideoLengthMs else durationMs)
        return ((sliderPos / 1000f) * currentDuration).toLong()
    }

    /**
     * Validates if the loaded proxy video duration is safe for seek synchronization.
     * A short cropped video (like 30s of a 30m video) is dangerous because it desyncs
     * the playhead at arbitrary seek positions.
     *
     * @return true if the duration is safe, false if it is a cropped anomaly that should be rejected.
     */
    fun isProxyDurationValid(durationMs: Long, fullVideoLengthMs: Long): Boolean {
        if (durationMs <= 0L || fullVideoLengthMs <= 0L) return true
        // If the duration is shorter than 95% of the full video length,
        // it's a cropped proxy and is invalid/unsafe for playhead sync.
        if (durationMs < (fullVideoLengthMs * 0.95).toLong()) {
            return false
        }
        return true
    }
}
