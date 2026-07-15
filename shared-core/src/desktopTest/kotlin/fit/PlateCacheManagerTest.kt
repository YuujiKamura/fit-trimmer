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

        // Linear interpolation at exactly mid-point (alpha = 0.5)
        // x1: 10 -> 20 => 15
        // y1: 10 -> 20 => 15
        // x2: 50 -> 60 => 55
        // y2: 50 -> 60 => 55
        val res2 = cache.shouldBlurAt(1100, isBlurEnabled = true)
        assertEquals(1, res2.size)
        assertEquals(PlateBox(15, 15, 55, 55), res2.first())

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
            cache = cache,
            fallbackSourceWidth = 1920,
            fallbackSourceHeight = 1080,
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
            expandRatio = 1.5,
            sourceWidth = 500,
            sourceHeight = 300
        )

        assertEquals(0, expanded.x1)
        assertEquals(10, expanded.y1)
        assertEquals(350, expanded.x2)
        assertEquals(230, expanded.y2)
    }

    @Test
    fun testPlateMaskExpanderAddsSmallPaddingForPlateMode() {
        val expanded = PlateMaskExpander.expand(
            box = PlateBox(100, 100, 200, 140),
            expandRatio = 0.2,
            sourceWidth = 500,
            sourceHeight = 300
        )

        assertEquals(80, expanded.x1)
        assertEquals(88, expanded.y1)
        assertEquals(220, expanded.x2)
        assertEquals(152, expanded.y2)
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
            expandRatio = 0.2,
            fallbackSourceWidth = 1920,
            fallbackSourceHeight = 1080,
            targetWidth = 1000f,
            targetHeight = 600f
        )

        assertEquals(4, frames.size)
        assertEquals(1, frames[0].size) // 0ms exact
        assertEquals(1, frames[1].size) // 100ms interpolated
        assertEquals(1, frames[2].size) // 200ms exact
        assertEquals(1, frames[3].size) // 300ms boundary hold

        val first = frames[0].first()
        assertEquals(160f, first.x)
        assertEquals(176f, first.y)
        assertEquals(280f, first.width)
        assertEquals(128f, first.height)

        val interpolated = frames[1].first()
        assertEquals(180f, interpolated.x)
        assertEquals(196f, interpolated.y)
        assertEquals(280f, interpolated.width)
        assertEquals(128f, interpolated.height)
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
            expandRatio = 0.2,
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
        assertEquals(160f, first.x)
        assertEquals(176f, first.y)
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
            expandRatio = 0.2,
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
            expandRatio = 0.2,
            fallbackSourceWidth = 2704,
            fallbackSourceHeight = 1520,
            targetWidth = 2704f,
            targetHeight = 1520f
        )

        // Interpolation (Lerp) should be blocked due to area mismatch.
        // Instead, fallback single-sided next-frame block should be outputted as-is (alpha >= 0.5 fallback).
        assertEquals(3, frames.size)
        assertEquals(1, frames[1].size) 
        
        val box = frames[1].first()
        // If Lerp was active, x would be 95f. Since Lerp is blocked, x must be 110f (exact next box expanded).
        assertEquals(110f, box.x)
    }
}
