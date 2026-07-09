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
    val averageGrade: Double,
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val endLat: Double = 0.0,
    val endLon: Double = 0.0,
    val startElev: Double = 0.0,
    val endElev: Double = 0.0,
    val minGrade: Double = 0.0,
    val maxGrade: Double = 0.0,
    val startFitTimestamp: Double = 0.0,
    val endFitTimestamp: Double = 0.0
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
        videoPath: String? = null,
        autoPauseGapSeconds: Double = 3.0,
        minSearchGrade: Double = 0.0,
        maxSearchGrade: Double = 15.0
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
        var prevPt: FitParser.TelemetryPoint? = null
        var outOfGradeCount = 0

        for (i in telemetryPoints.indices) {
            val pt = telemetryPoints[i]
            
            // Check time gap (auto-pause detection)
            if (prevPt != null) {
                val timeGap = pt.timestamp - prevPt.timestamp
                if (timeGap >= autoPauseGapSeconds) {
                    if (!splitIndices.contains(i)) {
                        splitIndices.add(i)
                    }
                }
            }
            prevPt = pt

            // Check grade bounds (>= 5 consecutive seconds out of bounds triggers split)
            val isOutOfGrade = pt.grade < minSearchGrade || pt.grade > maxSearchGrade
            if (isOutOfGrade) {
                outOfGradeCount++
            } else {
                if (outOfGradeCount >= 5) {
                    val splitIdx = maxOf(0, i - outOfGradeCount)
                    if (!splitIndices.contains(splitIdx)) {
                        splitIndices.add(splitIdx)
                    }
                }
                outOfGradeCount = 0
            }

            // Check U-Turn (reverse direction on flat terrain, avoiding hairpins)
            if (i >= 10 && i < telemetryPoints.size - 10) {
                val ptPrev = telemetryPoints[i - 10]
                val ptNext = telemetryPoints[i + 10]
                
                val latMid = pt.lat
                val cosLat = kotlin.math.cos(latMid * kotlin.math.PI / 180.0)
                
                val dLat1 = pt.lat - ptPrev.lat
                val dLon1 = (pt.lon - ptPrev.lon) * cosLat
                val dLat2 = ptNext.lat - pt.lat
                val dLon2 = (ptNext.lon - pt.lon) * cosLat
                
                val len1 = kotlin.math.sqrt(dLat1 * dLat1 + dLon1 * dLon1)
                val len2 = kotlin.math.sqrt(dLat2 * dLat2 + dLon2 * dLon2)
                
                if (len1 > 0.00001 && len2 > 0.00001) {
                    val dot = dLat1 * dLat2 + dLon1 * dLon2
                    val cosTheta = dot / (len1 * len2)
                    
                    if (cosTheta <= -0.76) { // ~140 degrees reverse
                        val altDiff = kotlin.math.abs(ptNext.elevation - ptPrev.elevation)
                        if (altDiff < 1.5) { // Flat turnaround (not climbing hairpin)
                            if (!splitIndices.contains(i)) {
                                splitIndices.add(i)
                            }
                        }
                    }
                }
            }

            // Check OSM nodes
            var nearSignal = false
            for (node in signalNodes) {
                if (calculateDistanceMeters(pt.lat, pt.lon, node.lat, node.lon) <= 10.0) {
                    nearSignal = true
                    break
                }
            }

            // Check physical stop (speed <= 0.1 m/s)
            val isPhysicallyStopped = pt.speed <= 0.1

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

        // Handle case where log ends with a stop or out-of-grade
        if (consecutiveStopCount >= 2 && stopStartIdx != -1) {
            if (!splitIndices.contains(stopStartIdx)) {
                splitIndices.add(stopStartIdx)
            }
        }
        if (outOfGradeCount >= 5) {
            val splitIdx = maxOf(0, telemetryPoints.size - outOfGradeCount)
            if (!splitIndices.contains(splitIdx)) {
                splitIndices.add(splitIdx)
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
                
                val elevDiff = endPt.elevation - startPt.elevation
                val averageGrade = if (distance > 0) (elevDiff / distance) * 100.0 else 0.0
                
                if (distance >= minDistanceMeters && averageGrade >= minSearchGrade && averageGrade <= maxSearchGrade) {
                    var trimmedStart = startIdx
                    while (trimmedStart <= endIdx && 
                           (telemetryPoints[trimmedStart].grade < minSearchGrade || 
                            telemetryPoints[trimmedStart].grade > maxSearchGrade)) {
                        trimmedStart++
                    }

                    var trimmedEnd = endIdx
                    while (trimmedEnd >= trimmedStart && 
                           (telemetryPoints[trimmedEnd].grade < minSearchGrade || 
                            telemetryPoints[trimmedEnd].grade > maxSearchGrade)) {
                        trimmedEnd--
                    }

                    val totalPoints = trimmedEnd - trimmedStart + 1
                    if (totalPoints >= 2) {
                        val finalStartPt = telemetryPoints[trimmedStart]
                        val finalEndPt = telemetryPoints[trimmedEnd]
                        val finalDistance = finalEndPt.distance - finalStartPt.distance
                        val finalDuration = finalEndPt.elapsedSeconds - finalStartPt.elapsedSeconds
                        val finalElevDiff = finalEndPt.elevation - finalStartPt.elevation
                        val finalAverageGrade = if (finalDistance > 0) (finalElevDiff / finalDistance) * 100.0 else 0.0

                        if (finalDistance >= minDistanceMeters && 
                            finalAverageGrade >= minSearchGrade && 
                            finalAverageGrade <= maxSearchGrade) {

                            var maxConsecutiveOutOfGrade = 0
                            var currentConsecutiveOutOfGrade = 0
                            var outOfGradePointsCount = 0

                            for (k in trimmedStart..trimmedEnd) {
                                val g = telemetryPoints[k].grade
                                val isOutOfGrade = g < minSearchGrade || g > maxSearchGrade
                                if (isOutOfGrade) {
                                    outOfGradePointsCount++
                                    currentConsecutiveOutOfGrade++
                                    if (currentConsecutiveOutOfGrade > maxConsecutiveOutOfGrade) {
                                        maxConsecutiveOutOfGrade = currentConsecutiveOutOfGrade
                                    }
                                } else {
                                    currentConsecutiveOutOfGrade = 0
                                }
                            }

                            val outOfGradeRatio = outOfGradePointsCount.toDouble() / totalPoints.toDouble()

                            if (maxConsecutiveOutOfGrade < 5 && outOfGradeRatio < 0.20) {
                                val id = "seg_${trimmedStart}_${trimmedEnd}"
                                var minG = Double.MAX_VALUE
                                var maxG = -Double.MAX_VALUE
                                for (k in trimmedStart..trimmedEnd) {
                                    val g = telemetryPoints[k].grade
                                    if (g < minG) minG = g
                                    if (g > maxG) maxG = g
                                }
                                if (minG == Double.MAX_VALUE) minG = 0.0
                                if (maxG == -Double.MAX_VALUE) maxG = 0.0

                                segments.add(
                                    AutoDetectedSegment(
                                        id = id,
                                        name = "Detected Climb (${(finalDistance/1000.0).toString().substringBefore(".")}.${((finalDistance%1000.0)/100.0).toInt()}km)",
                                        startIndex = trimmedStart,
                                        endIndex = trimmedEnd,
                                        distanceMeters = finalDistance,
                                        durationSeconds = finalDuration.toDouble(),
                                        averageGrade = finalAverageGrade,
                                        startLat = finalStartPt.lat,
                                        startLon = finalStartPt.lon,
                                        endLat = finalEndPt.lat,
                                        endLon = finalEndPt.lon,
                                        startElev = finalStartPt.elevation,
                                        endElev = finalEndPt.elevation,
                                        minGrade = minG,
                                        maxGrade = maxG,
                                        startFitTimestamp = finalStartPt.timestamp,
                                        endFitTimestamp = finalEndPt.timestamp
                                    )
                                )
                            }
                        }
                    }
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

