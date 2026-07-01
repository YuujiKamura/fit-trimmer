package fit

import kotlinx.serialization.Serializable

@Serializable
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

@Serializable
data class PlateRecord(val timeMs: Long, val boxes: List<PlateBox>)

@Serializable
data class VideoPlatesCache(
    val videoPath: String,
    val records: List<PlateRecord>,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0
) {
    fun findNeighborRecords(targetTimeMs: Long): Pair<PlateRecord?, PlateRecord?> {
        if (records.isEmpty()) return Pair(null, null)
        
        var low = 0
        var high = records.size - 1
        
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = records[mid].timeMs
            
            when {
                midVal < targetTimeMs -> low = mid + 1
                midVal > targetTimeMs -> high = mid - 1
                else -> return Pair(records[mid], records[mid])
            }
        }
        
        val prev = if (high in records.indices) records[high] else null
        val next = if (low in records.indices) records[low] else null
        return Pair(prev, next)
    }

    fun shouldBlurAt(targetTimeMs: Long, isBlurEnabled: Boolean): List<PlateBox> {
        if (!isBlurEnabled || records.isEmpty()) return emptyList()
        
        val (prev, next) = findNeighborRecords(targetTimeMs)
        
        if (prev == null && next == null) return emptyList()
        if (prev != null && next != null && prev.timeMs == next.timeMs) {
            return prev.boxes
        }
        
        if (prev != null && next != null) {
            val interval = next.timeMs - prev.timeMs
            if (interval <= 1000) { // Interpolate if interval is within 1.0 second
                val alpha = (targetTimeMs - prev.timeMs).toFloat() / interval.toFloat()
                return interpolateBoxes(prev.boxes, next.boxes, alpha)
            } else {
                // Interval too large, only apply close to boundaries (within 300ms)
                val distPrev = targetTimeMs - prev.timeMs
                val distNext = next.timeMs - targetTimeMs
                return when {
                    distPrev <= 300 -> prev.boxes
                    distNext <= 300 -> next.boxes
                    else -> emptyList()
                }
            }
        }
        
        // Single-sided boundary cases (within 300ms)
        if (prev != null) {
            val dist = targetTimeMs - prev.timeMs
            if (dist <= 300) return prev.boxes
        }
        if (next != null) {
            val dist = next.timeMs - targetTimeMs
            if (dist <= 300) return next.boxes
        }
        
        return emptyList()
    }
    
    private fun interpolateBoxes(
        prevBoxes: List<PlateBox>,
        nextBoxes: List<PlateBox>,
        alpha: Float
    ): List<PlateBox> {
        val result = mutableListOf<PlateBox>()
        val matchedNextIndices = mutableSetOf<Int>()
        
        for (pb in prevBoxes) {
            val pCx = (pb.x1 + pb.x2) / 2.0
            val pCy = (pb.y1 + pb.y2) / 2.0
            
            var bestIdx = -1
            var minDistance = Double.MAX_VALUE
            
            for (i in nextBoxes.indices) {
                if (i in matchedNextIndices) continue
                val nb = nextBoxes[i]
                val nCx = (nb.x1 + nb.x2) / 2.0
                val nCy = (nb.y1 + nb.y2) / 2.0
                
                val dist = kotlin.math.hypot(nCx - pCx, nCy - pCy)
                if (dist < minDistance && dist < 400.0) { // Limit to 400px of movement
                    minDistance = dist
                    bestIdx = i
                }
            }
            
            if (bestIdx != -1) {
                matchedNextIndices.add(bestIdx)
                val nb = nextBoxes[bestIdx]
                val lerp = { a: Int, b: Int -> (a + (b - a) * alpha).toInt() }
                result.add(
                    PlateBox(
                        x1 = lerp(pb.x1, nb.x1),
                        y1 = lerp(pb.y1, nb.y1),
                        x2 = lerp(pb.x2, nb.x2),
                        y2 = lerp(pb.y2, nb.y2)
                    )
                )
            } else {
                if (alpha < 0.5f) {
                    result.add(pb)
                }
            }
        }
        
        if (alpha >= 0.5f) {
            for (i in nextBoxes.indices) {
                if (i !in matchedNextIndices) {
                    result.add(nextBoxes[i])
                }
            }
        }
        
        return result
    }
}

data class MappedPlateBox(val x: Float, val y: Float, val width: Float, val height: Float)

object PlateMaskExpander {
    fun expand(
        box: PlateBox,
        mode: String,
        sourceWidth: Int,
        sourceHeight: Int
    ): PlateBox {
        val width = (box.x2 - box.x1).coerceAtLeast(1)
        val height = (box.y2 - box.y1).coerceAtLeast(1)
        val normalizedMode = mode.lowercase()

        val (leftPad, rightPad, topPad, bottomPad) = if (normalizedMode == "wide") {
            listOf(
                width * 3.0,
                width * 3.0,
                height * 3.0,
                height * 1.5
            )
        } else {
            val padX = width * 0.18
            val padY = height * 0.28
            listOf(padX, padX, padY, padY)
        }

        return PlateBox(
            x1 = (box.x1 - leftPad).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y1 = (box.y1 - topPad).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1)),
            x2 = (box.x2 + rightPad).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y2 = (box.y2 + bottomPad).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1))
        )
    }
}

object PlateCoordinateMapper {
    fun mapToTarget(
        box: PlateBox,
        cache: VideoPlatesCache?,
        fallbackSourceWidth: Int,
        fallbackSourceHeight: Int,
        targetWidth: Float,
        targetHeight: Float
    ): MappedPlateBox {
        val sourceWidth = cache?.sourceWidth?.takeIf { it > 0 } ?: fallbackSourceWidth.coerceAtLeast(1)
        val sourceHeight = cache?.sourceHeight?.takeIf { it > 0 } ?: fallbackSourceHeight.coerceAtLeast(1)
        val scaleX = targetWidth / sourceWidth.toFloat()
        val scaleY = targetHeight / sourceHeight.toFloat()
        val x1 = box.x1 * scaleX
        val y1 = box.y1 * scaleY
        val x2 = box.x2 * scaleX
        val y2 = box.y2 * scaleY
        return MappedPlateBox(
            x = x1,
            y = y1,
            width = x2 - x1,
            height = y2 - y1
        )
    }
}
