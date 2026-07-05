package fit

import java.io.File

object HudFileNameFormatter {
    fun buildEncodeOutputFileName(
        settings: HudSettings,
        videoPath: String,
        partIndex: Int = -1,
        numParts: Int = 1,
        isSample: Boolean = false,
        trimStartSeconds: Double? = null,
        trimEndSeconds: Double? = null,
        dateTag: String? = null
    ): String {
        val videoFile = File(videoPath)
        val baseName = videoFile.name.replace(".mp4", "", ignoreCase = true).replace(".mov", "", ignoreCase = true)
        val trimSuffix = buildTrimRangeSuffix(baseName, trimStartSeconds, trimEndSeconds, dateTag)
        val partSuffix = if (!isSample && partIndex >= 0 && numParts > 1) "_part${partIndex + 1}" else ""
        val resSuffix = when (settings.exportResolution) {
            "1080p" -> "_1080p"
            "2.7k" -> "_2.7k"
            else -> "_orig"
        }
        val suffix = if (isSample) {
            "${trimSuffix}${partSuffix}_TEST_HUD.mp4"
        } else {
            "${trimSuffix}${partSuffix}_KMP_HUD${resSuffix}.mp4"
        }
        return baseName + suffix
    }

    private fun buildTrimRangeSuffix(
        baseName: String,
        trimStartSeconds: Double?,
        trimEndSeconds: Double?,
        dateTag: String?
    ): String {
        if (trimStartSeconds == null || trimEndSeconds == null) return ""
        if (trimEndSeconds <= trimStartSeconds) return ""
        val normalizedDateTag = dateTag
            ?.takeIf { Regex("""\d{8}""").matches(it) }
            ?.takeUnless { baseName.contains(it) }
        val datePrefix = normalizedDateTag?.let { "${it}_" } ?: ""
        return "_${datePrefix}${formatSecondsForFileName(trimStartSeconds)}-${formatSecondsForFileName(trimEndSeconds)}"
    }

    private fun formatSecondsForFileName(seconds: Double): String {
        val totalSeconds = kotlin.math.round(seconds).toLong().coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val secs = totalSeconds % 60L
        return if (hours > 0L) {
            "%02dh%02dm%02ds".format(hours, minutes, secs)
        } else {
            "%02dm%02ds".format(minutes, secs)
        }
    }
}
