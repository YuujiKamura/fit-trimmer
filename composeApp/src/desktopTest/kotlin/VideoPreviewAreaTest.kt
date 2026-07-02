import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.rememberTextMeasurer
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.kdroidfilter.composemediaplayer.*
import io.github.vinceglb.filekit.PlatformFile
import fit.HudConfig
import fit.HudSettings
import fit.DynamicRendererProxy
import components.VideoPreviewArea

class VideoPreviewAreaTest {

    // Mock platform state implementing PlatformVideoPlayerState using Kotlin properties
    class MockPlatformVideoPlayerState : PlatformVideoPlayerState {
        override var isPlaying: Boolean = false
        override var sliderPos: Float = 0f
        override var volume: Float = 1f
        override var hasMedia: Boolean = true
        override var metadata: VideoMetadata = VideoMetadata(duration = 10000L, width = 1920, height = 1080)
        override var isLoading: Boolean = false
        override var error: VideoPlayerError? = null
        override var loop: Boolean = false
        override var userDragging: Boolean = false
        override var positionText: String = ""
        override var durationText: String = ""
        override var leftLevel: Float = 0f
        override var rightLevel: Float = 0f
        override var subtitlesEnabled: Boolean = false
        override var currentSubtitleTrack: SubtitleTrack? = null
        override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()

        var lastOpenedUri: String? = null
        var lastSeekPos: Float? = null

        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun stop() { isPlaying = false }
        override fun seekTo(pos: Float) {
            lastSeekPos = pos
            sliderPos = pos
        }
        override fun openUri(uri: String) { lastOpenedUri = uri }

        override fun disableSubtitles() {}
        override fun selectSubtitleTrack(track: SubtitleTrack?) {}
        override fun dispose() {}
        override fun clearError() {}
    }

    private fun injectMock(playerState: VideoPlayerState, mock: PlatformVideoPlayerState) {
        val field = VideoPlayerState::class.java.getDeclaredField("delegate")
        field.isAccessible = true
        field.set(playerState, mock)
    }

    @Test
    fun testVideoPreviewArea_PlayPauseToggle() {
        val mock = MockPlatformVideoPlayerState()
        val playerState = VideoPlayerState()
        injectMock(playerState, mock)

        val dummyFile = File("src/desktopTest/kotlin/EncodePlanTest.kt")
        assertTrue(dummyFile.exists(), "Dummy file must exist for test")

        var currentTimeMs = 0L
        val scene = ImageComposeScene(width = 800, height = 400) {
            val textMeasurer = rememberTextMeasurer()
            val hudConfig = HudConfig(
                valSize = 40f,
                tightness = 1f,
                spacing = 20f,
                xOffset = 40f,
                yOffset = 40f,
                graphH = 60f,
                graphW = 300f
            )
            val rendererProxy = DynamicRendererProxy(hudConfig)
            VideoPreviewArea(
                videoPath = dummyFile.absolutePath,
                videoLengthMs = 10000L,
                adjustedStartUtc = "2026-07-02T12:00:00Z",
                telemetryPoints = emptyList(),
                trimmedTelemetryPoints = emptyList(),
                settings = HudSettings(),
                rendererProxy = rendererProxy,
                textMeasurer = textMeasurer,
                playerState = playerState,
                videoCurrentTimeMs = currentTimeMs,
                onCurrentTimeChange = { currentTimeMs = it },
                renderVideoSurface = false
            )
        }

        scene.use {
            val skiaImage = it.render()
            val bytes = skiaImage.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            if (bytes != null) {
                val f = File("build/VideoPreviewArea_debug.png")
                f.parentFile.mkdirs()
                f.writeBytes(bytes)
                println("Saved debug image to: ${f.absolutePath}")
            }

            Thread.sleep(500)
            it.render()
            
            // Check initial state
            assertEquals(false, mock.isPlaying)

            // Click play button (approx X=27, Y=348)
            it.sendPointerEvent(PointerEventType.Press, Offset(27f, 348f))
            it.sendPointerEvent(PointerEventType.Release, Offset(27f, 348f))
            Thread.sleep(100)
            it.render()

            // Verify play is called
            assertEquals(true, mock.isPlaying)

            // Click pause (same position)
            it.sendPointerEvent(PointerEventType.Press, Offset(27f, 348f))
            it.sendPointerEvent(PointerEventType.Release, Offset(27f, 348f))
            Thread.sleep(100)
            it.render()

            // Verify pause is called
            assertEquals(false, mock.isPlaying)
        }
    }

    @Test
    fun testVideoPreviewArea_SeekButtons() {
        val mock = MockPlatformVideoPlayerState()
        val playerState = VideoPlayerState()
        injectMock(playerState, mock)

        val dummyFile = File("src/desktopTest/kotlin/EncodePlanTest.kt")
        assertTrue(dummyFile.exists(), "Dummy file must exist for test")

        var seekedTime: Long? = null
        var currentTimeMs = 5000L // Start at 5s

        val scene = ImageComposeScene(width = 800, height = 400) {
            val textMeasurer = rememberTextMeasurer()
            val hudConfig = HudConfig(
                valSize = 40f,
                tightness = 1f,
                spacing = 20f,
                xOffset = 40f,
                yOffset = 40f,
                graphH = 60f,
                graphW = 300f
            )
            val rendererProxy = DynamicRendererProxy(hudConfig)
            VideoPreviewArea(
                videoPath = dummyFile.absolutePath,
                videoLengthMs = 10000L,
                adjustedStartUtc = "2026-07-02T12:00:00Z",
                telemetryPoints = emptyList(),
                trimmedTelemetryPoints = emptyList(),
                settings = HudSettings(),
                rendererProxy = rendererProxy,
                textMeasurer = textMeasurer,
                playerState = playerState,
                videoCurrentTimeMs = currentTimeMs,
                onCurrentTimeChange = { currentTimeMs = it },
                onSeekEnd = { seekedTime = it },
                renderVideoSurface = false
            )
        }

        scene.use {
            Thread.sleep(500)
            it.render()

            // Click "+1" button (approx X=561, Y=382) -> should seek to 5000 + 1000 = 6000ms
            it.sendPointerEvent(PointerEventType.Press, Offset(561f, 382f))
            it.sendPointerEvent(PointerEventType.Release, Offset(561f, 382f))
            Thread.sleep(100)
            it.render()
            assertEquals(6000L, seekedTime, "Clicking +1 should seek to 6000ms")

            // Click "-5" button (approx X=431, Y=382) -> should seek to 5000 - 5000 = 0ms
            it.sendPointerEvent(PointerEventType.Press, Offset(431f, 382f))
            it.sendPointerEvent(PointerEventType.Release, Offset(431f, 382f))
            Thread.sleep(100)
            it.render()
            assertEquals(0L, seekedTime, "Clicking -5 should seek to 0ms (clamped)")
        }
    }

    @Test
    fun testVideoPreviewArea_SliderDrag() {
        val mock = MockPlatformVideoPlayerState()
        val playerState = VideoPlayerState()
        injectMock(playerState, mock)

        val dummyFile = File("src/desktopTest/kotlin/EncodePlanTest.kt")
        assertTrue(dummyFile.exists(), "Dummy file must exist for test")

        val isSeekingState = androidx.compose.runtime.mutableStateOf(false)
        val seekTargetTimeMsState = androidx.compose.runtime.mutableStateOf(0L)
        var progressTime: Long? = null
        var seekedTime: Long? = null
        var currentTimeMs = 0L

        val scene = ImageComposeScene(width = 800, height = 400) {
            val textMeasurer = rememberTextMeasurer()
            val hudConfig = HudConfig(
                valSize = 40f,
                tightness = 1f,
                spacing = 20f,
                xOffset = 40f,
                yOffset = 40f,
                graphH = 60f,
                graphW = 300f
            )
            val rendererProxy = DynamicRendererProxy(hudConfig)
            VideoPreviewArea(
                videoPath = dummyFile.absolutePath,
                videoLengthMs = 10000L,
                adjustedStartUtc = "2026-07-02T12:00:00Z",
                telemetryPoints = emptyList(),
                trimmedTelemetryPoints = emptyList(),
                settings = HudSettings(),
                rendererProxy = rendererProxy,
                textMeasurer = textMeasurer,
                playerState = playerState,
                videoCurrentTimeMs = currentTimeMs,
                onCurrentTimeChange = { currentTimeMs = it },
                isSeeking = isSeekingState.value,
                seekTargetTimeMs = seekTargetTimeMsState.value,
                onSeekStart = { isSeekingState.value = true },
                onSeekProgress = { 
                    progressTime = it
                    seekTargetTimeMsState.value = it
                },
                onSeekEnd = { 
                    seekedTime = it
                    currentTimeMs = it
                    isSeekingState.value = false
                },
                renderVideoSurface = false
            )
        }

        scene.use {
            Thread.sleep(500)
            it.render()

            // Slider is approx from X=150 to X=690. Total width 540px.
            // Click and drag slightly at middle (X=420, Y=348) to seek to 50% (5000ms)
            it.sendPointerEvent(PointerEventType.Press, Offset(420f, 348f))
            Thread.sleep(50)
            it.render()
            
            it.sendPointerEvent(PointerEventType.Move, Offset(421f, 348f))
            Thread.sleep(50)
            it.render()

            it.sendPointerEvent(PointerEventType.Release, Offset(421f, 348f))
            Thread.sleep(100)
            it.render()

            // We expect onSeekEnd to be called with a value around 5000ms
            assertTrue(seekedTime != null && Math.abs(seekedTime!! - 5000L) < 500L, "Slider click near middle should seek near 5000ms. Got: $seekedTime")
        }
    }

    @Test
    fun testVideoPreviewArea_EOF_Replay() {
        val mock = MockPlatformVideoPlayerState()
        val playerState = VideoPlayerState()
        injectMock(playerState, mock)

        val dummyFile = File("src/desktopTest/kotlin/EncodePlanTest.kt")
        assertTrue(dummyFile.exists(), "Dummy file must exist for test")

        var currentTimeMs = 9800L // 9.8s (EOF state since videoLengthMs = 10s and 9800 >= 9500)

        val scene = ImageComposeScene(width = 800, height = 400) {
            val textMeasurer = rememberTextMeasurer()
            val hudConfig = HudConfig(
                valSize = 40f,
                tightness = 1f,
                spacing = 20f,
                xOffset = 40f,
                yOffset = 40f,
                graphH = 60f,
                graphW = 300f
            )
            val rendererProxy = DynamicRendererProxy(hudConfig)
            VideoPreviewArea(
                videoPath = dummyFile.absolutePath,
                videoLengthMs = 10000L,
                adjustedStartUtc = "2026-07-02T12:00:00Z",
                telemetryPoints = emptyList(),
                trimmedTelemetryPoints = emptyList(),
                settings = HudSettings(),
                rendererProxy = rendererProxy,
                textMeasurer = textMeasurer,
                playerState = playerState,
                videoCurrentTimeMs = currentTimeMs,
                onCurrentTimeChange = { currentTimeMs = it },
                renderVideoSurface = false
            )
        }

        scene.use {
            Thread.sleep(500)
            it.render()

            // Click play button (approx X=27, Y=348) at EOF
            it.sendPointerEvent(PointerEventType.Press, Offset(27f, 348f))
            it.sendPointerEvent(PointerEventType.Release, Offset(27f, 348f))
            
            // Wait for coroutine inside togglePlayButton (it does seekTo(0f), onCurrentTimeChange(0L), delay(700), play())
            // We simulate progression of time
            for (i in 0..10) {
                Thread.sleep(100)
                it.render()
            }

            // Verify it seeked back to 0
            assertEquals(0L, currentTimeMs, "Replay from EOF should reset currentTime to 0")
            // Verify play is eventually called
            assertEquals(true, mock.isPlaying, "Replay from EOF should start playing again")
        }
    }
}
