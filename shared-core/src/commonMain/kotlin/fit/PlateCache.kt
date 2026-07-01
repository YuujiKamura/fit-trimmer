package fit

import kotlinx.serialization.Serializable

@Serializable
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

@Serializable
data class PlateRecord(val timeMs: Long, val boxes: List<PlateBox>)

@Serializable
data class VideoPlatesCache(
    val videoPath: String,
    val records: List<PlateRecord>
) {
    fun findClosestRecord(targetTimeMs: Long): PlateRecord? {
        if (records.isEmpty()) return null
        var low = 0
        var high = records.size - 1
        
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = records[mid].timeMs
            
            when {
                midVal < targetTimeMs -> low = mid + 1
                midVal > targetTimeMs -> high = mid - 1
                else -> return records[mid]
            }
        }
        
        val candidate1 = if (low in records.indices) records[low] else null
        val candidate2 = if (high in records.indices) records[high] else null
        
        return when {
            candidate1 == null -> candidate2
            candidate2 == null -> candidate1
            else -> {
                if (kotlin.math.abs(candidate1.timeMs - targetTimeMs) < kotlin.math.abs(candidate2.timeMs - targetTimeMs)) {
                    candidate1
                } else {
                    candidate2
                }
            }
        }
    }

    fun shouldBlurAt(targetTimeMs: Long, isBlurEnabled: Boolean): List<PlateBox> {
        if (!isBlurEnabled) return emptyList()
        val record = findClosestRecord(targetTimeMs) ?: return emptyList()
        return if (kotlin.math.abs(record.timeMs - targetTimeMs) < 2500) {
            record.boxes
        } else {
            emptyList()
        }
    }
}
