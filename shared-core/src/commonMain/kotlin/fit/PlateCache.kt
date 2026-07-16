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
    fun filledGaps(holdFrames: Int = 10, iouThreshold: Float = 0.1f): VideoPlatesCache {
        if (records.isEmpty()) return this
        
        val sortedRecords = records.sortedBy { it.timeMs }
        val mutableBoxesMap = sortedRecords.associate { it.timeMs to it.boxes.toMutableList() }
        
        // 順行で操作した後に、歯抜けを探す処理
        for (i in 0 until sortedRecords.size) {
            val currentRecord = sortedRecords[i]
            val currentBoxes = mutableBoxesMap[currentRecord.timeMs] ?: continue
            
            // 過去のフレームを遡って問い合わせる (最大 holdFrames 回)
            for (k in 1..holdFrames) {
                val pastOlderIdx = i - k - 1
                val pastNewerIdx = i - k
                
                if (pastOlderIdx < 0) break
                
                val boxesOlder = sortedRecords[pastOlderIdx].boxes // 是であるべき
                val boxesNewer = sortedRecords[pastNewerIdx].boxes // 否であるべき
                
                for (boxOld in boxesOlder) {
                    // 連続2フレームを比較し、否是の組み合わせを探す
                    val hasOverlapInNewer = boxesNewer.any { calculateIoU(it, boxOld) > iouThreshold }
                    if (!hasOverlapInNewer) {
                        // 否是の組み合わせ（消失）を発見。現在フレームにその座標を補完する。
                        // ただし、現在フレームにすでに重なるものが無い場合のみ。
                        val hasOverlapInCurrent = currentBoxes.any { calculateIoU(it, boxOld) > iouThreshold }
                        if (!hasOverlapInCurrent) {
                            currentBoxes.add(boxOld)
                        }
                    }
                }
            }
        }
        
        val finalRecords = sortedRecords.map { PlateRecord(it.timeMs, mutableBoxesMap[it.timeMs]?.toList() ?: emptyList()) }
        return copy(records = finalRecords)
    }

    private fun calculateIoU(a: PlateBox, b: PlateBox): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)
        
        val interW = maxOf(0, interX2 - interX1)
        val interH = maxOf(0, interY2 - interY1)
        val interArea = interW * interH
        
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea
        
        return if (unionArea <= 0) 0f else interArea.toFloat() / unionArea.toFloat()
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

        // The model is a dedicated plate detector. Expand from center by 1.35x to add margin.
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
            // FFmpeg's scale=W:H filter stretches the video ignoring aspect ratio.
            // We must map the coordinates using direct stretch to match exactly.
            val scaleX = targetWidth / safeSourceW.toFloat()
            val scaleY = targetHeight / safeSourceH.toFloat()
            
            x1 = box.x1 * scaleX
            y1 = box.y1 * scaleY
            x2 = box.x2 * scaleX
            y2 = box.y2 * scaleY
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
