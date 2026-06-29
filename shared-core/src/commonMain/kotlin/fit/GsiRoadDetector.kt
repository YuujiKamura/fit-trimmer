package fit

import kotlinx.serialization.json.*
import kotlin.math.*

object GsiRoadDetector {

    data class GsiRoadInfo(
        val rdCtg: String?,
        val name: String?,
        val comName: String?,
        val distanceMeters: Double
    )

    fun deg2tile(latDeg: Double, lonDeg: Double, zoom: Int = 16): Pair<Int, Int> {
        val latRad = latDeg * PI / 180.0
        val n = 2.0.pow(zoom)
        val xtile = ((lonDeg + 180.0) / 360.0 * n).toInt()
        val ytile = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(xtile, ytile)
    }

    private fun asinh(x: Double): Double = ln(x + sqrt(x * x + 1.0))

    fun getDistancePointToSegmentSq(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) {
            return (px - x1) * (px - x1) + (py - y1) * (py - y1)
        }
        var t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        t = maxOf(0.0, minOf(1.0, t))
        val closestX = x1 + t * dx
        val closestY = y1 + t * dy
        return (px - closestX) * (px - closestX) + (py - closestY) * (py - closestY)
    }

    fun getDistancePointToLineStringSq(px: Double, py: Double, coordinates: List<Pair<Double, Double>>): Double {
        var minDistSq = Double.MAX_VALUE
        for (i in 0 until coordinates.size - 1) {
            val p1 = coordinates[i]
            val p2 = coordinates[i+1]
            val distSq = getDistancePointToSegmentSq(px, py, p1.first, p1.second, p2.first, p2.second)
            if (distSq < minDistSq) {
                minDistSq = distSq
            }
        }
        return minDistSq
    }

    fun findClosestRoad(lat: Double, lon: Double, geoJsonStr: String): GsiRoadInfo? {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(geoJsonStr).jsonObject
            val features = element["features"]?.jsonArray ?: return null
            
            var closestFeature: JsonObject? = null
            var minDistSq = Double.MAX_VALUE

            for (featValue in features) {
                val feat = featValue.jsonObject
                val geom = feat["geometry"]?.jsonObject ?: continue
                val type = geom["type"]?.jsonPrimitive?.content ?: continue
                if (type == "LineString") {
                    val coordsArray = geom["coordinates"]?.jsonArray ?: continue
                    val coordinates = coordsArray.map {
                        val c = it.jsonArray
                        Pair(c[0].jsonPrimitive.double, c[1].jsonPrimitive.double)
                    }
                    val distSq = getDistancePointToLineStringSq(lon, lat, coordinates)
                    if (distSq < minDistSq) {
                        minDistSq = distSq
                        closestFeature = feat
                    }
                }
            }

            if (closestFeature != null) {
                val props = closestFeature["properties"]?.jsonObject
                val rdCtg = props?.get("rdCtg")?.jsonPrimitive?.contentOrNull
                val name = props?.get("name")?.jsonPrimitive?.contentOrNull
                val comName = props?.get("comName")?.jsonPrimitive?.contentOrNull
                
                val distMeters = sqrt(minDistSq) * 111000.0
                return GsiRoadInfo(
                    rdCtg = if (rdCtg.isNullOrEmpty()) null else rdCtg,
                    name = if (name.isNullOrEmpty()) null else name,
                    comName = if (comName.isNullOrEmpty()) null else comName,
                    distanceMeters = distMeters
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
