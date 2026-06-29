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
}
