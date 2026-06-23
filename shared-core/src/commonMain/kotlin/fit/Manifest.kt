package fit

import kotlinx.serialization.Serializable

@Serializable
data class SyncManifest(
    val fitFile: String,
    val videos: List<VideoInfo>
)

@Serializable
data class VideoInfo(
    val name: String,
    val path: String,
    val startUtc: String,
    val durationSec: Double,
    val isSyncable: Boolean,
    val alreadyHasHud: Boolean = false
)
