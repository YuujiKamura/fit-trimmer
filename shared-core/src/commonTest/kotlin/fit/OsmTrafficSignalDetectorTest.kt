package fit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsmTrafficSignalDetectorTest {

    private class MockHttpRequester(val jsonResponse: String) : HttpRequester {
        var lastUrl: String? = null
        override suspend fun get(url: String, headers: Map<String, String>): String {
            lastUrl = url
            return jsonResponse
        }
    }

    @Test
    fun testDetectSegmentsSuccess() = runTest {
        // Mock response containing 3 nodes (2 traffic signals, 1 level crossing)
        // Signal A: Near point 10 (lat=32.801, lon=130.801) -> SHOULD trigger split
        // Signal B: Far from route (lat=32.900, lon=130.900) -> SHOULD NOT trigger split
        // Crossing C: Near point 30 (lat=32.803, lon=130.803) -> SHOULD trigger split
        val mockJson = """
            {
                "elements": [
                    {
                        "type": "node",
                        "id": 1001,
                        "lat": 32.801005,
                        "lon": 130.801005,
                        "tags": { "highway": "traffic_signals" }
                    },
                    {
                        "type": "node",
                        "id": 1002,
                        "lat": 32.900000,
                        "lon": 130.900000,
                        "tags": { "highway": "traffic_signals" }
                    },
                    {
                        "type": "node",
                        "id": 1003,
                        "lat": 32.803005,
                        "lon": 130.803005,
                        "tags": { "railway": "level_crossing" }
                    }
                ]
            }
        """.trimIndent()

        val mockHttp = MockHttpRequester(mockJson)
        val detector = OsmTrafficSignalDetector(mockHttp)

        // Generate mock telemetry points (a route from lat=32.800, lon=130.800 to lat=32.805, lon=130.805)
        // distance increases by 100m per point, elevation goes up
        val points = mutableListOf<FitParser.TelemetryPoint>()
        for (i in 0..40) {
            val progress = i / 40.0
            points.add(
                FitParser.TelemetryPoint(
                    timestamp = 1782000000.0 + i * 10,
                    speed = 10.0,
                    power = 200.0,
                    cadence = 90.0,
                    heartRate = 140.0,
                    elevation = 100.0 + i * 5, // ascending
                    grade = 5.0,
                    lat = 32.800 + progress * 0.005, // 32.800 to 32.805
                    lon = 130.800 + progress * 0.005, // 130.800 to 130.805
                    distance = i * 100.0, // 0m to 4000m
                    elapsedSeconds = i * 10
                )
            )
        }

        // BBox: south=32.79, west=130.79, north=32.91, east=130.91
        val bbox = BBox(32.79, 130.79, 32.91, 130.91)
        val segments = detector.detectSegments(bbox, points)

        // Verify URL contains expected parameters
        assertTrue(mockHttp.lastUrl != null)
        assertTrue(mockHttp.lastUrl!!.contains("32.79"))
        assertTrue(mockHttp.lastUrl!!.contains("traffic_signals"))

        // Expected splits:
        // Point 10 (approx lat=32.80125, lon=130.80125) is close to Signal A (dist ~= 38m, wait, let's make it closer)
        // Let's adjust point coords or signal coords so they are within 10m.
        // Point 8: lat = 32.800 + 8/40*0.005 = 32.80100, lon = 130.800 + 8/40*0.005 = 130.80100 -> matches Signal A exactly!
        // Point 24: lat = 32.800 + 24/40*0.005 = 32.80300, lon = 130.800 + 24/40*0.005 = 130.80300 -> matches Crossing C exactly!
        
        // Segments should be split at Point 8 and Point 24.
        // Segments to check:
        // Segment 1: index 0 to 8 (distance 800m) -> too short (if threshold is 1000m)
        // Segment 2: index 8 to 24 (distance 1600m) -> matches! (over 1000m)
        // Segment 3: index 24 to 40 (distance 1600m) -> matches! (over 1000m)

        // (We will use 1000m as the minimum distance for the test segment threshold)
        assertEquals(2, segments.size)
        
        val seg1 = segments[0]
        assertEquals(8, seg1.startIndex)
        assertEquals(24, seg1.endIndex)
        assertEquals(1600.0, seg1.distanceMeters)

        val seg2 = segments[1]
        assertEquals(24, seg2.startIndex)
        assertEquals(40, seg2.endIndex)
        assertEquals(1600.0, seg2.distanceMeters)
    }

    @Test
    fun testDetectSegmentsWithSpeedDrop() = runTest {
        // Mock response with empty elements (no signals in OSM)
        val mockJson = """{ "elements": [] }"""
        val mockHttp = MockHttpRequester(mockJson)
        val detector = OsmTrafficSignalDetector(mockHttp)

        // Generate mock telemetry points with a stop in the middle (points 18, 19, 20)
        val points = mutableListOf<FitParser.TelemetryPoint>()
        for (i in 0..40) {
            val progress = i / 40.0
            val speed = if (i in 18..20) 0.0 else 10.0
            val cadence = 90.0
            
            points.add(
                FitParser.TelemetryPoint(
                    1782000000.0 + i * 10,
                    speed,
                    if (speed == 0.0) 0.0 else 200.0,
                    cadence,
                    140.0,
                    100.0 + i * 5,
                    5.0,
                    32.800 + progress * 0.005,
                    130.800 + progress * 0.005,
                    i * 100.0,
                    i * 10,
                    0.0
                )
            )
        }

        // BBox doesn't query any elements
        val bbox = BBox(32.79, 130.79, 32.81, 130.81)
        val segments = detector.detectSegments(bbox, points)

        // Point 18 is where speed drops to 0.0.
        // Segments:
        // Segment 1: index 0 to 18 (distance 1800m) -> matches (> 1000m)
        // Segment 2: index 18 to 40 (distance 2200m) -> matches (> 1000m)
        assertEquals(2, segments.size)

        val seg1 = segments[0]
        assertEquals(0, seg1.startIndex)
        assertEquals(18, seg1.endIndex)
        assertEquals(1800.0, seg1.distanceMeters)

        val seg2 = segments[1]
        assertEquals(18, seg2.startIndex)
        assertEquals(40, seg2.endIndex)
        assertEquals(2200.0, seg2.distanceMeters)
    }

    private class MockSignalCache : SignalCache {
        val storage = mutableMapOf<String, List<TrafficSignalNode>>()
        var loadCount = 0
        var saveCount = 0
        
        override fun load(videoPath: String): List<TrafficSignalNode>? {
            loadCount++
            return storage[videoPath]
        }

        override fun save(videoPath: String, nodes: List<TrafficSignalNode>) {
            saveCount++
            storage[videoPath] = nodes
        }
    }

    @Test
    fun testDetectSegmentsWithCache() = runTest {
        val mockJson = """{
            "elements": [
                {
                    "type": "node",
                    "id": 1001,
                    "lat": 32.801000,
                    "lon": 130.801000,
                    "tags": { "highway": "traffic_signals" }
                }
            ]
        }""".trimIndent()

        var getCallCount = 0
        val countingHttp = object : HttpRequester {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                getCallCount++
                return mockJson
            }
        }

        val cache = MockSignalCache()
        val detector = OsmTrafficSignalDetector(countingHttp, cache)

        val points = listOf(
            FitParser.TelemetryPoint(1782000000.0, 10.0, 200.0, 90.0, 140.0, 100.0, 5.0, 32.800, 130.800, 0.0, 0, 0.0),
            FitParser.TelemetryPoint(1782000010.0, 10.0, 200.0, 90.0, 140.0, 100.0, 5.0, 32.801, 130.801, 100.0, 10, 0.0),
            FitParser.TelemetryPoint(1782000020.0, 10.0, 200.0, 90.0, 140.0, 100.0, 5.0, 32.802, 130.802, 200.0, 20, 0.0)
        )

        val bbox = BBox(32.79, 130.79, 32.83, 130.83)
        val videoPath = "my_ride_video.mp4"

        // First run (Cache Miss)
        val result1 = detector.detectSegments(bbox, points, minDistanceMeters = 50.0, videoPath = videoPath)
        assertEquals(1, getCallCount, "HTTP Requester should be called once on cache miss")
        assertEquals(1, cache.loadCount, "Cache load should be called")
        assertEquals(1, cache.saveCount, "Cache save should be called")
        
        // Second run (Cache Hit)
        val result2 = detector.detectSegments(bbox, points, minDistanceMeters = 50.0, videoPath = videoPath)
        assertEquals(1, getCallCount, "HTTP Requester should NOT be called again on cache hit")
        assertEquals(2, cache.loadCount, "Cache load should be called again")
        assertEquals(1, cache.saveCount, "Cache save should NOT be called again")
        
        assertEquals(result1.size, result2.size)
    }
}
