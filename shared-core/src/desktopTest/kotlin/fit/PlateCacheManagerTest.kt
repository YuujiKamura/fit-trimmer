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

        // Within 300ms boundary limit
        val res4 = cacheSingle.shouldBlurAt(750, isBlurEnabled = true) // 250ms before
        assertEquals(1, res4.size)
        assertEquals(box1, res4.first())
    }
}
