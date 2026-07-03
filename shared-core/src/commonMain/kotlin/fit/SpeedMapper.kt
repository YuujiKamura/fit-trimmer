package fit

object SpeedMapper {
    /**
     * Maps a source time (in seconds) to the target/output time (in seconds).
     * The input segments are in the source timeline and must be sorted and non-overlapping.
     */
    fun mapSourceToTarget(sourceSeconds: Double, segments: List<SpeedSegment>): Double {
        if (segments.isEmpty()) return sourceSeconds
        
        val sortedSegs = segments.sortedBy { it.startSeconds }
        
        var currentSrc = 0.0
        var currentDst = 0.0
        
        for (seg in sortedSegs) {
            if (sourceSeconds < seg.startSeconds) {
                return currentDst + (sourceSeconds - currentSrc)
            }
            // Add normal speed segment before this speed segment
            currentDst += (seg.startSeconds - currentSrc)
            currentSrc = seg.startSeconds
            
            if (sourceSeconds <= seg.endSeconds) {
                return currentDst + (sourceSeconds - currentSrc) / seg.speedFactor
            }
            // Add speed segment
            currentDst += (seg.endSeconds - seg.startSeconds) / seg.speedFactor
            currentSrc = seg.endSeconds
        }
        
        return currentDst + (sourceSeconds - currentSrc)
    }

    /**
     * Maps a target/output time (in seconds) back to the source time (in seconds).
     * This is the timeline inverse-mapping function.
     */
    fun mapTargetToSource(targetSeconds: Double, segments: List<SpeedSegment>): Double {
        if (segments.isEmpty()) return targetSeconds
        
        val sortedSegs = segments.sortedBy { it.startSeconds }
        
        var currentSrc = 0.0
        var currentDst = 0.0
        
        for (seg in sortedSegs) {
            val targetStart = currentDst + (seg.startSeconds - currentSrc)
            if (targetSeconds < targetStart) {
                return currentSrc + (targetSeconds - currentDst)
            }
            
            currentDst = targetStart
            currentSrc = seg.startSeconds
            
            val targetEnd = currentDst + (seg.endSeconds - seg.startSeconds) / seg.speedFactor
            if (targetSeconds <= targetEnd) {
                return currentSrc + (targetSeconds - currentDst) * seg.speedFactor
            }
            
            currentDst = targetEnd
            currentSrc = seg.endSeconds
        }
        
        return currentSrc + (targetSeconds - currentDst)
    }
}
