package fit

import kotlinx.serialization.Serializable

@Serializable
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val trackId: Int = 0)

@Serializable
data class PlateRecord(val timeMs: Long, val boxes: List<PlateBox>)

@Serializable
data class PlateScanRange(val startMs: Long, val endMs: Long)

@Serializable
data class VideoPlatesCache(
    val videoPath: String,
    val records: List<PlateRecord>,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val scanRanges: List<PlateScanRange> = emptyList()
) {
    fun smoothed(alpha: Float = 0.5f): VideoPlatesCache {
        val sortedRecords = records.sortedBy { it.timeMs }
        val smoothedRecords = mutableListOf<PlateRecord>()
        val trackMap = mutableMapOf<Int, PlateBox>()
        
        for (record in sortedRecords) {
            val newBoxes = mutableListOf<PlateBox>()
            for (box in record.boxes) {
                if (box.trackId == 0) {
                    newBoxes.add(box)
                } else {
                    val prev = trackMap[box.trackId]
                    if (prev != null) {
                        val nx1 = (prev.x1 * (1 - alpha) + box.x1 * alpha).toInt()
                        val ny1 = (prev.y1 * (1 - alpha) + box.y1 * alpha).toInt()
                        val nx2 = (prev.x2 * (1 - alpha) + box.x2 * alpha).toInt()
                        val ny2 = (prev.y2 * (1 - alpha) + box.y2 * alpha).toInt()
                        val smoothedBox = PlateBox(nx1, ny1, nx2, ny2, box.trackId)
                        newBoxes.add(smoothedBox)
                        trackMap[box.trackId] = smoothedBox
                    } else {
                        newBoxes.add(box)
                        trackMap[box.trackId] = box
                    }
                }
            }
            smoothedRecords.add(PlateRecord(record.timeMs, newBoxes))
        }
        
        return copy(records = smoothedRecords)
    }

    fun coversRange(startSeconds: Double, endSeconds: Double): Boolean {
        if (scanRanges.isEmpty()) return true
        val startMs = (startSeconds * 1000.0).toLong()
        val endMs = (endSeconds * 1000.0).toLong()
        return scanRanges.any { it.startMs <= startMs && it.endMs >= endMs }
    }

    fun coversRanges(ranges: List<Pair<Double, Double>>): Boolean {
        return ranges.all { (start, end) -> coversRange(start, end) }
    }

    fun mergedWith(other: VideoPlatesCache): VideoPlatesCache {
        val mergedRecords = (records + other.records)
            .groupBy { it.timeMs }
            .map { (timeMs, recordsAtTime) ->
                PlateRecord(timeMs, recordsAtTime.flatMap { it.boxes }.distinct())
            }
            .sortedBy { it.timeMs }
        val mergedRanges = (scanRanges + other.scanRanges)
            .sortedBy { it.startMs }
            .fold(mutableListOf<PlateScanRange>()) { acc, range ->
                val last = acc.lastOrNull()
                if (last != null && range.startMs <= last.endMs + 1L) {
                    acc[acc.lastIndex] = PlateScanRange(last.startMs, maxOf(last.endMs, range.endMs))
                } else {
                    acc.add(range)
                }
                acc
            }
        return copy(
            records = mergedRecords,
            sourceWidth = sourceWidth.takeIf { it > 0 } ?: other.sourceWidth,
            sourceHeight = sourceHeight.takeIf { it > 0 } ?: other.sourceHeight,
            scanRanges = mergedRanges
        )
    }

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
        return boxesForTargetTime(targetTimeMs, prev, next).map { it.first }
    }

    private fun scaleBoxRatio(box: PlateBox, scale: Float): PlateBox {
        val w = box.x2 - box.x1
        val h = box.y2 - box.y1
        val cx = (box.x1 + box.x2) / 2f
        val cy = (box.y1 + box.y2) / 2f
        val newW = (w * scale).coerceAtLeast(1f)
        val newH = (h * scale).coerceAtLeast(1f)
        return PlateBox(
            x1 = (cx - newW / 2).toInt(),
            y1 = (cy - newH / 2).toInt(),
            x2 = (cx + newW / 2).toInt(),
            y2 = (cy + newH / 2).toInt()
        )
    }

    fun boxesForTargetTime(
        targetTimeMs: Long,
        prev: PlateRecord?,
        next: PlateRecord?
    ): List<Pair<PlateBox, Float>> {
        val width = if (sourceWidth > 0) sourceWidth else 2704 // fallback to 2.7K width
        val areaLimit = (width * 0.5) * (width * 0.5)// Disable linear interpolation (Lerp) because the interval is typically 0.5s (2fps) to 0.25s (4fps),
        // and perspective movement of approaching vehicles is highly non-linear. 
        // Lerping across such large gaps causes bounding boxes to drift significantly off the vehicle.
        // Instead, we use Nearest Neighbor approach to keep boxes perfectly locked onto the vehicle's detected position.
        
        val nearest = listOfNotNull(prev, next).minByOrNull { kotlin.math.abs(it.timeMs - targetTimeMs) }
        if (nearest != null) {
            val dist = kotlin.math.abs(nearest.timeMs - targetTimeMs)
            if (dist <= 300L) {
                return nearest.boxes.map { it to 1.0f }
            }
        }
        
        return emptyList()
    }
    

data class MappedPlateBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val intensity: Float = 1.0f
)

fun VideoPlatesCache.buildMappedMaskFrames(
    totalFrames: Int,
    fps: Double,
    isBlurEnabled: Boolean,
    fallbackSourceWidth: Int,
    fallbackSourceHeight: Int,
    targetWidth: Float,
    targetHeight: Float,
    sourceStartTimeMs: Long = 0L,
    speedSegments: List<SpeedSegment> = emptyList(),
    cropToSquare: Boolean = false
): List<List<MappedPlateBox>> {
    if (!isBlurEnabled || records.isEmpty() || totalFrames <= 0 || fps <= 0.0) {
        return List(totalFrames.coerceAtLeast(0)) { emptyList() }
    }

    var prevIndex = -1
    var nextIndex = 0
    return List(totalFrames) { frame ->
        val frameTargetSeconds = frame / fps
        val frameSourceSeconds = if (speedSegments.isEmpty()) {
            frameTargetSeconds
        } else {
            SpeedMapper.mapTargetToSource(frameTargetSeconds, speedSegments)
        }
        val targetTimeMs = sourceStartTimeMs + (frameSourceSeconds * 1000.0).toLong()
        while (nextIndex < records.size && records[nextIndex].timeMs < targetTimeMs) {
            prevIndex = nextIndex
            nextIndex++
        }

        val prev = when {
            nextIndex < records.size && records[nextIndex].timeMs == targetTimeMs -> records[nextIndex]
            prevIndex in records.indices -> records[prevIndex]
            else -> null
        }
        val next = when {
            nextIndex < records.size && records[nextIndex].timeMs == targetTimeMs -> records[nextIndex]
            nextIndex in records.indices -> records[nextIndex]
            else -> null
        }

        val actualSourceW = sourceWidth.takeIf { it > 0 } ?: fallbackSourceWidth
        val actualSourceH = sourceHeight.takeIf { it > 0 } ?: fallbackSourceHeight

        boxesForTargetTime(targetTimeMs, prev, next).mapNotNull { (box, intensity) ->
            val expanded = PlateMaskExpander.expand(
                box = box,
                sourceWidth = actualSourceW,
                sourceHeight = actualSourceH
            )
            PlateCoordinateMapper.mapToTarget(
                box = expanded,
                sourceWidth = actualSourceW,
                sourceHeight = actualSourceH,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                cropToSquare = cropToSquare,
                intensity = intensity
            ).takeIf { it.width > 0f && it.height > 0f }
        }
    }
}

object PlateMaskExpander {
    fun expand(
        box: PlateBox,
        sourceWidth: Int,
        sourceHeight: Int
    ): PlateBox {
        val width = (box.x2 - box.x1).coerceAtLeast(1)
        val height = (box.y2 - box.y1).coerceAtLeast(1)

        // Match video-privacy-blur logic: expand from center by 1.35x
        val scale = 1.35
        val cx = box.x1 + width / 2.0
        val cy = box.y1 + height / 2.0
        val newW = width * scale
        val newH = height * scale

        return PlateBox(
            x1 = (cx - newW / 2.0).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y1 = (cy - newH / 2.0).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1)),
            x2 = (cx + newW / 2.0).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y2 = (cy + newH / 2.0).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1))
        )
    }
}

object PlateCoordinateMapper {
    fun mapToTarget(
        box: PlateBox,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Float,
        targetHeight: Float,
        cropToSquare: Boolean = false,
        intensity: Float = 1.0f
    ): MappedPlateBox {
        val safeSourceW = sourceWidth.coerceAtLeast(1)
        val safeSourceH = sourceHeight.coerceAtLeast(1)
        
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        
        if (cropToSquare) {
            val scale = targetHeight / safeSourceH.toFloat()
            val xOffset = (safeSourceW - safeSourceH) / 2f
            x1 = (box.x1 - xOffset) * scale
            y1 = box.y1 * scale
            x2 = (box.x2 - xOffset) * scale
            y2 = box.y2 * scale
        } else {
            // Calculate effective drawing area while maintaining aspect ratio (letterboxing)
            val sourceAspect = safeSourceW.toFloat() / safeSourceH.toFloat()
            val targetAspect = targetWidth / targetHeight
            
            val drawW: Float
            val drawH: Float
            val offsetX: Float
            val offsetY: Float
            
            if (targetAspect > sourceAspect) {
                // Target is wider -> pillarbox (black bars on left/right)
                drawH = targetHeight
                drawW = targetHeight * sourceAspect
                offsetX = (targetWidth - drawW) / 2f
                offsetY = 0f
            } else {
                // Target is taller -> letterbox (black bars on top/bottom)
                drawW = targetWidth
                drawH = targetWidth / sourceAspect
                offsetX = 0f
                offsetY = (targetHeight - drawH) / 2f
            }
            
            val scaleX = drawW / safeSourceW.toFloat()
            val scaleY = drawH / safeSourceH.toFloat()
            
            x1 = (box.x1 * scaleX) + offsetX
            y1 = (box.y1 * scaleY) + offsetY
            x2 = (box.x2 * scaleX) + offsetX
            y2 = (box.y2 * scaleY) + offsetY
        }
        
        return MappedPlateBox(
            x = x1,
            y = y1,
            width = x2 - x1,
            height = y2 - y1,
            intensity = intensity
        )
    }
}
