package viewmodel

import fit.HudSettings
import fit.FitParser
import utils.GuiPathCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class AppViewModelTest {

    @Test
    fun testInitializationWithNullCache() {
        val viewModel = AppViewModel(null)
        assertEquals("", viewModel.fitPath)
        assertEquals("", viewModel.videoPath)
        assertFalse(viewModel.isGeneratingProxy)
        assertEquals(0f, viewModel.proxyProgress)
        assertNull(viewModel.proxyVideoPath)
        assertEquals("2026-06-21T02:09:49Z", viewModel.videoStartUtc)
        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(0.0, viewModel.trimEndSeconds)
        assertTrue(viewModel.splitPoints.isEmpty())
    }

    @Test
    fun testInitializationWithCache() {
        val cache = GuiPathCache(
            fitPath = "/path/to/fit",
            videoPath = "/path/to/video",
            videoStartUtc = "2026-06-29T10:00:00Z",
            trimStartSeconds = 10.0,
            trimEndSeconds = 100.0,
            splitPoints = listOf(30.0, 60.0),
            settings = HudSettings(exportResolution = "1080p"),
            timeOffsetMillis = 5000,
            timeOffsetSeconds = null,
            moveOutputToSource = true,
            showLivePreview = false
        )
        val viewModel = AppViewModel(cache)
        assertEquals("/path/to/fit", viewModel.fitPath)
        assertEquals("/path/to/video", viewModel.videoPath)
        assertEquals("2026-06-29T10:00:00Z", viewModel.videoStartUtc)
        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(100.0, viewModel.trimEndSeconds)
        assertEquals(listOf(30.0, 60.0), viewModel.splitPoints)
        assertEquals("1080p", viewModel.settings.exportResolution)
        assertEquals(5000, viewModel.timeOffsetState.millis)
        assertTrue(viewModel.moveOutputToSource)
        assertFalse(viewModel.showLivePreview)
    }

    @Test
    fun testVideoPathChangeResetsTrimAndProxyStates() {
        val cache = GuiPathCache(
            fitPath = "/path/to/fit",
            videoPath = "/path/to/video",
            videoStartUtc = "2026-06-29T10:00:00Z",
            trimStartSeconds = 10.0,
            trimEndSeconds = 100.0,
            splitPoints = listOf(30.0, 60.0),
            settings = HudSettings(),
            timeOffsetMillis = 0,
            timeOffsetSeconds = null,
            moveOutputToSource = false,
            showLivePreview = true
        )
        val viewModel = AppViewModel(cache)
        viewModel.isGeneratingProxy = true
        viewModel.proxyProgress = 0.5f
        viewModel.proxyVideoPath = "/path/to/proxy"

        // Changing the video path
        viewModel.videoPath = "/path/to/new_video"

        assertEquals("/path/to/new_video", viewModel.videoPath)
        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(0.0, viewModel.trimEndSeconds)
        assertTrue(viewModel.splitPoints.isEmpty())
        assertFalse(viewModel.isGeneratingProxy)
        assertEquals(0f, viewModel.proxyProgress)
        assertNull(viewModel.proxyVideoPath)
    }

    @Test
    fun testAddRemoveSplitPoint() {
        val viewModel = AppViewModel(null)
        viewModel.trimStartSeconds = 10.0
        viewModel.trimEndSeconds = 100.0

        // 1. Add valid split point
        viewModel.addSplitPoint(30.0)
        assertEquals(listOf(30.0), viewModel.splitPoints)

        // 2. Add another valid split point
        viewModel.addSplitPoint(20.0)
        // Check sorted order
        assertEquals(listOf(20.0, 30.0), viewModel.splitPoints)

        // 3. Add out-of-range split point (lower than trimStart)
        viewModel.addSplitPoint(5.0)
        assertEquals(listOf(20.0, 30.0), viewModel.splitPoints)

        // 4. Add out-of-range split point (higher than trimEnd)
        viewModel.addSplitPoint(120.0)
        assertEquals(listOf(20.0, 30.0), viewModel.splitPoints)

        // 5. Add duplicate split point
        viewModel.addSplitPoint(20.0)
        assertEquals(listOf(20.0, 30.0), viewModel.splitPoints)

        // 6. Remove split point
        viewModel.removeSplitPoint(20.0)
        assertEquals(listOf(30.0), viewModel.splitPoints)

        // 7. Clear split points
        viewModel.clearSplitPoints()
        assertTrue(viewModel.splitPoints.isEmpty())
    }

    @Test
    fun testGetSplitRanges() {
        val viewModel = AppViewModel(null)
        viewModel.videoLengthMs = 120000L // 120 seconds
        viewModel.trimStartSeconds = 10.0
        viewModel.trimEndSeconds = 100.0
        viewModel.addSplitPoint(30.0)
        viewModel.addSplitPoint(60.0)
        viewModel.addSplitPoint(110.0) // Out of trim range, should be ignored

        val ranges = viewModel.getSplitRanges()
        // Ranges should be:
        // [10.0, 30.0], [30.0, 60.0], [60.0, 100.0]
        assertEquals(3, ranges.size)
        assertEquals(Pair(10.0, 30.0), ranges[0])
        assertEquals(Pair(30.0, 60.0), ranges[1])
        assertEquals(Pair(60.0, 100.0), ranges[2])
    }

    @Test
    fun testDerivedInstantsAndRanges() {
        val viewModel = AppViewModel(null)
        
        // Setup raw telemetry points (FIT epoch offset is 631065600L)
        // FIT timestamps: 1151310000 -> Instant: 1151310000 + 631065600 = 1782375600 (2026-06-21T08:20:00Z)
        val p1 = FitParser.TelemetryPoint(1151310000.0, 20.0, 150.0, 90.0, 140.0, 50.0, 0.0, 32.0, 130.0)
        val p2 = FitParser.TelemetryPoint(1151313600.0, 25.0, 200.0, 95.0, 150.0, 60.0, 0.0, 32.1, 130.1) // +1 hour
        viewModel.telemetryPoints = listOf(p1, p2)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        val expectedStart = java.time.Instant.ofEpochSecond(1151310000L + 631065600L)
        val expectedEnd = java.time.Instant.ofEpochSecond(1151313600L + 631065600L)
        assertEquals(expectedStart, viewModel.fitStartInstant)
        assertEquals(expectedEnd, viewModel.fitEndInstant)

        // Setup Video (10 minutes = 600000 ms)
        viewModel.videoLengthMs = 600000L
        viewModel.videoStartUtc = "2026-06-25T08:30:00Z" // Starts 10 mins after FIT start
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        val expectedVideoStart = java.time.Instant.parse("2026-06-25T08:30:00Z")
        val expectedVideoEnd = expectedVideoStart.plusMillis(600000L)
        assertEquals(expectedVideoStart, viewModel.videoStartInstant)
        assertEquals(expectedVideoEnd, viewModel.videoEndInstant)

        // Check range validation
        assertTrue(viewModel.isVideoInFitRange, "Video should be fully inside the FIT telemetry range")

        // Shift video start out of FIT range (e.g. video starts before FIT starts)
        viewModel.videoStartUtc = "2026-06-25T08:15:00Z" // FIT starts at 08:20:00, video is 10 mins (08:15 - 08:25)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertFalse(viewModel.isVideoInFitRange, "Video should be flagged as outside FIT range if it starts too early")

        // Shift video start so it ends after FIT ends
        viewModel.videoStartUtc = "2026-06-25T09:15:00Z" // FIT ends at 09:20:00, video (10m) ends at 09:25:00
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertFalse(viewModel.isVideoInFitRange, "Video should be flagged as outside FIT range if it ends too late")
    }

    @Test
    fun testRobustnessAgainstInvalidStartUtc() {
        val viewModel = AppViewModel(null)
        viewModel.videoStartUtc = "invalid-date-string"
        assertNull(viewModel.videoStartInstant, "Invalid videoStartUtc should return null instead of throwing an exception")
        assertNull(viewModel.videoEndInstant, "Invalid videoStartUtc should result in null videoEndInstant")
    }

    @Test
    fun testVideoLengthMsSetterScenarios() {
        // シナリオ1: キャッシュがある場合 (oldVal == 0L で既に trim が非ゼロ)
        val cache = utils.GuiPathCache(
            fitPath = "", videoPath = "", videoStartUtc = "",
            trimStartSeconds = 10.0, trimEndSeconds = 50.0,
            splitPoints = emptyList(), settings = fit.HudSettings(),
            timeOffsetMillis = 0, timeOffsetSeconds = null,
            moveOutputToSource = false, showLivePreview = true
        )
        val viewModel = AppViewModel(cache)
        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(50.0, viewModel.trimEndSeconds)

        // videoLengthMs を設定したとき、既に trimStart/End が非ゼロなら上書きされないこと
        viewModel.videoLengthMs = 100000L // 100s
        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(50.0, viewModel.trimEndSeconds)

        // シナリオ2: 2回目以降の設定 (oldVal > 0L)
        // 値が更新されると、トリム範囲は強制リセットされること
        viewModel.videoLengthMs = 120000L // 120s
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(120.0, viewModel.trimEndSeconds)
    }

    @Test
    fun testTimeOffsetStateAndAdjustedStartUtcDerivedState() {
        val viewModel = AppViewModel(null)
        viewModel.videoStartUtc = "2026-06-21T02:09:49Z"
        
        // 初期状態 (Offset = 0)
        assertEquals("2026-06-21T02:09:49Z", viewModel.adjustedStartUtc)

        // オフセットを +5000ms (+5s) に更新
        viewModel.timeOffsetState.update(5000)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        // 2026-06-21T02:09:49Z + 5s = 2026-06-21T02:09:54Z
        assertEquals("2026-06-21T02:09:54Z", viewModel.adjustedStartUtc)
    }

    @Test
    fun testTrimmedTelemetryPointsFiltering() {
        val viewModel = AppViewModel(null)
        val videoStartStr = "2026-06-21T02:09:49Z"
        viewModel.videoStartUtc = videoStartStr
        
        // 動画開始時刻のエポック秒とFIT基準エポック（1989-12-31T00:00:00Z = 631065600）の差分から、動画開始時点のFITタイムスタンプを動的に算出する
        val videoStart = java.time.Instant.parse(videoStartStr)
        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
        val baseFitTime = (videoStart.epochSecond - fitEpoch).toDouble()
        
        val p1 = FitParser.TelemetryPoint(baseFitTime, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 0s
        val p2 = FitParser.TelemetryPoint(baseFitTime + 5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 5s
        val p3 = FitParser.TelemetryPoint(baseFitTime + 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 10s
        val p4 = FitParser.TelemetryPoint(baseFitTime + 15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 15s

        viewModel.telemetryPoints = listOf(p1, p2, p3, p4)
        
        // トリム範囲: 5.0s ~ 12.0s (p2, p3 が含まれるべき)
        viewModel.trimStartSeconds = 5.0
        viewModel.trimEndSeconds = 12.0
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        val trimmed = viewModel.trimmedTelemetryPoints
        assertEquals(2, trimmed.size)
        assertEquals(baseFitTime + 5.0, trimmed[0].timestamp)
        assertEquals(baseFitTime + 10.0, trimmed[1].timestamp)
    }

    @Test
    fun testUpdateRoadCaptionStartAndEnd() {
        val cache = utils.GuiPathCache(
            fitPath = "", videoPath = "", videoStartUtc = "",
            trimStartSeconds = 0.0, trimEndSeconds = 0.0,
            splitPoints = emptyList(),
            settings = fit.HudSettings(
                roadCaptions = listOf(
                    fit.RoadCaptionSegment("id-1", 10.0, 20.0, "Route 1", true)
                )
            ),
            timeOffsetMillis = 0, timeOffsetSeconds = null,
            moveOutputToSource = false, showLivePreview = true
        )
        val viewModel = AppViewModel(cache)
        viewModel.videoLengthMs = 100000L // 100s

        // 始点の更新
        viewModel.updateRoadCaptionStart(0, 15.0)
        assertEquals(15.0, viewModel.settings.roadCaptions[0].startSeconds)

        // 始点の更新（終点を超える値は制限されること）
        viewModel.updateRoadCaptionStart(0, 25.0)
        assertEquals(20.0, viewModel.settings.roadCaptions[0].startSeconds) // 20.0(endSeconds)に制限される

        // 終点の更新
        viewModel.updateRoadCaptionEnd(0, 30.0)
        assertEquals(30.0, viewModel.settings.roadCaptions[0].endSeconds)

        // 終点の更新（始点を下回る値は制限されること）
        viewModel.updateRoadCaptionEnd(0, 5.0)
        assertEquals(20.0, viewModel.settings.roadCaptions[0].endSeconds) // 20.0(現在のstartSecondsである20.0)に制限される
    }
}

