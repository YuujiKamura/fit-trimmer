package fit

import kotlinx.serialization.Serializable

@Serializable
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

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

    fun shouldBlurAt(targetTimeMs: Long, isBlurEnabled: Boolean, timeBufferMs: Long = 300L): List<PlateBox> {
        if (!isBlurEnabled || records.isEmpty()) return emptyList()
        
        val (prev, next) = findNeighborRecords(targetTimeMs)
        return boxesForTargetTime(targetTimeMs, prev, next, timeBufferMs)
    }

    private fun expandBoxRatio(box: PlateBox, scale: Float): PlateBox {
        if (scale <= 1.0f) return box
        val w = box.x2 - box.x1
        val h = box.y2 - box.y1
        val cx = (box.x1 + box.x2) / 2f
        val cy = (box.y1 + box.y2) / 2f
        val newW = w * scale
        val newH = h * scale
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
        next: PlateRecord?,
        timeBufferMs: Long = 300L
    ): List<PlateBox> {
        val width = if (sourceWidth > 0) sourceWidth else 2704 // fallback to 2.7K width
        val result = mutableListOf<PlateBox>()
        
        // 1. Primary interpolation for adjacent frames
        val primaryBoxes = if (prev != null && next != null) {
            val interval = next.timeMs - prev.timeMs
            if (interval <= 1500) { // Interpolate if interval is within 1.5 seconds
                val alpha = (targetTimeMs - prev.timeMs).toFloat() / interval.toFloat()
                interpolateBoxes(prev.boxes, next.boxes, alpha, width)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        result.addAll(primaryBoxes)
        
        // 2. Fallback buffer processing: Gather buffer boxes from ALL records within timeBufferMs to prevent vehicle-blocking issues
        val bufferRecords = records.filter { 
            val dist = kotlin.math.abs(it.timeMs - targetTimeMs)
            dist <= timeBufferMs && it.timeMs != prev?.timeMs && it.timeMs != next?.timeMs
        }
        for (rec in bufferRecords) {
            val dist = kotlin.math.abs(rec.timeMs - targetTimeMs)
            val scale = 1.0f + (dist.toFloat() / timeBufferMs.toFloat()) * 0.4f
            result.addAll(rec.boxes.map { expandBoxRatio(it, scale) })
        }
        
        // 3. Apply buffer scale to prev/next if they were not interpolated
        if (primaryBoxes.isEmpty()) {
            if (prev != null) {
                val dist = targetTimeMs - prev.timeMs
                if (dist <= timeBufferMs) {
                    val scale = 1.0f + (dist.toFloat() / timeBufferMs.toFloat()) * 0.4f
                    result.addAll(prev.boxes.map { expandBoxRatio(it, scale) })
                }
            }
            if (next != null) {
                val dist = next.timeMs - targetTimeMs
                if (dist <= timeBufferMs) {
                    val scale = 1.0f + (dist.toFloat() / timeBufferMs.toFloat()) * 0.4f
                    result.addAll(next.boxes.map { expandBoxRatio(it, scale) })
                }
            }
        }
        
        return result.distinct()
    }
    
    private fun interpolateBoxes(
        prevBoxes: List<PlateBox>,
        nextBoxes: List<PlateBox>,
        alpha: Float,
        width: Int
    ): List<PlateBox> {
        val result = mutableListOf<PlateBox>()
        val matchedNextIndices = mutableSetOf<Int>()
        val maxDist = (width.toDouble() * 0.5).coerceAtLeast(400.0) // 50% of screen width tracking limit
        
        for (pb in prevBoxes) {
            val pCx = (pb.x1 + pb.x2) / 2.0
            val pCy = (pb.y1 + pb.y2) / 2.0
            val pArea = (pb.x2 - pb.x1).coerceAtLeast(1) * (pb.y2 - pb.y1).coerceAtLeast(1)
            
            var bestIdx = -1
            var minDistance = Double.MAX_VALUE
            
            for (i in nextBoxes.indices) {
                if (i in matchedNextIndices) continue
                val nb = nextBoxes[i]
                val nCx = (nb.x1 + nb.x2) / 2.0
                val nCy = (nb.y1 + nb.y2) / 2.0
                val nArea = (nb.x2 - nb.x1).coerceAtLeast(1) * (nb.y2 - nb.y1).coerceAtLeast(1)
                
                // Exclude matches with extreme size scaling mismatch (e.g. area ratio >= 3.0) to prevent ghost mapping between different vehicles
                val areaRatio = if (pArea > nArea) pArea.toDouble() / nArea.toDouble() else nArea.toDouble() / pArea.toDouble()
                if (areaRatio >= 3.0) continue

                val dist = kotlin.math.hypot(nCx - pCx, nCy - pCy)
                if (dist < minDistance && dist < maxDist) {
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

fun VideoPlatesCache.buildMappedMaskFrames(
    totalFrames: Int,
    fps: Double,
    isBlurEnabled: Boolean,
    expandRatio: Double,
    fallbackSourceWidth: Int,
    fallbackSourceHeight: Int,
    targetWidth: Float,
    targetHeight: Float,
    timeBufferMs: Long = 300L,
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

        boxesForTargetTime(targetTimeMs, prev, next, timeBufferMs).mapNotNull { box ->
            val expanded = PlateMaskExpander.expand(
                box = box,
                expandRatio = expandRatio,
                sourceWidth = sourceWidth.takeIf { it > 0 } ?: fallbackSourceWidth,
                sourceHeight = sourceHeight.takeIf { it > 0 } ?: fallbackSourceHeight
            )
            PlateCoordinateMapper.mapToTarget(
                box = expanded,
                cache = this,
                fallbackSourceWidth = fallbackSourceWidth,
                fallbackSourceHeight = fallbackSourceHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                cropToSquare = cropToSquare
            ).takeIf { it.width > 0f && it.height > 0f }
        }
    }
}

object PlateMaskExpander {
    fun expand(
        box: PlateBox,
        expandRatio: Double,
        sourceWidth: Int,
        sourceHeight: Int
    ): PlateBox {
        val width = (box.x2 - box.x1).coerceAtLeast(1)
        val height = (box.y2 - box.y1).coerceAtLeast(1)

        val padX = width * expandRatio
        val padY = height * (expandRatio * 1.5)

        return PlateBox(
            x1 = (box.x1 - padX).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y1 = (box.y1 - padY).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1)),
            x2 = (box.x2 + padX).toInt().coerceIn(0, sourceWidth.coerceAtLeast(1)),
            y2 = (box.y2 + padY).toInt().coerceIn(0, sourceHeight.coerceAtLeast(1))
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
        targetHeight: Float,
        cropToSquare: Boolean = false
    ): MappedPlateBox {
        val sourceWidth = cache?.sourceWidth?.takeIf { it > 0 } ?: fallbackSourceWidth.coerceAtLeast(1)
        val sourceHeight = cache?.sourceHeight?.takeIf { it > 0 } ?: fallbackSourceHeight.coerceAtLeast(1)
        
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        
        if (cropToSquare) {
            val scale = targetHeight / sourceHeight.toFloat()
            val xOffset = (sourceWidth - sourceHeight) / 2f
            x1 = (box.x1 - xOffset) * scale
            y1 = box.y1 * scale
            x2 = (box.x2 - xOffset) * scale
            y2 = box.y2 * scale
        } else {
            val scaleX = targetWidth / sourceWidth.toFloat()
            val scaleY = targetHeight / sourceHeight.toFloat()
            x1 = box.x1 * scaleX
            y1 = box.y1 * scaleY
            x2 = box.x2 * scaleX
            y2 = box.y2 * scaleY
        }
        
        return MappedPlateBox(
            x = x1,
            y = y1,
            width = x2 - x1,
            height = y2 - y1
        )
    }
}
