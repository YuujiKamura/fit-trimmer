package components

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineCoordinateSystemTest {

    // Helper to build a coordinate system for FIT-wide mode (isTelemetryCut=false)
    private fun fitWideCoords(
        fitDurationSec: Double = 3600.0,   // 1h FIT recording
        videoDurationSec: Double = 600.0,  // 10min video
        startDiffSec: Double = 120.0,      // video starts 2min into FIT
        canvasWidth: Float = 1000f
    ) = TimelineCoordinateSystem(
        timelineDurationSec = fitDurationSec,
        videoDurationSec = videoDurationSec,
        startDiffSec = startDiffSec,
        isTelemetryCut = false
    )

    // Helper for isTelemetryCut=true mode
    private fun cutCoords(
        videoDurationSec: Double = 600.0
    ) = TimelineCoordinateSystem(
        timelineDurationSec = videoDurationSec,
        videoDurationSec = videoDurationSec,
        startDiffSec = 0.0,
        isTelemetryCut = true
    )

    // --- toAbsoluteRatio: Convert a video-relative second to timeline ratio ---

    @Test
    fun trimStartRatioReflectsStartDiff() {
        val coords = fitWideCoords()
        // trimStart=0.0 in video => absolute 120s on FIT timeline => ratio 120/3600
        val ratio = coords.videoSecToTimelineRatio(0.0)
        assertEquals(120.0 / 3600.0, ratio, 1e-9)
    }

    @Test
    fun trimEndRatioReflectsStartDiff() {
        val coords = fitWideCoords()
        // trimEnd=600.0 in video => absolute 720s on FIT timeline => ratio 720/3600
        val ratio = coords.videoSecToTimelineRatio(600.0)
        assertEquals(720.0 / 3600.0, ratio, 1e-9)
    }

    @Test
    fun playheadRatioReflectsStartDiff() {
        val coords = fitWideCoords()
        // playhead at 300s in video => absolute 420s on FIT timeline
        val ratio = coords.videoSecToTimelineRatio(300.0)
        assertEquals(420.0 / 3600.0, ratio, 1e-9)
    }

    @Test
    fun videoSecToTimelineRatioInCutMode() {
        val coords = cutCoords()
        // In cut mode, startDiffSec=0, so videoSec maps directly
        val ratio = coords.videoSecToTimelineRatio(300.0)
        assertEquals(300.0 / 600.0, ratio, 1e-9)
    }

    // --- toVideoRelativeSec: Convert a timeline ratio back to video-relative seconds ---

    @Test
    fun timelineRatioToVideoSec() {
        val coords = fitWideCoords()
        // ratio 0.2 => absolute 720s on 3600 timeline => video relative = 720 - 120 = 600
        val videoSec = coords.timelineRatioToVideoSec(720.0 / 3600.0)
        assertEquals(600.0, videoSec, 1e-9)
    }

    @Test
    fun timelineRatioToVideoSecAtStart() {
        val coords = fitWideCoords()
        // ratio at startDiff => video relative = 0
        val videoSec = coords.timelineRatioToVideoSec(120.0 / 3600.0)
        assertEquals(0.0, videoSec, 1e-9)
    }

    @Test
    fun timelineRatioToVideoSecInCutMode() {
        val coords = cutCoords()
        val videoSec = coords.timelineRatioToVideoSec(0.5)
        assertEquals(300.0, videoSec, 1e-9)
    }

    // --- videoStartRatio / videoEndRatio ---

    @Test
    fun videoStartRatioMatchesStartDiff() {
        val coords = fitWideCoords()
        assertEquals(120.0 / 3600.0, coords.videoStartRatio, 1e-9)
    }

    @Test
    fun videoEndRatioMatchesStartDiffPlusVideoDuration() {
        val coords = fitWideCoords()
        assertEquals(720.0 / 3600.0, coords.videoEndRatio, 1e-9)
    }

    // --- toPixelX / fromPixelX round-trip ---

    @Test
    fun pixelRoundTrip() {
        val coords = fitWideCoords()
        val w = 1000f
        val videoSec = 250.0
        val px = coords.videoSecToPixelX(videoSec, w)
        val backSec = coords.pixelXToVideoSec(px, w)
        assertEquals(videoSec, backSec, 0.01, "Float round-trip tolerance for pixel coords")
    }

    @Test
    fun pixelRoundTripCutMode() {
        val coords = cutCoords()
        val w = 800f
        val videoSec = 150.0
        val px = coords.videoSecToPixelX(videoSec, w)
        val backSec = coords.pixelXToVideoSec(px, w)
        assertEquals(videoSec, backSec, 0.01, "Float round-trip tolerance for pixel coords")
    }

    // --- Edge: startDiffSec=0 (video and FIT start at the same time) ---

    @Test
    fun zeroDiffBehavesLikeCutMode() {
        val coords = TimelineCoordinateSystem(
            timelineDurationSec = 600.0,
            videoDurationSec = 600.0,
            startDiffSec = 0.0,
            isTelemetryCut = false
        )
        val ratio = coords.videoSecToTimelineRatio(300.0)
        assertEquals(0.5, ratio, 1e-9)
    }

    @Test
    fun videoRangeDragMovesStartOnFitTimelineScale() {
        val coords = fitWideCoords(fitDurationSec = 3600.0, videoDurationSec = 600.0, startDiffSec = 120.0)
        val targetStartSec = coords.videoRangeDragTargetStartSec(
            dragStartStartDiffSec = 120.0,
            dragStartX = 200f,
            currentX = 300f,
            w = 1000f
        )

        assertEquals(480.0, targetStartSec, 1e-9)
    }

    @Test
    fun videoRangePixelHitTestUsesDisplayedRange() {
        val coords = fitWideCoords(fitDurationSec = 3600.0, videoDurationSec = 600.0, startDiffSec = 120.0)
        val w = 1000f

        assertEquals(false, coords.containsVideoRangePixel(20f, w))
        assertEquals(true, coords.containsVideoRangePixel(100f, w))
        assertEquals(false, coords.containsVideoRangePixel(250f, w))
    }
}
