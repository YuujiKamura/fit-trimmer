import java.io.File
import fit.HudSettings

data class EncodeSegmentPlan(
    val index: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val finalOutputFile: File
)

data class EncodePlan(
    val settings: HudSettings,
    val segments: List<EncodeSegmentPlan>
) {
    val totalDurationSeconds: Double
        get() = segments.sumOf { it.endSeconds - it.startSeconds }
}
