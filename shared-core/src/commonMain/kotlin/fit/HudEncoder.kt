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
            "total_ms=${totalElapsedMs.formatDecimals(2)} " +
            "mask_plan_ms=${maskPlanMs.formatDecimals(2)} " +
            "mask_video_ms=${maskVideoMs.formatDecimals(2)} " +
            "ffmpeg_active_ms=${ffmpegActiveMs.formatDecimals(2)} " +
            "frames=$frameCount " +
            "telemetry_ms=${telemetryMs.formatDecimals(2)} " +
            "hud_render_ms=${hudRenderMs.formatDecimals(2)} " +
            "raw_copy_ms=${rawCopyMs.formatDecimals(2)} " +
            "buffer_wait_ms=${bufferWaitMs.formatDecimals(2)} " +
            "queue_put_ms=${queuePutMs.formatDecimals(2)} " +
            "live_preview_ms=${livePreviewMs.formatDecimals(2)} " +
            "progress_ms=${progressMs.formatDecimals(2)} " +
            "pipe_write_ms=${pipeWriteMs.formatDecimals(2)} " +
            "pipe_mib=${pipeMiB.formatDecimals(2)} " +
            "avg_hud_ms=${avgHudRenderMs.formatDecimals(3)} " +
            "avg_copy_ms=${avgRawCopyMs.formatDecimals(3)} " +
            "avg_wait_ms=${avgBufferWaitMs.formatDecimals(3)} " +
            "avg_pipe_ms=${avgPipeWriteMs.formatDecimals(3)}"
}

private fun Double.formatDecimals(digits: Int): String {
    if (this.isNaN() || this.isInfinite()) return this.toString()
    var factor = 1.0
    repeat(digits) { factor *= 10.0 }
    val rounded = kotlin.math.round(this * factor).toLong()
    val isNegative = rounded < 0
    val absRounded = kotlin.math.abs(rounded)
    val intPart = absRounded / factor.toLong()
    val fracPart = absRounded % factor.toLong()
    val fracStr = fracPart.toString().padStart(digits, '0')
    val sign = if (isNegative) "-" else ""
    return "$sign$intPart.$fracStr"
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
        onFrameRendered: (Any) -> Unit = {},
        pauseSupplier: () -> Boolean = { false },
        cancelSupplier: () -> Boolean = { false },
        customRenderer: ((HudCanvas, TelemetryPoint, List<TelemetryPoint>, List<TelemetryPoint>, List<Double>, Float) -> Unit)? = null,
        showLivePreviewSupplier: () -> Boolean = { true },
        profileSink: ((EncodeProfileReport) -> Unit)? = null
    ): HudEncoder
}
