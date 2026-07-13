package utils

import fit.PlateBox
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PlateTrackerTest {

    @Test
    fun testHsvHistogramComputationAndBhattacharyya() {
        val tracker = PlateTracker(histogramBinCount = 8)

        // Create a 100x100 pure red image
        val redImg = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
        val gRed = redImg.createGraphics()
        gRed.color = Color.RED
        gRed.fillRect(0, 0, 100, 100)
        gRed.dispose()

        // Create a 100x100 pure blue image
        val blueImg = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
        val gBlue = blueImg.createGraphics()
        gBlue.color = Color.BLUE
        gBlue.fillRect(0, 0, 100, 100)
        gBlue.dispose()

        val box = PlateBox(0, 0, 100, 100)
        val redHist = tracker.computeHsvHistogram(redImg, box)
        val blueHist = tracker.computeHsvHistogram(blueImg, box)

        // Compare red with itself
        val distSelf = tracker.compareHistograms(redHist, redHist)
        // Compare red with blue
        val distDiff = tracker.compareHistograms(redHist, blueHist)

        assertEquals(0.0f, distSelf, 0.01f, "Distance to self should be 0")
        assertTrue(distDiff > 0.5f, "Distance to different color should be high")
    }

    @Test
    fun testIntermediateFrameTrackingMotionAndHistogram() {
        val tracker = PlateTracker(inferenceInterval = 5, histogramBinCount = 8)

        // Create frame with a red box at (20, 20)
        val frame1 = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
        val g1 = frame1.createGraphics()
        g1.color = Color.RED
        g1.fillRect(20, 20, 10, 10)
        g1.dispose()

        // Inference frame: register track
        val boxes1 = tracker.updateWithDetections(
            frameIndex = 0L,
            timeMs = 0L,
            detections = listOf(PlateBox(20, 20, 30, 30)),
            image = frame1
        )
        assertEquals(1, boxes1.size)

        // Create frame with a red box moved to (22, 22)
        val frame2 = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
        val g2 = frame2.createGraphics()
        g2.color = Color.RED
        g2.fillRect(22, 22, 10, 10)
        g2.dispose()

        // Intermediate frame: track without ONNX
        val trackedBoxes = tracker.trackIntermediateFrame(
            frameIndex = 1L,
            timeMs = 33L,
            image = frame2
        )

        assertEquals(1, trackedBoxes.size)
        val trackedBox = trackedBoxes.first()
        // Allow a small drift but it should track the red box near (22, 22, 32, 32)
        assertTrue(trackedBox.x1 in 20..24, "Should track X near 22, got ${trackedBox.x1}")
        assertTrue(trackedBox.y1 in 20..24, "Should track Y near 22, got ${trackedBox.y1}")
    }

    @Test
    fun testBacktrackingOnNewDetection() {
        val tracker = PlateTracker(inferenceInterval = 10)

        // Track initialized on frame 10 (first detection)
        // Set y closer to 0 (top boundary) so it becomes the closest boundary
        val newTrack = TrackedObject(
            id = 5,
            lastBox = PlateBox(50, 20, 60, 30),
            lastUpdatedFrame = 10L
        )

        // Perform backtracking to frame 0
        // Screen size: 100x100. Nearest boundary to (50,50) is (50, 0) or similar.
        val interpolatedMap = tracker.performBacktracking(
            newTrack = newTrack,
            fromFrameIndex = 10L,
            videoWidth = 100,
            videoHeight = 100
        )

        // Should return interpolated boxes for frames 0 to 9
        assertEquals(10, interpolatedMap.size, "Should contain 10 interpolated frames (0 to 9)")
        
        // Frame 0 should be close to screen boundary (top border y=0 in this case)
        val frame0Box = interpolatedMap[0L]
        assertNotNull(frame0Box)
        assertTrue(frame0Box.y1 <= 5, "Frame 0 box should be close to screen boundary, got Y1=${frame0Box.y1}")
    }

    @Test
    fun testTrackingWithOversizedBoxDoesNotCrash() {
        val tracker = PlateTracker(histogramBinCount = 8)
        
        // Track initialized with an oversized box (width 150 > image width 100)
        val oversizedTrack = TrackedObject(
            id = 9,
            lastBox = PlateBox(0, 0, 150, 50),
            lastUpdatedFrame = 0L
        )
        tracker.activeTracks.add(oversizedTrack)
        
        val frame = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
        
        // This should not throw IllegalArgumentException: Cannot coerce value to an empty range
        val tracked = tracker.trackIntermediateFrame(1L, 33L, frame)
        assertEquals(1, tracked.size)
    }

    @Test
    fun testBacktrackingIsLimitedToMaxFrames() {
        val tracker = PlateTracker(histogramBinCount = 8)
        val newTrack = TrackedObject(
            id = 1,
            lastBox = PlateBox(40, 40, 60, 60),
            lastUpdatedFrame = 100L
        )
        
        val interpolatedMap = tracker.performBacktracking(
            newTrack = newTrack,
            fromFrameIndex = 100L,
            videoWidth = 100,
            videoHeight = 100
        )
        
        // We expect it to be limited to max 10 frames (from 90 to 99)
        assertEquals(10, interpolatedMap.size, "Backtracking should be limited to 10 frames")
        assertTrue(interpolatedMap.containsKey(90L), "Should contain frame 90")
        assertFalse(interpolatedMap.containsKey(0L), "Should not contain frame 0 (too far back)")
    }
}
