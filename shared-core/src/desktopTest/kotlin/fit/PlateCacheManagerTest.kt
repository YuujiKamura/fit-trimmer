package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlateCacheManagerTest {

    @Test
    fun testFindClosestRecordWithEmptyCache() {
        val cache = VideoPlatesCache("test.mp4", emptyList())
        assertNull(cache.findClosestRecord(100))
    }

    @Test
    fun testFindClosestRecordWithSingleElement() {
        val record = PlateRecord(100, emptyList())
        val cache = VideoPlatesCache("test.mp4", listOf(record))
        
        assertEquals(record, cache.findClosestRecord(50))
        assertEquals(record, cache.findClosestRecord(100))
        assertEquals(record, cache.findClosestRecord(150))
    }

    @Test
    fun testFindClosestRecordWithMultipleElements() {
        val r1 = PlateRecord(100, emptyList())
        val r2 = PlateRecord(200, emptyList())
        val r3 = PlateRecord(300, emptyList())
        val cache = VideoPlatesCache("test.mp4", listOf(r1, r2, r3))

        // Exact matches
        assertEquals(r1, cache.findClosestRecord(100))
        assertEquals(r2, cache.findClosestRecord(200))
        assertEquals(r3, cache.findClosestRecord(300))

        // Boundary values
        assertEquals(r1, cache.findClosestRecord(50))
        assertEquals(r3, cache.findClosestRecord(350))

        // Intermediate values
        assertEquals(r1, cache.findClosestRecord(120)) // closer to 100
        assertEquals(r2, cache.findClosestRecord(180)) // closer to 200
        assertEquals(r2, cache.findClosestRecord(220)) // closer to 200
        assertEquals(r3, cache.findClosestRecord(280)) // closer to 300
    }

    @Test
    fun testShouldBlurAtWithThresholds() {
        val box = PlateBox(10, 10, 50, 50)
        val r1 = PlateRecord(1000, listOf(box))
        val cache = VideoPlatesCache("test.mp4", listOf(r1))

        // Case 1: Blur is enabled, time difference is within 1500ms
        // e.g., targetTimeMs = 2000 (diff = 1000ms < 1500ms) -> Should return the box
        val result1 = cache.shouldBlurAt(2000, isBlurEnabled = true)
        assertEquals(1, result1.size)
        assertEquals(box, result1.first())

        // Case 2: Blur is enabled, but time difference exceeds 2500ms
        // e.g., targetTimeMs = 3600 (diff = 2600ms > 2500ms) -> Should return empty
        val result2 = cache.shouldBlurAt(3600, isBlurEnabled = true)
        assertEquals(0, result2.size)

        // Case 3: Blur is disabled, even with exact time match -> Should return empty (toggled OFF behavior)
        val result3 = cache.shouldBlurAt(1000, isBlurEnabled = false)
        assertEquals(0, result3.size)
    }
}
