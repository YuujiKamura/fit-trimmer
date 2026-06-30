package utils

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
}
