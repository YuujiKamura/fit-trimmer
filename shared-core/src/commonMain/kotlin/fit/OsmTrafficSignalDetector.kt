package fit

import kotlinx.serialization.Serializable
import kotlin.math.*
import kotlinx.serialization.json.*

@Serializable
data class TrafficSignalNode(
    val lat: Double,
    val lon: Double,
    val type: String
)

@Serializable
data class AutoDetectedSegment(
    val id: String,
    val name: String,
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val averageGrade: Double
)

class OsmTrafficSignalDetector(
    private val httpRequester: HttpRequester,
    private val signalCache: SignalCache? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Constants for distance calculation
    private val PI = 3.141592653589793
    private fun toRadians(deg: Double): Double = deg * PI / 180.0

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latMid = (lat1 + lat2) / 2.0
        val dy = (lat1 - lat2) * 111320.0
        val dx = (lon1 - lon2) * 111320.0 * cos(toRadians(latMid))
        return sqrt(dx * dx + dy * dy)
    }

    private fun encodeUrlQuery(query: String): String {
        return query.replace(" ", "%20")
            .replace("\"", "%22")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace(";", "%3B")
    }

    suspend fun detectSegments(
        bbox: BBox,
        telemetryPoints: List<FitParser.TelemetryPoint>,
        minDistanceMeters: Double = 1000.0,
        videoPath: String? = null
    ): List<AutoDetectedSegment> {
        if (telemetryPoints.size < 2) return emptyList()

        // 1. Try to load from cache
        var signalNodes: List<TrafficSignalNode>? = null
        if (videoPath != null && signalCache != null) {
            signalNodes = signalCache.load(videoPath)
        }

        // 2. Fetch from Overpass API if cache miss
        if (signalNodes == null) {
            val query = """
                [out:json][timeout:15];
                (
                  node["highway"="traffic_signals"](${bbox.south},${bbox.west},${bbox.north},${bbox.east});
                  node["railway"="level_crossing"](${bbox.south},${bbox.west},${bbox.north},${bbox.east});
                  node["highway"="stop"](${bbox.south},${bbox.west},${bbox.north},${bbox.east});
                );
                out body;
            """.trimIndent().replace("\n", "")

            val url = "https://overpass-api.de/api/interpreter?data=${encodeUrlQuery(query)}"
            val responseText = httpRequester.get(url, mapOf("User-Agent" to "FitTrimmer/1.0"))

            val nodes = mutableListOf<TrafficSignalNode>()
            try {
                val root = json.parseToJsonElement(responseText).jsonObject
                val elements = root["elements"]?.jsonArray
                if (elements != null) {
                    for (elem in elements) {
                        val obj = elem.jsonObject
                        val lat = obj["lat"]?.jsonPrimitive?.double ?: continue
                        val lon = obj["lon"]?.jsonPrimitive?.double ?: continue
                        val tags = obj["tags"]?.jsonObject
                        val type = if (tags?.get("railway")?.jsonPrimitive?.content == "level_crossing") {
                            "level_crossing"
                        } else {
                            "traffic_signals"
                        }
                        nodes.add(TrafficSignalNode(lat, lon, type))
                    }
                }
            } catch (e: Exception) {
                // Return empty if parsing fails
                return emptyList()
            }
            signalNodes = nodes

            // Save to cache
            if (videoPath != null && signalCache != null) {
                signalCache.save(videoPath, nodes)
            }
        }

        // 3. Find indices close to any traffic signal (within 10 meters)
        // To avoid multiple splits at the same intersection, we group consecutive close points
        val splitIndices = mutableListOf<Int>()
        var inSignalZone = false
        var consecutiveStopCount = 0
        var stopStartIdx = -1

        for (i in telemetryPoints.indices) {
            val pt = telemetryPoints[i]
            
            // Check OSM nodes
            var nearSignal = false
            for (node in signalNodes) {
                if (calculateDistanceMeters(pt.lat, pt.lon, node.lat, node.lon) <= 10.0) {
                    nearSignal = true
                    break
                }
            }

            // Check physical stop (speed <= 1.0 m/s)
            val isPhysicallyStopped = pt.speed <= 1.0

            if (isPhysicallyStopped) {
                if (consecutiveStopCount == 0) {
                    stopStartIdx = i
                }
                consecutiveStopCount++
            } else {
                // If we stopped for at least 2 consecutive points (~2 seconds), mark the split
                if (consecutiveStopCount >= 2) {
                    if (!splitIndices.contains(stopStartIdx)) {
                        splitIndices.add(stopStartIdx)
                    }
                }
                consecutiveStopCount = 0
                stopStartIdx = -1
            }

            if (nearSignal) {
                if (!inSignalZone) {
                    if (!splitIndices.contains(i)) {
                        splitIndices.add(i)
                    }
                    inSignalZone = true
                }
            } else {
                inSignalZone = false
            }
        }

        // Handle case where log ends with a stop
        if (consecutiveStopCount >= 2 && stopStartIdx != -1) {
            if (!splitIndices.contains(stopStartIdx)) {
                splitIndices.add(stopStartIdx)
            }
        }

        // 4. Split segments
        val segments = mutableListOf<AutoDetectedSegment>()
        var startIdx = 0
        val boundaryIndices = splitIndices + listOf(telemetryPoints.lastIndex)

        for (endIdx in boundaryIndices) {
            if (endIdx > startIdx) {
                val startPt = telemetryPoints[startIdx]
                val endPt = telemetryPoints[endIdx]
                val distance = endPt.distance - startPt.distance
                val duration = endPt.elapsedSeconds - startPt.elapsedSeconds
                
                // Average grade check (we only want ascending segments, height difference >= 0)
                val elevDiff = endPt.elevation - startPt.elevation
                
                if (distance >= minDistanceMeters && elevDiff >= 0.0) {
                    val averageGrade = if (distance > 0) (elevDiff / distance) * 100.0 else 0.0
                    val id = "seg_${startIdx}_${endIdx}"
                    segments.add(
                        AutoDetectedSegment(
                            id = id,
                            name = "Detected Climb (${(distance/1000.0).toString().substringBefore(".")}.${((distance%1000.0)/100.0).toInt()}km)",
                            startIndex = startIdx,
                            endIndex = endIdx,
                            distanceMeters = distance,
                            durationSeconds = duration.toDouble(),
                            averageGrade = averageGrade
                        )
                    )
                }
            }
            startIdx = endIdx
        }

        return segments
    }
}

data class BBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

