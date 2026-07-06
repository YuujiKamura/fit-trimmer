package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class EncodeGroundTruthMetadataTest {
    @Test
    fun buildsFfmpegMetadataArgsForManualTimeOffsetGroundTruth() {
        val metadata = EncodeGroundTruthMetadata(
            sourceVideoPath = "F:\\Insta360\\20260705\\VID_20260705_172820_002_00m23s-00m50s_KMP_HUD_2.7k.mp4",
            sourceVideoStartUtc = "2026-07-05T08:28:20Z",
            alignedVideoStartUtc = "2026-07-05T08:28:43Z",
            timeOffsetMillis = 23_000L
        )

        assertEquals(
            listOf(
                "-movflags", "+use_metadata_tags",
                "-metadata", "comment=fit-trimmer-hud-burned",
                "-metadata", "fit_trimmer_ground_truth=manual_time_offset",
                "-metadata", "fit_trimmer_source_video_path=F:\\Insta360\\20260705\\VID_20260705_172820_002_00m23s-00m50s_KMP_HUD_2.7k.mp4",
                "-metadata", "fit_trimmer_source_video_start_utc=2026-07-05T08:28:20Z",
                "-metadata", "fit_trimmer_aligned_video_start_utc=2026-07-05T08:28:43Z",
                "-metadata", "fit_trimmer_time_offset_ms=23000"
            ),
            metadata.toFfmpegMetadataArgs()
        )
    }

    @Test
    fun omitsOptionalGroundTruthFieldsWhenSourceStartIsUnknown() {
        val metadata = EncodeGroundTruthMetadata(
            sourceVideoPath = "input.mp4",
            sourceVideoStartUtc = "",
            alignedVideoStartUtc = "2026-07-05T08:28:43Z",
            timeOffsetMillis = 0L
        )

        assertEquals(
            listOf(
                "-movflags", "+use_metadata_tags",
                "-metadata", "comment=fit-trimmer-hud-burned",
                "-metadata", "fit_trimmer_ground_truth=manual_time_offset",
                "-metadata", "fit_trimmer_source_video_path=input.mp4",
                "-metadata", "fit_trimmer_aligned_video_start_utc=2026-07-05T08:28:43Z",
                "-metadata", "fit_trimmer_time_offset_ms=0"
            ),
            metadata.toFfmpegMetadataArgs()
        )
    }
}
