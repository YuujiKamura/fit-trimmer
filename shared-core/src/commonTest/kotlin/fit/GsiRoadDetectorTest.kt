package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GsiRoadDetectorTest {

    @Test
    fun testDeg2Tile() {
        // 熊本市の特定の座標
        val lat = 32.854619
        val lon = 130.779864
        val (x, y) = GsiRoadDetector.deg2tile(lat, lon, 16)
        assertEquals(56575, x)
        assertEquals(26429, y)
    }

    @Test
    fun testFindClosestRoad() {
        val dummyGeoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [130.770, 32.850],
                  [130.780, 32.855]
                ]
              },
              "properties": {
                "rdCtg": "都道府県道",
                "name": "熊本菊陽線",
                "comName": "熊本菊陽線"
              }
            },
            {
              "type": "Feature",
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [130.700, 32.800],
                  [130.710, 32.810]
                ]
              },
              "properties": {
                "rdCtg": "一般国道",
                "name": "国道57号"
              }
            }
          ]
        }
        """.trimIndent()

        // 熊本菊陽線の線分上に近い座標
        val lat = 32.854
        val lon = 130.779
        val info = GsiRoadDetector.findClosestRoad(lat, lon, dummyGeoJson)
        
        assertNotNull(info)
        assertEquals("都道府県道", info.rdCtg)
        assertEquals("熊本菊陽線", info.name)
        assertEquals("熊本菊陽線", info.comName)
    }

    @Test
    fun testFindClosestRoadWithCorruptedGeoJson() {
        val lat = 32.854
        val lon = 130.779

        // 1. Completely invalid JSON
        val corruptedJson = "{ invalid json }"
        val result1 = GsiRoadDetector.findClosestRoad(lat, lon, corruptedJson)
        kotlin.test.assertNull(result1, "Should return null for invalid JSON structure")

        // 2. Empty JSON object
        val emptyJson = "{}"
        val result2 = GsiRoadDetector.findClosestRoad(lat, lon, emptyJson)
        kotlin.test.assertNull(result2, "Should return null for empty JSON object")

        // 3. API Error Response
        val apiErrorJson = """{"error": "Too Many Requests", "status": 429}"""
        val result3 = GsiRoadDetector.findClosestRoad(lat, lon, apiErrorJson)
        kotlin.test.assertNull(result3, "Should return null when API returns an error response instead of GeoJSON")
    }
}

