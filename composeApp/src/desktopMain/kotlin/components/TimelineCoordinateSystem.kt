package components

/**
 * Centralised coordinate-system for the telemetry timeline.
 *
 * All coordinate conversions between "video-relative seconds" (0 .. videoDurationSec)
 * and "FIT-timeline position" (ratio 0..1, or pixel-X) go through this single object.
 *
 * This eliminates the scattered `startDiffSec` calculations that previously caused
 * handle-detection and drawing mismatches.
 */
data class TimelineCoordinateSystem(
    /** Total duration shown on the timeline (FIT duration when !isTelemetryCut, else videoDuration). */
    val timelineDurationSec: Double,
    /** Duration of the loaded video in seconds. */
    val videoDurationSec: Double,
    /**
     * Offset from the FIT recording start to the video start, in seconds.
     * `startDiffSec = (adjustedStartUtc.epochSec - fitStartUtc.epochSec)`.
     * Always 0.0 when isTelemetryCut is true.
     */
    val startDiffSec: Double,
    /** Whether the telemetry has been cut to match the video range. */
    val isTelemetryCut: Boolean
) {
    // ---- derived helpers ----

    /** Timeline ratio [0..1] where the video starts. */
    val videoStartRatio: Double
        get() = if (timelineDurationSec > 0.0) startDiffSec / timelineDurationSec else 0.0

    /** Timeline ratio [0..1] where the video ends. */
    val videoEndRatio: Double
        get() = if (timelineDurationSec > 0.0) (startDiffSec + videoDurationSec) / timelineDurationSec else 1.0

    // ---- video-relative seconds ↔ timeline ratio ----

    /** Convert a video-relative second to a timeline ratio [0..1]. */
    fun videoSecToTimelineRatio(videoSec: Double): Double {
        if (timelineDurationSec <= 0.0) return 0.0
        val absoluteSec = if (isTelemetryCut) videoSec else (startDiffSec + videoSec)
        return absoluteSec / timelineDurationSec
    }

    /** Convert a timeline ratio [0..1] back to a video-relative second. */
    fun timelineRatioToVideoSec(ratio: Double): Double {
        val absoluteSec = ratio * timelineDurationSec
        return if (isTelemetryCut) absoluteSec else (absoluteSec - startDiffSec)
    }

    // ---- video-relative seconds ↔ pixel X ----

    /** Convert a video-relative second to a pixel X coordinate on a canvas of width [w]. */
    fun videoSecToPixelX(videoSec: Double, w: Float): Float {
        val ratio = videoSecToTimelineRatio(videoSec)
        return (ratio * w).toFloat()
    }

    /** Convert a pixel X coordinate to a video-relative second. */
    fun pixelXToVideoSec(pixelX: Float, w: Float): Double {
        if (w <= 0f) return 0.0
        val ratio = pixelX.toDouble() / w.toDouble()
        return timelineRatioToVideoSec(ratio)
    }

    // ---- timeline ratio ↔ pixel X (absolute, not video-relative) ----

    /** Convert a timeline-absolute second to pixel X. */
    fun absoluteSecToPixelX(absoluteSec: Double, w: Float): Float {
        if (timelineDurationSec <= 0.0) return 0f
        return ((absoluteSec / timelineDurationSec) * w).toFloat()
    }

    /** Convert pixel X to timeline-absolute second. */
    fun pixelXToAbsoluteSec(pixelX: Float, w: Float): Double {
        if (w <= 0f || timelineDurationSec <= 0.0) return 0.0
        return (pixelX.toDouble() / w.toDouble()) * timelineDurationSec
    }

    /** Whether [pixelX] falls inside the displayed video range. */
    fun containsVideoRangePixel(pixelX: Float, w: Float): Boolean {
        val start = videoStartPixelX(w)
        val end = videoEndPixelX(w)
        return pixelX in minOf(start, end)..maxOf(start, end)
    }

    /**
     * Convert a VIDEO_RANGE drag into the target FIT-timeline second for the video start.
     *
     * The trim seconds remain video-relative. Moving the range only changes [startDiffSec],
     * so every video-relative marker follows through [videoSecToPixelX].
     */
    fun videoRangeDragTargetStartSec(
        dragStartStartDiffSec: Double,
        dragStartX: Float,
        currentX: Float,
        w: Float
    ): Double {
        if (w <= 0f || timelineDurationSec <= 0.0) return dragStartStartDiffSec
        val deltaRatio = (currentX - dragStartX).toDouble() / w.toDouble()
        return dragStartStartDiffSec + deltaRatio * timelineDurationSec
    }

    /** Pixel X where the video starts on the canvas. */
    fun videoStartPixelX(w: Float): Float = (videoStartRatio * w).toFloat()

    /** Pixel X where the video ends on the canvas. */
    fun videoEndPixelX(w: Float): Float = (videoEndRatio * w).toFloat()
}
