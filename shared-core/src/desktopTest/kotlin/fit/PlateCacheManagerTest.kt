package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlateCacheManagerTest {

    @Test
    fun testFindNeighborRecordsWithEmptyCache() {
        val cache = VideoPlatesCache("test.mp4", emptyList())
        val neighbors = cache.findNeighborRecords(100)
        assertNull(neighbors.first)
        assertNull(neighbors.second)
    }

    @Test
    fun testFindNeighborRecordsWithSingleElement() {
        val record = PlateRecord(100, emptyList())
        val cache = VideoPlatesCache("test.mp4", listOf(record))
        
        val left = cache.findNeighborRecords(50)
        assertNull(left.first)
        assertEquals(record, left.second)

        val exact = cache.findNeighborRecords(100)
        assertEquals(record, exact.first)
        assertEquals(record, exact.second)

        val right = cache.findNeighborRecords(150)
        assertEquals(record, right.first)
        assertNull(right.second)
    }

    @Test
    fun testFindNeighborRecordsWithMultipleElements() {
        val r1 = PlateRecord(100, emptyList())
        val r2 = PlateRecord(200, emptyList())
        val r3 = PlateRecord(300, emptyList())
        val cache = VideoPlatesCache("test.mp4", listOf(r1, r2, r3))

        // Exact matches
        assertEquals(Pair(r1, r1), cache.findNeighborRecords(100))
        assertEquals(Pair(r2, r2), cache.findNeighborRecords(200))
        assertEquals(Pair(r3, r3), cache.findNeighborRecords(300))

        // Boundary values
        assertEquals(Pair(null, r1), cache.findNeighborRecords(50))
        assertEquals(Pair(r3, null), cache.findNeighborRecords(350))

        // Intermediate values
        assertEquals(Pair(r1, r2), cache.findNeighborRecords(150))
        assertEquals(Pair(r2, r3), cache.findNeighborRecords(250))
    }

    @Test
    fun testShouldBlurAtWithInterpolation() {
        val box1 = PlateBox(10, 10, 50, 50)
        val box2 = PlateBox(20, 20, 60, 60)
        val r1 = PlateRecord(1000, listOf(box1))
        val r2 = PlateRecord(1200, listOf(box2))
        val cache = VideoPlatesCache("test.mp4", listOf(r1, r2))

        // Exact match
        val res1 = cache.shouldBlurAt(1000, isBlurEnabled = true)
        assertEquals(1, res1.size)
        assertEquals(box1, res1.first())

        // Nearest neighbor at mid-point (1100). Since both are 100ms away, minByOrNull picks the first (1000).
        val res2 = cache.shouldBlurAt(1100, isBlurEnabled = true)
        assertEquals(1, res2.size)
        assertEquals(box1, res2.first())

        // Boundary limit: 10.4s to 10.8s case where distance is 400ms.
        // If single record exists at 1000, should not blur at 600 (400ms before)
        val cacheSingle = VideoPlatesCache("test.mp4", listOf(r1))
        val res3 = cacheSingle.shouldBlurAt(600, isBlurEnabled = true) // 400ms before
        assertEquals(0, res3.size) // Limit is 300ms for boundary/outer

        // Within 300ms boundary limit (exact fallback, no dilation scaling anymore)
        val res4 = cacheSingle.shouldBlurAt(750, isBlurEnabled = true) // 250ms before
        assertEquals(1, res4.size)
        assertEquals(PlateBox(10, 10, 50, 50), res4.first())
    }

    @Test
    fun testPlateCoordinateMapperUsesCacheSourceSize() {
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = emptyList(),
            sourceWidth = 2704,
            sourceHeight = 1520
        )
        val mapped = PlateCoordinateMapper.mapToTarget(
            box = PlateBox(1352, 760, 2704, 1520),
            sourceWidth = cache.sourceWidth,
            sourceHeight = cache.sourceHeight,
            targetWidth = 1352f,
            targetHeight = 760f
        )

        assertEquals(676f, mapped.x)
        assertEquals(380f, mapped.y)
        assertEquals(676f, mapped.width)
        assertEquals(380f, mapped.height)
    }

    @Test
    fun testPlateMaskExpanderSupportsWideMask() {
        val expanded = PlateMaskExpander.expand(
            box = PlateBox(100, 100, 200, 140),

            sourceWidth = 500,
            sourceHeight = 300
        )

        // With 1.35x scaling on 100x40 box centered at (150, 120):
        // newW = 135, newH = 54
        // x1 = 150 - 67.5 = 82, y1 = 120 - 27 = 93
        // x2 = 150 + 67.5 = 217, y2 = 120 + 27 = 147
        assertEquals(82, expanded.x1)
        assertEquals(93, expanded.y1)
        assertEquals(217, expanded.x2)
        assertEquals(147, expanded.y2)
    }

    @Test
    fun testPlateMaskExpanderAddsSmallPaddingForPlateMode() {
        val expanded = PlateMaskExpander.expand(
            box = PlateBox(100, 100, 200, 140),

            sourceWidth = 500,
            sourceHeight = 300
        )

        // Same as above due to fixed 1.35x scale regardless of expandRatio param
        assertEquals(82, expanded.x1)
        assertEquals(93, expanded.y1)
        assertEquals(217, expanded.x2)
        assertEquals(147, expanded.y2)
    }

    @Test
    fun testBuildMappedMaskFramesMatchesBlurTimingAndMapping() {
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = listOf(
                PlateRecord(0, listOf(PlateBox(100, 100, 200, 140))),
                PlateRecord(200, listOf(PlateBox(120, 120, 220, 160)))
            ),
            sourceWidth = 500,
            sourceHeight = 300
        )

        val frames = cache.buildMappedMaskFrames(
            totalFrames = 4,
            fps = 10.0,
            isBlurEnabled = true,

            fallbackSourceWidth = 1920,
            fallbackSourceHeight = 1080,
            targetWidth = 1000f,
            targetHeight = 600f
        )

        assertEquals(4, frames.size)
        assertEquals(1, frames[0].size) // 0ms exact
        assertEquals(1, frames[1].size) // 100ms nearest (0ms box)
        assertEquals(1, frames[2].size) // 200ms exact
        assertEquals(1, frames[3].size) // 300ms nearest (200ms box)

        val first = frames[0].first()
        // Box: 100, 100, 200, 140
        // Expanded: 82, 93, 217, 147
        // Mapped (500x300 -> 1000x600, scale=2.0)
        assertEquals(164f, first.x)
        assertEquals(186f, first.y)
        assertEquals(270f, first.width)
        assertEquals(108f, first.height)

        val nearest = frames[1].first()
        assertEquals(164f, nearest.x)
        assertEquals(186f, nearest.y)
        assertEquals(270f, nearest.width)
        assertEquals(108f, nearest.height)
    }

    @Test
    fun testBuildMappedMaskFramesUsesSourceStartOffsetForTrimmedOutput() {
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = listOf(
                PlateRecord(10_000, listOf(PlateBox(100, 100, 200, 140))),
                PlateRecord(10_200, listOf(PlateBox(120, 120, 220, 160)))
            ),
            sourceWidth = 500,
            sourceHeight = 300
        )

        val frames = cache.buildMappedMaskFrames(
            totalFrames = 3,
            fps = 10.0,
            isBlurEnabled = true,

            fallbackSourceWidth = 1920,
            fallbackSourceHeight = 1080,
            targetWidth = 1000f,
            targetHeight = 600f,
            sourceStartTimeMs = 10_000
        )

        assertEquals(3, frames.size)
        assertEquals(1, frames[0].size)
        assertEquals(1, frames[1].size)
        assertEquals(1, frames[2].size)

        val first = frames[0].first()
        // Box: 100, 100, 200, 140
        // Expanded: 82, 93, 217, 147
        // Mapped (500x300 -> 1000x600, scale=2.0)
        assertEquals(164f, first.x)
        assertEquals(186f, first.y)
    }

    @Test
    fun testInterpolationResolutionBasedDistance() {
        // sourceWidth is 2704 (2.7K). The box moves by 600px, which exceeds the old 400px limit,
        // but is well within the new dynamic 50% limit (1352px).
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = listOf(
                PlateRecord(0, listOf(PlateBox(100, 100, 200, 140))),
                PlateRecord(200, listOf(PlateBox(700, 100, 800, 140))) // moves from x=100 to x=700 (deltaX = 600px)
            ),
            sourceWidth = 2704,
            sourceHeight = 1520
        )

        val frames = cache.buildMappedMaskFrames(
            totalFrames = 3,
            fps = 10.0,
            isBlurEnabled = true,

            fallbackSourceWidth = 2704,
            fallbackSourceHeight = 1520,
            targetWidth = 2704f,
            targetHeight = 1520f
        )

        // Interpolation should succeed, frame 1 (100ms) should have 1 interpolated box
        assertEquals(3, frames.size)
        assertEquals(1, frames[1].size) // 100ms should be interpolated
    }

    @Test
    fun testInterpolationAreaRatioMismatchGhostPrevention() {
        // The box moves slightly (deltaX = 50px), but the area changes by 4x (100x100 -> 200x200),
        // which represents different vehicles (e.g. far vehicle vs close vehicle).
        // Interpolation should be blocked.
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = listOf(
                PlateRecord(0, listOf(PlateBox(100, 100, 200, 200))),       // Area = 10_000
                PlateRecord(200, listOf(PlateBox(150, 100, 350, 300)))       // Area = 40_000 (ratio = 4.0 >= 3.0)
            ),
            sourceWidth = 2704,
            sourceHeight = 1520
        )

        val frames = cache.buildMappedMaskFrames(
            totalFrames = 3,
            fps = 10.0,
            isBlurEnabled = true,

            fallbackSourceWidth = 2704,
            fallbackSourceHeight = 1520,
            targetWidth = 2704f,
            targetHeight = 1520f
        )

        // Interpolation (Lerp) is disabled globally. Nearest neighbor is used.
        // At 100ms, both 0ms and 200ms are 100ms away. minByOrNull picks 0ms record.
        assertEquals(3, frames.size)
        assertEquals(1, frames[1].size) 
        
        val box = frames[1].first()
        // Expanded 0ms box: x1=100 -> center=150, width=100. 1.35x scale -> newW=135. x1 = 150 - 67.5 = 82
        // mapped x = 82 * (2704 / 2704) = 82f
        assertEquals(82f, box.x)
    }

    @Test
    fun testFilledGapsBackfill() {
        // Frame 1 (0ms): plate detected at (100, 100, 200, 150)
        // Frame 2 (250ms): MISSING
        // Frame 3 (500ms): plate detected at (150, 100, 250, 150) -> moves +50px
        
        val box0 = PlateBox(100, 100, 200, 150)
        val box500 = PlateBox(150, 100, 250, 150)
        
        val cache = VideoPlatesCache(
            videoPath = "test.mp4",
            records = listOf(
                PlateRecord(0, listOf(box0)),
                PlateRecord(250, emptyList()), // Empty record
                PlateRecord(500, listOf(box500))
            )
        )
        
        // Fill gaps with maxGapMs = 1000L and iouThreshold = 0.3
        val filled = cache.filledGaps(1000L, 0.3f)
        
        // Should have 3 records
        assertEquals(3, filled.records.size)
        
        // Middle record (250ms) should now be filled with interpolated box
        val middleBoxes = filled.records[1].boxes
        assertEquals(1, middleBoxes.size)
        
        // Expected interpolation at 250ms (exactly half-way)
        val expectedMid = PlateBox(125, 100, 225, 150)
        assertEquals(expectedMid.x1, middleBoxes[0].x1)
        assertEquals(expectedMid.x2, middleBoxes[0].x2)
    }
}
