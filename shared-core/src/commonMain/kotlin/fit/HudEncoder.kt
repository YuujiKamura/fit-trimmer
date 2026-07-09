package fit

data class EncodeProfileReport(
    val totalElapsedMs: Double,
    val maskPlanMs: Double,
    val maskVideoMs: Double,
    val ffmpegActiveMs: Double,
    val frameCount: Long,
    val telemetryMs: Double,
    val hudRenderMs: Double,
    val rawCopyMs: Double,
    val bufferWaitMs: Double,
    val queuePutMs: Double,
    val livePreviewMs: Double,
    val progressMs: Double,
    val pipeWriteMs: Double,
    val pipeBytes: Long
) {
    val pipeMiB: Double get() = pipeBytes.toDouble() / (1024.0 * 1024.0)
    val avgHudRenderMs: Double get() = if (frameCount > 0) hudRenderMs / frameCount else 0.0
    val avgRawCopyMs: Double get() = if (frameCount > 0) rawCopyMs / frameCount else 0.0
    val avgBufferWaitMs: Double get() = if (frameCount > 0) bufferWaitMs / frameCount else 0.0
    val avgPipeWriteMs: Double get() = if (frameCount > 0) pipeWriteMs / frameCount else 0.0

    fun toMetricLine(): String =
        "ENCODE_PROFILE: " +
            "total_ms=${"%.2f".format(totalElapsedMs)} " +
            "mask_plan_ms=${"%.2f".format(maskPlanMs)} " +
            "mask_video_ms=${"%.2f".format(maskVideoMs)} " +
            "ffmpeg_active_ms=${"%.2f".format(ffmpegActiveMs)} " +
            "frames=$frameCount " +
            "telemetry_ms=${"%.2f".format(telemetryMs)} " +
            "hud_render_ms=${"%.2f".format(hudRenderMs)} " +
            "raw_copy_ms=${"%.2f".format(rawCopyMs)} " +
            "buffer_wait_ms=${"%.2f".format(bufferWaitMs)} " +
            "queue_put_ms=${"%.2f".format(queuePutMs)} " +
            "live_preview_ms=${"%.2f".format(livePreviewMs)} " +
            "progress_ms=${"%.2f".format(progressMs)} " +
            "pipe_write_ms=${"%.2f".format(pipeWriteMs)} " +
            "pipe_mib=${"%.2f".format(pipeMiB)} " +
            "avg_hud_ms=${"%.3f".format(avgHudRenderMs)} " +
            "avg_copy_ms=${"%.3f".format(avgRawCopyMs)} " +
            "avg_wait_ms=${"%.3f".format(avgBufferWaitMs)} " +
            "avg_pipe_ms=${"%.3f".format(avgPipeWriteMs)}"
}

data class EncodeGroundTruthMetadata(
    val sourceVideoPath: String,
    val sourceVideoStartUtc: String,
    val alignedVideoStartUtc: String,
    val timeOffsetMillis: Long,
    val imuTimeOffsetMillis: Long? = null
) {
    fun toFfmpegMetadataArgs(): List<String> {
        val args = mutableListOf(
            "-movflags", "+use_metadata_tags",
            "-metadata", "comment=fit-trimmer-hud-burned",
            "-metadata", "fit_trimmer_ground_truth=manual_time_offset",
            "-metadata", "fit_trimmer_source_video_path=$sourceVideoPath"
        )
        if (sourceVideoStartUtc.isNotEmpty()) {
            args.add("-metadata")
            args.add("fit_trimmer_source_video_start_utc=$sourceVideoStartUtc")
        }
        if (alignedVideoStartUtc.isNotEmpty()) {
            args.add("-metadata")
            args.add("fit_trimmer_aligned_video_start_utc=$alignedVideoStartUtc")
        }
        args.add("-metadata")
        args.add("fit_trimmer_time_offset_ms=$timeOffsetMillis")
        if (imuTimeOffsetMillis != null) {
            args.add("-metadata")
            args.add("fit_trimmer_imu_offset_ms=$imuTimeOffsetMillis")
        }
        return args
    }
}

interface HudEncoder {
    fun encode(
        fitPath: String,
        videoPath: String,
        output: String,
        startUtc: String,
        maxDurationSeconds: Int = -1,
        trimStartSeconds: Double = 0.0,
        trimEndSeconds: Double = -1.0,
        shouldResume: Boolean = false,
        skipConcat: Boolean = false,
        groundTruthMetadata: EncodeGroundTruthMetadata? = null
    )
}

interface HudEncoderFactory {
    fun create(
        settings: HudSettings,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onFrameRendered: (java.awt.image.BufferedImage) -> Unit = {},
        pauseSupplier: () -> Boolean = { false },
        cancelSupplier: () -> Boolean = { false },
        customRenderer: ((HudCanvas, TelemetryPoint, List<TelemetryPoint>, List<TelemetryPoint>, List<Double>, Float) -> Unit)? = null,
        showLivePreviewSupplier: () -> Boolean = { true },
        profileSink: ((EncodeProfileReport) -> Unit)? = null
    ): HudEncoder
}
