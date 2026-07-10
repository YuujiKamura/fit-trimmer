package fit

data class CutSpan(val startSec: Double, val endSec: Double)

object SmartCutTimeMapper {
    fun mapVideoTimeToFitTime(videoTime: Double, cutSpans: List<CutSpan>): Double {
        if (videoTime < 0.0) return 0.0
        
        // Sort cut spans to ensure sequential processing
        val sortedCuts = cutSpans.sortedBy { it.startSec }
        
        // Build the active timeline segments (keep spans)
        val keepSpans = mutableListOf<Pair<Double, Double>>()
        var lastTime = 0.0
        for (cut in sortedCuts) {
            if (cut.startSec > lastTime) {
                keepSpans.add(Pair(lastTime, cut.startSec))
            }
            if (cut.endSec > lastTime) {
                lastTime = cut.endSec
            }
        }
        keepSpans.add(Pair(lastTime, Double.MAX_VALUE))
        
        // Traverse the keep spans to map the truncated videoTime back to the original timeline
        var remainingVideoTime = videoTime
        for (span in keepSpans) {
            val spanLength = span.second - span.first
            if (remainingVideoTime <= spanLength) {
                return span.first + remainingVideoTime
            } else {
                remainingVideoTime -= spanLength
            }
        }
        
        return videoTime
    }
}
