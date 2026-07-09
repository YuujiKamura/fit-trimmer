package utils

import fit.HudEncoder
import fit.HudEncoderFactory
import fit.HudSettings
import fit.EncodeProfileReport
import fit.EncodeGroundTruthMetadata
import fit.TelemetryPoint
import fit.HudCanvas
import java.awt.image.BufferedImage

class FakeHudEncoder(
    var onEncodeCalled: (() -> Unit)? = null,
    var shouldThrowError: Boolean = false,
    var onProgress: (Float, String) -> Unit = { _, _ -> },
    var profileSink: ((EncodeProfileReport) -> Unit)? = null
) : HudEncoder {

    override fun encode(
        fitPath: String,
        videoPath: String,
        output: String,
        startUtc: String,
        maxDurationSeconds: Int,
        trimStartSeconds: Double,
        trimEndSeconds: Double,
        shouldResume: Boolean,
        skipConcat: Boolean,
        groundTruthMetadata: EncodeGroundTruthMetadata?
    ) {
        onEncodeCalled?.invoke()

        if (shouldThrowError) {
            throw java.io.IOException("Fake pipe write error: FFmpeg process terminated prematurely")
        }

        onProgress(0.0f, "Fake encoding start")
        onProgress(0.5f, "Fake encoding progress")
        onProgress(1.0f, "Fake encoding complete")

        profileSink?.invoke(
            EncodeProfileReport(
                totalElapsedMs = 1500.0,
                maskPlanMs = 0.0,
                maskVideoMs = 0.0,
                ffmpegActiveMs = 100.0,
                frameCount = 10,
                telemetryMs = 0.0,
                hudRenderMs = 10.0,
                rawCopyMs = 1.0,
                bufferWaitMs = 0.0,
                queuePutMs = 0.0,
                livePreviewMs = 0.0,
                progressMs = 0.0,
                pipeWriteMs = 10.0,
                pipeBytes = 1024
            )
        )
    }
}

class FakeHudEncoderFactory(
    var nextEncoder: FakeHudEncoder = FakeHudEncoder()
) : HudEncoderFactory {

    override fun create(
        settings: HudSettings,
        onProgress: (Float, String) -> Unit,
        onFrameRendered: (Any) -> Unit,
        pauseSupplier: () -> Boolean,
        cancelSupplier: () -> Boolean,
        customRenderer: ((HudCanvas, TelemetryPoint, List<TelemetryPoint>, List<TelemetryPoint>, List<Double>, Float) -> Unit)?,
        showLivePreviewSupplier: () -> Boolean,
        profileSink: ((EncodeProfileReport) -> Unit)?
    ): HudEncoder {
        nextEncoder.onProgress = onProgress
        nextEncoder.profileSink = profileSink
        return nextEncoder
    }
}
