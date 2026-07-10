package viewmodel

import fit.TelemetryPoint

import fit.HudSettings
import fit.FitParser
import fit.PlateBox
import fit.PlateCacheManager
import fit.PlateRecord
import fit.VideoPlatesCache
import kotlinx.coroutines.runBlocking
import utils.GuiPathCache
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.BeforeTest

class AppViewModelTest {

    @BeforeTest
    fun setUp() {
        utils.BatchQueueCache.file.delete()
    }

    @Test
    fun testInitializationWithNullCache() {
        val viewModel = AppViewModel(null)
        assertEquals("", viewModel.fitPath)
        assertEquals("", viewModel.videoPath)
        assertFalse(viewModel.isGeneratingProxy)
        assertEquals(0f, viewModel.proxyProgress)
        assertNull(viewModel.proxyVideoPath)
        // videoStartUtc is empty when no cache exists (populated by getVideoStartUtc() after load)
        assertEquals("", viewModel.videoStartUtc)
        assertEquals("original", viewModel.previewQualityMode)
        assertFalse(viewModel.autoDetectRoadCaptionsOnEncode)
        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(0.0, viewModel.trimEndSeconds)
        assertTrue(viewModel.splitPoints.isEmpty())
        assertTrue(viewModel.settings.enableRoadDetection)
    }

    @Test
    fun testTelemetryCuttingFlow() {
        val viewModel = AppViewModel(null)
        viewModel.videoStartUtc = "1989-12-31T00:00:05Z"
        
        val dummyPoints = List(30) { i ->
            TelemetryPoint(
                timestamp = i.toDouble(),
                speed = 25.0,
                cadence = 90.0,
                heartRate = 140.0,
                power = 200.0,
                elevation = 100.0,
                distance = i * 7.0,
                grade = 0.0
            )
        }
        
        viewModel.updateTelemetry(dummyPoints)
        assertFalse(viewModel.isTelemetryCut)
        assertEquals(30, viewModel.telemetryPoints.size)
        assertEquals(30, viewModel.originalTelemetryPoints.size)
        
        viewModel.cutTelemetry(
            trimStartSec = 10.0,
            trimEndSec = 20.0,
            videoStartUtcStr = viewModel.videoStartUtc
        )
        
        assertTrue(viewModel.isTelemetryCut)
        assertEquals(11, viewModel.telemetryPoints.size)
        assertEquals(10.0, viewModel.telemetryPoints.first().timestamp)
        assertEquals(20.0, viewModel.telemetryPoints.last().timestamp)
        
        assertEquals(30, viewModel.originalTelemetryPoints.size)
        
        assertEquals(5000, viewModel.timeOffsetState.millis)
    }

    @Test
    fun testConfirmTelemetryRangeUsesAlignedVideoRangeAndPreservesOffset() {
        val viewModel = AppViewModel(null)
        val dummyPoints = List(30) { i ->
            TelemetryPoint(
                timestamp = i.toDouble(),
                speed = 25.0,
                cadence = 90.0,
                heartRate = 140.0,
                power = 200.0,
                elevation = 100.0,
                distance = i * 7.0,
                grade = 0.0
            )
        }

        viewModel.updateTelemetry(dummyPoints)
        viewModel.timeOffsetState.update(5000)

        viewModel.confirmTelemetryRangeForVideo(
            alignedVideoStartUtcStr = "1989-12-31T00:00:10Z",
            videoDurationSec = 8.0
        )

        assertTrue(viewModel.isTelemetryCut)
        assertEquals(9, viewModel.telemetryPoints.size)
        assertEquals(10.0, viewModel.telemetryPoints.first().timestamp)
        assertEquals(18.0, viewModel.telemetryPoints.last().timestamp)
        assertEquals(30, viewModel.originalTelemetryPoints.size)
        assertEquals(5000, viewModel.timeOffsetState.millis)

        viewModel.resetTelemetryCut()

        assertFalse(viewModel.isTelemetryCut)
        assertEquals(30, viewModel.telemetryPoints.size)
        assertEquals(30, viewModel.originalTelemetryPoints.size)
    }

    @Test
    fun testVideoPathChangeRestoresFullTelemetryPoints() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "video1.mp4"
        
        val dummyPoints = List(10) { i ->
            TelemetryPoint(
                timestamp = i.toDouble(),
                speed = 20.0, cadence = 80.0, heartRate = 120.0, power = 150.0,
                elevation = 50.0, distance = i * 5.0, grade = 0.0
            )
        }
        viewModel.updateTelemetry(dummyPoints)
        
        viewModel.cutTelemetry(
            trimStartSec = 3.0,
            trimEndSec = 5.0,
            videoStartUtcStr = ""
        )
        assertTrue(viewModel.isTelemetryCut)
        assertEquals(3, viewModel.telemetryPoints.size)
        
        // Scenario A: Same video path loaded again (reload)
        viewModel.videoPath = "video1.mp4"
        assertFalse(viewModel.isTelemetryCut)
        assertEquals(10, viewModel.telemetryPoints.size)
        
        // Trim/Cut again
        viewModel.cutTelemetry(
            trimStartSec = 3.0,
            trimEndSec = 5.0,
            videoStartUtcStr = ""
        )
        assertTrue(viewModel.isTelemetryCut)
        
        // Scenario B: Different video path loaded
        viewModel.videoPath = "video2.mp4"
        assertFalse(viewModel.isTelemetryCut)
        assertEquals(10, viewModel.telemetryPoints.size)
    }

    @Test
    fun testEncodingPhaseTransitionsKeepCompletionSeparateFromActiveEncoding() {
        val viewModel = AppViewModel(null)

        viewModel.beginEncoding()
        assertEquals(EncodePhase.Preparing, viewModel.encodePhase)
        assertTrue(viewModel.isEncoding)
        assertFalse(viewModel.isCanceled)

        viewModel.updateEncodingProgress(0.5f, "Merging video segments...")
        assertEquals(EncodePhase.Merging, viewModel.encodePhase)
        assertTrue(viewModel.isEncoding)
        assertEquals(0.5f, viewModel.progress)

        viewModel.completeEncoding("Finished")
        assertEquals(EncodePhase.Completed, viewModel.encodePhase)
        assertFalse(viewModel.isEncoding)
        assertEquals(1.0f, viewModel.progress)
        assertEquals("Finished", viewModel.statusText)
    }

    @Test
    fun testEncodingPhaseTransitionsPreserveFailureAndCancelAsInactiveStates() {
        val viewModel = AppViewModel(null)

        viewModel.beginEncoding()
        viewModel.failEncoding("boom")
        assertEquals(EncodePhase.Failed, viewModel.encodePhase)
        assertFalse(viewModel.isEncoding)
        assertEquals("boom", viewModel.statusText)

        viewModel.beginEncoding()
        viewModel.cancelEncoding()
        assertEquals(EncodePhase.Canceled, viewModel.encodePhase)
        assertFalse(viewModel.isEncoding)
        assertTrue(viewModel.isCanceled)
    }

    @Test
    fun testResetPlateDetectionClearsStateAndDeletesCache() {
        val videoFile = File(System.getProperty("java.io.tmpdir"), "fittrimmer-reset-plate-test.mp4")
        videoFile.writeText("placeholder")
        val cacheFile = PlateCacheManager.getPlatesFile(videoFile.absolutePath)
        cacheFile?.delete()

        PlateCacheManager.saveCache(
            videoFile.absolutePath,
            VideoPlatesCache(
                videoPath = videoFile.absolutePath,
                records = listOf(
                    PlateRecord(1000L, listOf(PlateBox(1, 2, 30, 12))),
                    PlateRecord(2000L, listOf(PlateBox(4, 5, 40, 18)))
                )
            )
        )

        val viewModel = AppViewModel(null)
        viewModel.videoPath = videoFile.absolutePath

        assertNotNull(viewModel.plateCache)
        assertEquals(2, viewModel.plateRecordCount)
        assertEquals(2, viewModel.plateBoxCount)
        assertTrue(cacheFile?.exists() == true)

        viewModel.resetPlateDetection()

        assertNull(viewModel.plateCache)
        assertEquals(0, viewModel.plateRecordCount)
        assertEquals(0, viewModel.plateBoxCount)
        assertEquals("", viewModel.plateDetectionProgress)
        assertNull(viewModel.plateDetectionError)
        assertFalse(cacheFile?.exists() == true)

        videoFile.delete()
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
        // videoStartUtc is used as initial value from cache so HUD works immediately on startup
        // (it gets overwritten by getVideoStartUtc() once the file loads)
        assertEquals("2026-06-29T10:00:00Z", viewModel.videoStartUtc)
        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(100.0, viewModel.trimEndSeconds)
        assertEquals(listOf(30.0, 60.0), viewModel.splitPoints)
        assertEquals("1080p", viewModel.settings.exportResolution)
        assertEquals(5000, viewModel.timeOffsetState.millis)
        assertTrue(viewModel.moveOutputToSource)
        assertFalse(viewModel.showLivePreview)
        assertEquals("original", viewModel.previewQualityMode)
        assertFalse(viewModel.autoDetectRoadCaptionsOnEncode)
        assertTrue(viewModel.settings.enableRoadDetection)
    }

    @Test
    fun testPreviewQualityModeRestoredFromCache() {
        val cache = GuiPathCache(
            fitPath = "/path/to/fit",
            videoPath = "/path/to/video",
            videoStartUtc = "2026-06-29T10:00:00Z",
            settings = HudSettings(),
            previewQualityMode = "auto"
        )
        val viewModel = AppViewModel(cache)
        assertEquals("auto", viewModel.previewQualityMode)
    }

    @Test
    fun testAutoDetectRoadCaptionsOnEncodeRestoredFromCache() {
        val cache = GuiPathCache(
            fitPath = "/path/to/fit",
            videoPath = "/path/to/video",
            videoStartUtc = "2026-06-29T10:00:00Z",
            settings = HudSettings(),
            autoDetectRoadCaptionsOnEncode = true
        )
        val viewModel = AppViewModel(cache)
        assertTrue(viewModel.autoDetectRoadCaptionsOnEncode)
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
        val p1 = TelemetryPoint(1151310000.0, 20.0, 150.0, 90.0, 140.0, 50.0, 0.0, 32.0, 130.0)
        val p2 = TelemetryPoint(1151313600.0, 25.0, 200.0, 95.0, 150.0, 60.0, 0.0, 32.1, 130.1) // +1 hour
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
        // 再取得された動画長で、既存のトリム範囲が破壊されないこと
        viewModel.videoLengthMs = 120000L // 120s
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(50.0, viewModel.trimEndSeconds)
    }

    @Test
    fun testVideoLengthMsSetterClampsOnlyWhenExistingTrimExceedsDuration() {
        val viewModel = AppViewModel(null)
        viewModel.videoLengthMs = 120000L
        viewModel.trimStartSeconds = 10.0
        viewModel.trimEndSeconds = 110.0

        viewModel.videoLengthMs = 90000L
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        assertEquals(10.0, viewModel.trimStartSeconds)
        assertEquals(90.0, viewModel.trimEndSeconds)
    }

    @Test
    fun testPlateScanUsesVideoDurationWhenTrimEndIsZero() = runBlocking {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/tmp/ride.mp4"
        viewModel.videoLengthMs = 120000L
        viewModel.trimStartSeconds = 0.0
        viewModel.trimEndSeconds = 0.0

        var capturedScanRanges: List<Pair<Double, Double>>? = null
        viewModel.plateDetector = object : fit.PlateDetector {
            override suspend fun detect(
                videoPath: String,
                telemetryPoints: List<fit.TelemetryPoint>,
                adjustedStartUtc: String,
                onProgress: (Float, String) -> Unit,
                onCancel: () -> Boolean,
                onPartialResult: (fit.VideoPlatesCache) -> Unit,
                maxRecords: Int?,
                saveCache: Boolean,
                settings: fit.HudSettings,
                scanRanges: List<Pair<Double, Double>>?
            ): fit.VideoPlatesCache? {
                capturedScanRanges = scanRanges
                return fit.VideoPlatesCache(videoPath = videoPath, records = emptyList())
            }
        }

        viewModel.executePlateScanCore(
            videoPath = viewModel.videoPath,
            telemetryPoints = emptyList(),
            adjustedStartUtc = "",
            trimStart = viewModel.trimStartSeconds,
            trimEnd = viewModel.trimEndSeconds,
            settings = fit.HudSettings(),
            onProgress = { _, _ -> },
            onCancel = { false }
        )

        assertEquals(listOf(0.0 to 120.0), capturedScanRanges)
    }

    @Test
    fun testManualPlateScanPreservesDetectionSettings() = runBlocking {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/tmp/ride.mp4"
        viewModel.settings = viewModel.settings.copy(
            detectPedestrians = true,
            plateMinMaskHeightRatio = 0.02
        )

        var capturedSettings: fit.HudSettings? = null
        viewModel.plateDetector = object : fit.PlateDetector {
            override suspend fun detect(
                videoPath: String,
                telemetryPoints: List<fit.TelemetryPoint>,
                adjustedStartUtc: String,
                onProgress: (Float, String) -> Unit,
                onCancel: () -> Boolean,
                onPartialResult: (fit.VideoPlatesCache) -> Unit,
                maxRecords: Int?,
                saveCache: Boolean,
                settings: fit.HudSettings,
                scanRanges: List<Pair<Double, Double>>?
            ): fit.VideoPlatesCache? {
                capturedSettings = settings
                return fit.VideoPlatesCache(videoPath = videoPath, records = emptyList())
            }
        }

        viewModel.executePlateScanCore(
            videoPath = viewModel.videoPath,
            telemetryPoints = emptyList(),
            adjustedStartUtc = "",
            trimStart = 0.0,
            trimEnd = 1.0,
            settings = viewModel.settings.copy(plateMaxSpeedKmh = 40.0),
            onProgress = { _, _ -> },
            onCancel = { false }
        )

        assertEquals(true, capturedSettings?.detectPedestrians)
        assertEquals(0.02, capturedSettings?.plateMinMaskHeightRatio)
        assertEquals(40.0, capturedSettings?.plateMaxSpeedKmh)
    }

    @Test
    fun testStoppedPlateScanNullResultDoesNotBecomeFailure() = runBlocking {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/tmp/ride.mp4"
        viewModel.plateDetector = object : fit.PlateDetector {
            override suspend fun detect(
                videoPath: String,
                telemetryPoints: List<fit.TelemetryPoint>,
                adjustedStartUtc: String,
                onProgress: (Float, String) -> Unit,
                onCancel: () -> Boolean,
                onPartialResult: (fit.VideoPlatesCache) -> Unit,
                maxRecords: Int?,
                saveCache: Boolean,
                settings: fit.HudSettings,
                scanRanges: List<Pair<Double, Double>>?
            ): fit.VideoPlatesCache? {
                viewModel.stopPlateDetection()
                return null
            }
        }

        viewModel.runPlateDetection(this)
        while (viewModel.isDetectingPlates) {
            kotlinx.coroutines.delay(10)
        }

        assertEquals("Stopped", viewModel.plateDetectionProgress)
        assertNull(viewModel.plateDetectionError)
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
        
        val p1 = TelemetryPoint(baseFitTime, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 0s
        val p2 = TelemetryPoint(baseFitTime + 5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 5s
        val p3 = TelemetryPoint(baseFitTime + 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 10s
        val p4 = TelemetryPoint(baseFitTime + 15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) // 15s

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
    fun testEditingCaptionIndexState() {
        val viewModel = AppViewModel(null)
        assertNull(viewModel.editingCaptionIndex)
        viewModel.editingCaptionIndex = 5
        assertEquals(5, viewModel.editingCaptionIndex)
        viewModel.editingCaptionIndex = null
        assertNull(viewModel.editingCaptionIndex)
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

    @Test
    fun testBatchQueueOperations() {
        val viewModel = AppViewModel(null)
        assertTrue(viewModel.batchQueue.isEmpty())
        
        // 1. Setup current state
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        viewModel.videoStartUtc = "2026-06-29T10:00:00Z"
        viewModel.trimStartSeconds = 10.0
        viewModel.trimEndSeconds = 50.0
        viewModel.addSplitPoint(30.0)
        viewModel.settings = viewModel.settings.copy(exportResolution = "720p")
        viewModel.autoDetectRoadCaptionsOnEncode = true
        
        // 2. Add to queue
        viewModel.addToBatchQueue()
        assertEquals(1, viewModel.batchQueue.size)
        val job = viewModel.batchQueue[0]
        assertEquals("/path/to/video1.mp4", job.videoPath)
        assertEquals("/path/to/fit1.fit", job.fitPath)
        assertEquals("2026-06-29T10:00:00Z", job.videoStartUtc)
        assertEquals(10.0, job.trimStartSeconds)
        assertEquals(50.0, job.trimEndSeconds)
        assertEquals(listOf(30.0), job.splitPoints)
        assertEquals("720p", job.settings.exportResolution)
        assertTrue(job.autoDetectRoadCaptionsOnEncode)
        assertEquals("video1_20260629_00m10s-00m30s_part1_KMP_HUD_orig.mp4 (+1)", job.entryName)
        assertEquals(
            listOf(
                "video1_20260629_00m10s-00m30s_part1_KMP_HUD_orig.mp4",
                "video1_20260629_00m30s-00m50s_part2_KMP_HUD_orig.mp4"
            ),
            job.outputFileNames
        )
        assertEquals(BatchJobStatus.WAITING, job.status)
        
        // 3. Add second job (after changing state)
        viewModel.videoPath = "/path/to/video2.mp4"
        viewModel.addToBatchQueue()
        assertEquals(2, viewModel.batchQueue.size)
        assertEquals("/path/to/video2.mp4", viewModel.batchQueue[1].videoPath)
        
        // 4. Remove job
        val firstJobId = job.id
        viewModel.removeFromBatchQueue(firstJobId)
        assertEquals(1, viewModel.batchQueue.size)
        assertEquals("/path/to/video2.mp4", viewModel.batchQueue[0].videoPath)
        
        // 5. Clear queue
        viewModel.clearBatchQueue()
        assertTrue(viewModel.batchQueue.isEmpty())
    }

    @Test
    fun testBatchFolderDiscoveryUsesLatestFitAndSourceVideosOnly() {
        val dir = createTempDirectory("fittrimmer-batch-folder-").toFile()
        try {
            val olderFit = File(dir, "Morning.fit").apply {
                writeText("fit")
                setLastModified(1000L)
            }
            val latestFit = File(dir, "Afternoon.fit").apply {
                writeText("fit")
                setLastModified(2000L)
            }
            File(dir, "VID_20260702_163959_001.mp4").writeText("video")
            File(dir, "VID_20260702_170526_002.mov").writeText("video")
            File(dir, "LRV_20260702_163959_001.lrv").writeText("proxy")

            val candidates = utils.BatchFolderLoader.discoverCandidates(dir.absolutePath)

            assertEquals(latestFit.absolutePath, candidates.fitFile?.absolutePath)
            assertEquals(
                listOf("VID_20260702_163959_001.mp4", "VID_20260702_170526_002.mov"),
                candidates.videoFiles.map { it.name }
            )
            assertTrue(olderFit.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testEnqueueBatchFolderAddsVideosOnceAndReusesJobSettings() = runBlocking {
        val dir = createTempDirectory("fittrimmer-batch-enqueue-").toFile()
        try {
            File(dir, "Afternoon_Ride.fit").writeText("fit")
            val video1 = File(dir, "VID_20260702_163959_001.mp4").apply { writeText("video") }
            val video2 = File(dir, "VID_20260702_170526_002.mp4").apply { writeText("video") }
            File(dir, "LRV_20260702_163959_001.lrv").writeText("proxy")
            val viewModel = AppViewModel(null)
            viewModel.batchFolderPath = dir.absolutePath
            viewModel.autoDetectRoadCaptionsOnEncode = true
            viewModel.settings = viewModel.settings.copy(exportResolution = "720p", blurLicensePlates = true)

            val (jobs1, status1) = utils.BatchFolderLoader.loadJobs(
                folderPath = dir.absolutePath,
                currentSettings = viewModel.settings,
                autoDetectRoadCaptions = viewModel.autoDetectRoadCaptionsOnEncode,
                timeOffsetMillis = viewModel.timeOffsetState.millis.toLong(),
                existingVideoPaths = viewModel.batchQueue.map { it.videoPath },
                durationProvider = { 120_000L },
                startUtcProvider = { "2026-07-02T07:39:59Z" }
            )
            viewModel.batchQueue.addAll(jobs1)
            val added = jobs1.size

            val (jobs2, status2) = utils.BatchFolderLoader.loadJobs(
                folderPath = dir.absolutePath,
                currentSettings = viewModel.settings,
                autoDetectRoadCaptions = viewModel.autoDetectRoadCaptionsOnEncode,
                timeOffsetMillis = viewModel.timeOffsetState.millis.toLong(),
                existingVideoPaths = viewModel.batchQueue.map { it.videoPath },
                durationProvider = { 120_000L },
                startUtcProvider = { "2026-07-02T07:39:59Z" }
            )
            viewModel.batchQueue.addAll(jobs2)
            val addedAgain = jobs2.size

            assertEquals(2, added)
            assertEquals(0, addedAgain)
            assertEquals(2, viewModel.batchQueue.size)
            assertEquals(video1.absolutePath, viewModel.batchQueue[0].videoPath)
            assertEquals(video2.absolutePath, viewModel.batchQueue[1].videoPath)
            assertEquals(120.0, viewModel.batchQueue[0].trimEndSeconds)
            assertEquals("720p", viewModel.batchQueue[0].settings.exportResolution)
            assertTrue(viewModel.batchQueue[0].settings.blurLicensePlates)
            assertTrue(viewModel.batchQueue[0].autoDetectRoadCaptionsOnEncode)

            viewModel.setBatchJobRoadCaptionDetection(viewModel.batchQueue[0].id, false)
            viewModel.setBatchJobPlateMasking(viewModel.batchQueue[0].id, false)
            assertFalse(viewModel.batchQueue[0].autoDetectRoadCaptionsOnEncode)
            assertFalse(viewModel.batchQueue[0].settings.blurLicensePlates)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testBatchConfirmDialogRequiresRunnableJobs() {
        val viewModel = AppViewModel(null)

        assertFalse(viewModel.requestBatchConfirmDialog("test-empty"))
        assertFalse(viewModel.showBatchConfirmDialog)
        assertEquals("処理待ちのジョブがありません。", viewModel.batchStatusText)

        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        viewModel.addToBatchQueue()

        assertTrue(viewModel.requestBatchConfirmDialog("test-waiting"))
        assertTrue(viewModel.showBatchConfirmDialog)

        viewModel.dismissBatchConfirmDialog("test")
        assertFalse(viewModel.showBatchConfirmDialog)
    }

    @Test
    fun testBatchConfirmDialogDismissClearsQueue() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        viewModel.addToBatchQueue()

        assertEquals(1, viewModel.batchQueue.size)
        assertTrue(viewModel.requestBatchConfirmDialog("test"))
        
        // dismiss
        viewModel.dismissBatchConfirmDialog("cancel-button")
        
        // The queue should be cleared
        assertEquals(0, viewModel.batchQueue.size)
    }

    @Test
    fun testPrepareBatchQueueForStartResetsFinishedJobs() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        viewModel.addToBatchQueue()
        viewModel.videoPath = "/path/to/video2.mp4"
        viewModel.addToBatchQueue()

        viewModel.batchQueue[0].status = BatchJobStatus.COMPLETED
        viewModel.batchQueue[0].progress = 1f
        viewModel.batchQueue[1].status = BatchJobStatus.FAILED
        viewModel.batchQueue[1].progress = 0.25f
        viewModel.batchQueue[1].errorMessage = "boom"

        viewModel.prepareBatchQueueForStart()

        assertEquals(BatchJobStatus.WAITING, viewModel.batchQueue[0].status)
        assertEquals(0f, viewModel.batchQueue[0].progress)
        assertNull(viewModel.batchQueue[0].errorMessage)
        assertEquals(BatchJobStatus.WAITING, viewModel.batchQueue[1].status)
        assertEquals(0f, viewModel.batchQueue[1].progress)
        assertNull(viewModel.batchQueue[1].errorMessage)
    }

    @Test
    fun testBatchQueueReorderOperations() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.addToBatchQueue()
        viewModel.videoPath = "/path/to/video2.mp4"
        viewModel.addToBatchQueue()
        viewModel.videoPath = "/path/to/video3.mp4"
        viewModel.addToBatchQueue()

        val firstId = viewModel.batchQueue[0].id
        val secondId = viewModel.batchQueue[1].id
        val thirdId = viewModel.batchQueue[2].id

        viewModel.moveBatchJobUp(thirdId)
        assertEquals(listOf(firstId, thirdId, secondId), viewModel.batchQueue.map { it.id })

        viewModel.moveBatchJobDown(firstId)
        assertEquals(listOf(thirdId, firstId, secondId), viewModel.batchQueue.map { it.id })

        viewModel.moveBatchJobUp(thirdId)
        assertEquals(listOf(thirdId, firstId, secondId), viewModel.batchQueue.map { it.id })

        viewModel.moveBatchJobDown(secondId)
        assertEquals(listOf(thirdId, firstId, secondId), viewModel.batchQueue.map { it.id })
    }

    @Test
    fun testRemovingLastRunnableJobClosesBatchConfirmDialog() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.addToBatchQueue()

        assertTrue(viewModel.requestBatchConfirmDialog("test"))
        viewModel.removeFromBatchQueue(viewModel.batchQueue[0].id)

        assertTrue(viewModel.batchQueue.isEmpty())
        assertFalse(viewModel.showBatchConfirmDialog)
    }

    @Test
    fun testRenameBatchJobEntryUpdatesOutputFileName() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        viewModel.addToBatchQueue()
        val jobId = viewModel.batchQueue[0].id

        viewModel.renameBatchJobEntry(jobId, "custom_name")

        assertEquals("custom_name.mp4", viewModel.batchQueue[0].entryName)
        assertEquals(listOf("custom_name.mp4"), viewModel.batchQueue[0].outputFileNames)
    }

    @Test
    fun testBatchEntryNameDoesNotDuplicateDateAlreadyInSourceName() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/VID_20260630_174458_001.mp4"
        viewModel.videoStartUtc = "2026-06-30T08:44:58Z"
        viewModel.videoLengthMs = 1_799_130L
        viewModel.trimStartSeconds = 197.465
        viewModel.trimEndSeconds = 339.082

        viewModel.addToBatchQueue()

        assertEquals(
            "VID_20260630_174458_001_03m17s-05m39s_KMP_HUD_2.7k.mp4",
            viewModel.batchQueue[0].entryName
        )
    }

    @Test
    fun testVideoPathChangeSavesAndRestoresHistory() {
        // Clean history files if any from previous test runs
        val cleanHistory = {
            val historyDir = java.io.File(System.getProperty("user.home"), ".fittrimmer_history")
            if (historyDir.exists()) {
                historyDir.deleteRecursively()
            }
        }
        cleanHistory()
        
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/my_video1.mp4"
        viewModel.trimStartSeconds = 15.5
        viewModel.trimEndSeconds = 85.2
        viewModel.settings = viewModel.settings.copy(
            roadCaptions = listOf(
                fit.RoadCaptionSegment("id-hist", 10.0, 20.0, "Saved History Route", true)
            )
        )
        
        // Changing videoPath should trigger history save for video1.mp4
        viewModel.videoPath = "/path/to/my_video2.mp4"
        
        // UI values for video2.mp4 should be clean (flashed)
        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(0.0, viewModel.trimEndSeconds)
        assertTrue(viewModel.settings.roadCaptions.isEmpty())
        
        // Restoring videoPath back to video1.mp4 should load and restore settings
        viewModel.videoPath = "/path/to/my_video1.mp4"
        
        assertEquals(15.5, viewModel.trimStartSeconds)
        assertEquals(85.2, viewModel.trimEndSeconds)
        assertEquals(1, viewModel.settings.roadCaptions.size)
        assertEquals("Saved History Route", viewModel.settings.roadCaptions[0].text)
        
        cleanHistory()
    }

    @Test
    fun testFirstVideoPathSelectionClearsStaleRoadCaptionsWhenNoHistoryExists() {
        val cleanHistory = {
            val historyDir = java.io.File(System.getProperty("user.home"), ".fittrimmer_history")
            if (historyDir.exists()) {
                historyDir.deleteRecursively()
            }
        }
        cleanHistory()

        val initialCache = utils.GuiPathCache(
            fitPath = "/path/to/activity.fit",
            videoPath = "",
            videoStartUtc = "",
            settings = fit.HudSettings(
                roadCaptions = listOf(
                    fit.RoadCaptionSegment("stale-id", 10.0, 20.0, "Stale Route", true)
                )
            ),
            trimStartSeconds = 12.0,
            trimEndSeconds = 90.0,
            splitPoints = listOf(30.0)
        )
        val viewModel = AppViewModel(initialCache)

        viewModel.videoPath = "/path/to/new_video.mp4"

        assertEquals(0.0, viewModel.trimStartSeconds)
        assertEquals(0.0, viewModel.trimEndSeconds)
        assertTrue(viewModel.splitPoints.isEmpty())
        assertTrue(viewModel.settings.roadCaptions.isEmpty())

        cleanHistory()
    }

    @Test
    fun testFirstVideoPathSelectionRestoresRoadCaptionsFromHistory() {
        val cleanHistory = {
            val historyDir = java.io.File(System.getProperty("user.home"), ".fittrimmer_history")
            if (historyDir.exists()) {
                historyDir.deleteRecursively()
            }
        }
        cleanHistory()

        val returningVideoPath = "/path/to/returning_video.mp4"
        utils.GuiCache.saveHistory(
            returningVideoPath,
            utils.GuiPathCache(
                fitPath = "/path/to/activity.fit",
                videoPath = returningVideoPath,
                videoStartUtc = "2026-06-29T10:00:00Z",
                settings = fit.HudSettings(
                    roadCaptions = listOf(
                        fit.RoadCaptionSegment("saved-id", 15.0, 25.0, "Saved Local Route", true)
                    )
                ),
                trimStartSeconds = 15.0,
                trimEndSeconds = 80.0,
                splitPoints = listOf(40.0)
            )
        )

        val viewModel = AppViewModel(
            utils.GuiPathCache(
                fitPath = "/path/to/activity.fit",
                videoPath = "",
                videoStartUtc = "",
                settings = fit.HudSettings(
                    roadCaptions = listOf(
                        fit.RoadCaptionSegment("stale-id", 10.0, 20.0, "Stale Route", true)
                    )
                )
            )
        )

        viewModel.videoPath = returningVideoPath

        assertEquals(15.0, viewModel.trimStartSeconds)
        assertEquals(80.0, viewModel.trimEndSeconds)
        assertEquals(listOf(40.0), viewModel.splitPoints)
        assertEquals(1, viewModel.settings.roadCaptions.size)
        assertEquals("Saved Local Route", viewModel.settings.roadCaptions[0].text)

        cleanHistory()
    }

    @Test
    fun testAppViewModelPreservesInitialCacheOnStart() {
        val initialSettings = fit.HudSettings(
            valSize = 75f,
            roadCaptions = listOf(
                fit.RoadCaptionSegment("id-init", 5.0, 15.0, "Initial Route", true)
            )
        )
        val initialCache = utils.GuiPathCache(
            fitPath = "/path/to/init.fit",
            videoPath = "/path/to/init.mp4",
            videoStartUtc = "2026-06-29T10:20:40Z",
            timeOffsetMillis = 4500,
            settings = initialSettings,
            trimStartSeconds = 25.0,
            trimEndSeconds = 95.0,
            splitPoints = listOf(40.0, 70.0)
        )
        
        val viewModel = AppViewModel(initialCache)
        
        // At launch, values must be populated from initialCache
        assertEquals("/path/to/init.mp4", viewModel.videoPath)
        assertEquals("/path/to/init.fit", viewModel.fitPath)
        assertEquals(25.0, viewModel.trimStartSeconds)
        assertEquals(95.0, viewModel.trimEndSeconds)
        assertEquals(4500, viewModel.timeOffsetState.millis)
        assertEquals(1, viewModel.settings.roadCaptions.size)
        assertEquals("Initial Route", viewModel.settings.roadCaptions[0].text)
        
        // Re-assigning the same path (or starting UI initialization bindings)
        // should NOT clear the memory-restored cache values.
        viewModel.videoPath = "/path/to/init.mp4"
        
        assertEquals(25.0, viewModel.trimStartSeconds)
        assertEquals(95.0, viewModel.trimEndSeconds)
        assertEquals(4500, viewModel.timeOffsetState.millis)
        assertEquals(1, viewModel.settings.roadCaptions.size)
        assertEquals("Initial Route", viewModel.settings.roadCaptions[0].text)
    }

    /**
     * videoStartUtc はキャッシュを初期値として使う（起動直後の HUD 表示のため）。
     * ただし cache-save の LaunchedEffect のキーには含めない（"" 一時期間での保存汚染防止）。
     * timeOffsetMillis は常に正しく復元される。
     */
    @Test
    fun testVideoStartUtcRestoredFromCacheAsInitialValue() {
        val initialCache = utils.GuiPathCache(
            fitPath = "/path/to/ride.fit",
            videoPath = "/path/to/ride.mp4",
            videoStartUtc = "2026-06-29T10:20:40Z",
            timeOffsetMillis = -7528,
            settings = fit.HudSettings(),
            trimStartSeconds = 0.0,
            trimEndSeconds = 1198.0
        )
        val viewModel = AppViewModel(initialCache)

        // timeOffsetMillis は復元される
        assertEquals(-7528, viewModel.timeOffsetState.millis)

        // videoStartUtc はキャッシュから初期値として復元される（HUD の即時表示のため）
        assertEquals("2026-06-29T10:20:40Z", viewModel.videoStartUtc)
    }

    @Test
    fun testHudSettingsDefaultPowerTrendSpanIs60Seconds() {
        val settings = HudSettings()
        assertEquals(60, settings.powerTrendSpanSeconds, "Default power trend span should be 60 seconds (1 minute)")
        
        val config = fit.HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f,
            xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f
        )
        assertEquals(60, config.powerTrendSpanSeconds, "Default HudConfig power trend span should be 60 seconds (1 minute)")
    }

    @Test
    fun testHudSettingsDefaultLanguageIsEmpty() {
        val settings = HudSettings()
        assertEquals("", settings.language, "Default language should be empty string (meaning unset)")
    }

    @Test
    fun testHudSettingsDefaultUseImperialUnitsIsFalse() {
        val settings = HudSettings()
        assertEquals(false, settings.useImperialUnits, "Default useImperialUnits should be false")
        
        val config = fit.HudConfig(
            valSize = 50f, tightness = 0f, spacing = 10f,
            xOffset = 0f, yOffset = 0f, graphH = 100f, graphW = 200f
        )
        assertEquals(false, config.useImperialUnits, "Default HudConfig useImperialUnits should be false")
    }

    @Test
    fun testHudSettingsDefaultRiskSettings() {
        val settings = HudSettings()
        assertEquals(emptyList<fit.RiskTimeSpan>(), settings.riskTimeSpans, "Default riskTimeSpans should be empty list")
        assertEquals(false, settings.detectPedestrians, "Default detectPedestrians should be false")
    }

    @Test
    fun testHudSettingsDefaultEnableRoadDetectionIsTrue() {
        val settings = HudSettings()
        assertTrue(settings.enableRoadDetection, "Default enableRoadDetection should be true")
    }

    @Test
    fun testLocalizerWithFallback() {
        val valueEn = utils.Localizer.get("app_title", java.util.Locale.ENGLISH)
        assertEquals("FIT Telemetry Trimmer", valueEn)
        
        val valueJa = utils.Localizer.get("app_title", java.util.Locale.JAPANESE)
        assertEquals("FIT テレメトリ トリマー", valueJa)
        
        val unitLabelEn = utils.Localizer.get("use_imperial_units", "en")
        assertEquals("Use Imperial Units (mph, ft)", unitLabelEn)
        
        val unitLabelJa = utils.Localizer.get("use_imperial_units", "ja")
        assertEquals("マイル・フィート表示 (Imperial Units)", unitLabelJa)
    }

    @Test
    fun testInitializationWithCacheRestoresPlateCache() {
        val videoFile = File(System.getProperty("java.io.tmpdir"), "fittrimmer-restore-init-test.mp4")
        videoFile.writeText("placeholder")
        val cacheFile = PlateCacheManager.getPlatesFile(videoFile.absolutePath)
        cacheFile?.delete()

        val expectedCache = VideoPlatesCache(
            videoPath = videoFile.absolutePath,
            records = listOf(
                PlateRecord(1000L, listOf(PlateBox(1, 2, 30, 12))),
                PlateRecord(2000L, listOf(PlateBox(4, 5, 40, 18)))
            )
        )
        PlateCacheManager.saveCache(videoFile.absolutePath, expectedCache)

        val cache = GuiPathCache(
            fitPath = "/path/to/fit",
            videoPath = videoFile.absolutePath,
            videoStartUtc = "2026-06-29T10:00:00Z",
            settings = HudSettings(blurLicensePlates = true)
        )
        val viewModel = AppViewModel(cache)
        
        assertNotNull(viewModel.plateCache, "plateCache must be restored immediately on startup when settings.blurLicensePlates is true")
        assertEquals(2, viewModel.plateRecordCount)
        
        PlateCacheManager.deleteCache(videoFile.absolutePath)
        videoFile.delete()
    }

    @Test
    fun testCacheExistsWithDifferentPathFormatting() {
        val videoPath1 = "C:\\\\Users\\\\yuuji\\\\Test.mp4"
        val videoPath2 = "c:/Users/yuuji/Test.mp4"
        
        val cache = VideoPlatesCache(
            videoPath = videoPath1,
            records = listOf(PlateRecord(1000L, emptyList()))
        )
        
        PlateCacheManager.saveCache(videoPath1, cache)
        
        try {
            assertTrue(PlateCacheManager.cacheExists(videoPath2), "Cache must be detected even if slash directions or drive letter casing differ")
            val restored = PlateCacheManager.loadCache(videoPath2)
            assertNotNull(restored)
        } finally {
            PlateCacheManager.deleteCache(videoPath1)
        }
    }

    @Test
    fun testAvailableCacheJobsRefreshOnVideoPathChange() {
        val mockVideoPath = File(System.getProperty("java.io.tmpdir"), "video_mock_vm.mp4").absolutePath
        val workDir = fit.PathResolver.getTempWorkDir(mockVideoPath)
        val jobDir = File(workDir, "job_88888")
        jobDir.mkdirs()
        val part = File(jobDir, "part_0000.ts")
        part.writeText("data")

        try {
            val viewModel = AppViewModel(null)
            assertEquals(0, viewModel.availableCacheJobs.size, "Initially should be empty")

            viewModel.videoPath = mockVideoPath
            val targetJob = viewModel.availableCacheJobs.find { it.jobHash == "88888" }
            assertNotNull(targetJob, "ViewModel must auto-scan and detect the unfinished cache job")
            assertEquals(1, targetJob.partsCount)
        } finally {
            part.delete()
            jobDir.delete()
        }
    }

    @Test
    fun testParseStartUtcFromFileName() {
        val fileName1 = "VID_20260702_163959_001.mp4"
        val parsed1 = utils.tryParseStartUtcFromFileName(fileName1)
        assertNotNull(parsed1)
        assertTrue(parsed1!!.endsWith("Z"))
        assertTrue(parsed1.contains("2026-07-02"))

        val fileName2 = "20260630_154458.mov"
        val parsed2 = utils.tryParseStartUtcFromFileName(fileName2)
        assertNotNull(parsed2)
        assertTrue(parsed2!!.endsWith("Z"))
        assertTrue(parsed2.contains("2026-06-30"))

        val fileNameInvalid = "GH010234.MP4"
        val parsedInvalid = utils.tryParseStartUtcFromFileName(fileNameInvalid)
        assertNull(parsedInvalid)
    }

    @Test
    fun testBatchRestoreDialogScenarios() {
        kotlinx.coroutines.runBlocking {
        val dummyJobs = listOf(
            utils.SerializedBatchJob(
                id = "job-1",
                videoPath = "video1.mp4",
                fitPath = "fit1.fit",
                videoStartUtc = "2026-07-02T10:00:00Z",
                timeOffsetMillis = 0L,
                trimStartSeconds = 10.0,
                trimEndSeconds = 20.0,
                splitPoints = emptyList(),
                settings = HudSettings(),
                autoDetectRoadCaptionsOnEncode = false,
                outputFileNames = listOf("video1.mp4"),
                status = "COMPLETED", // Should be ignored during restore
                progress = 1.0f,
                errorMessage = null
            ),
            utils.SerializedBatchJob(
                id = "job-2",
                videoPath = "video2.mp4",
                fitPath = "fit1.fit",
                videoStartUtc = "2026-07-02T10:00:00Z",
                timeOffsetMillis = 0L,
                trimStartSeconds = 30.0,
                trimEndSeconds = 50.0,
                splitPoints = emptyList(),
                settings = HudSettings(),
                autoDetectRoadCaptionsOnEncode = false,
                outputFileNames = listOf("video2.mp4"),
                status = "WAITING", // Unfinished, should be restored
                progress = 0f,
                errorMessage = null
            )
        )
        utils.BatchQueueCache.save(dummyJobs)

        val viewModel = AppViewModel(null)

        // Verify restoration dialog is shown, and only the WAITING job is in pending list
        assertTrue(viewModel.showBatchRestoreDialog, "Dialog must be shown when unfinished jobs exist")
        assertEquals(1, viewModel.pendingRestorableJobs.size)
        assertEquals("job-2", viewModel.pendingRestorableJobs[0].id)
        assertTrue(viewModel.batchQueue.isEmpty(), "Batch queue must remain empty until explicitly restored")

        // 1. Confirm restore (no cache scenario)
        viewModel.restorePendingBatchJobs(this)

        // Wait for asynchronous queue restoration
        var restored = false
        for (i in 1..50) {
            if (viewModel.batchQueue.isNotEmpty()) {
                restored = true
                break
            }
            kotlinx.coroutines.delay(50)
        }

        assertTrue(restored, "Job should be restored to queue asynchronously")
        assertFalse(viewModel.showBatchRestoreDialog)
        assertEquals(1, viewModel.batchQueue.size)
        assertEquals("job-2", viewModel.batchQueue[0].id)
        assertTrue(viewModel.pendingRestorableJobs.isEmpty())

        // 2. Clear queue and verify discard
        viewModel.clearBatchQueue()
        utils.BatchQueueCache.save(dummyJobs) // Reload dummy jobs to disk cache
        val viewModel2 = AppViewModel(null)
        assertTrue(viewModel2.showBatchRestoreDialog)

        viewModel2.discardPendingBatchJobs()
        assertFalse(viewModel2.showBatchRestoreDialog)
        assertTrue(viewModel2.batchQueue.isEmpty())
        assertTrue(viewModel2.pendingRestorableJobs.isEmpty())
        assertTrue(utils.BatchQueueCache.load().isEmpty(), "Disk cache must be cleared on discard")

        // 3. Test restore with available cache (auto-salvage scenario)
        viewModel.clearBatchQueue()

        val tempDir = kotlin.io.path.createTempDirectory("fittrimmer-test-salvage-").toFile()
        val mockVideoFile = File(tempDir, "video_mock.mp4")
        mockVideoFile.createNewFile()

        val dummyJobsWithCache = listOf(
            utils.SerializedBatchJob(
                id = "job-3",
                videoPath = mockVideoFile.absolutePath,
                fitPath = "fit1.fit",
                videoStartUtc = "2026-07-02T10:00:00Z",
                timeOffsetMillis = 0L,
                trimStartSeconds = 30.0,
                trimEndSeconds = 50.0,
                splitPoints = emptyList(),
                settings = HudSettings(),
                autoDetectRoadCaptionsOnEncode = false,
                outputFileNames = listOf("video_mock_salvaged.mp4"),
                status = "WAITING",
                progress = 0f,
                errorMessage = null
            )
        )
        utils.BatchQueueCache.save(dummyJobsWithCache)

        val viewModel3 = AppViewModel(null)
        viewModel3.outputDir = tempDir.absolutePath
        assertTrue(viewModel3.showBatchRestoreDialog)
        assertEquals(1, viewModel3.pendingRestorableJobs.size)

        // Create cache job structures
        val workDir = fit.PathResolver.getTempWorkDir(mockVideoFile.absolutePath)
        val jobDir = File(workDir, "job_77777")
        jobDir.mkdirs()
        val part1 = File(jobDir, "part_0000.ts")
        createMockTsFile(part1)
        File(jobDir, ".video_source").writeText(mockVideoFile.absolutePath)

        // Restore -> Should trigger auto-salvage
        viewModel3.restorePendingBatchJobs(this)

        val expectedOutputFile = fit.CacheJobManager.getInstance().getSalvageOutputPath(
            videoPath = mockVideoFile.absolutePath,
            outputDir = tempDir.absolutePath,
            settings = viewModel3.settings
        )

        // Poll for completion (as salvage is asynchronous)
        var completed = false
        for (i in 1..100) {
            if (!viewModel3.isSalvaging && expectedOutputFile.exists()) {
                completed = true
                break
            }
            kotlinx.coroutines.delay(100)
        }

        assertTrue(completed, "Auto-salvage should complete and produce salvaged video file")
        assertTrue(viewModel3.batchQueue.isEmpty(), "Job should be completed and NOT added to batch queue")
        assertFalse(jobDir.exists(), "Job directory should be cleaned up after successful salvage")

        // Cleanup
        tempDir.deleteRecursively()
        workDir.deleteRecursively()
        }
    }

    private fun createMockTsFile(dest: File) {
        val ffmpegPath = try { fit.findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val pb = ProcessBuilder(
            ffmpegPath, "-y",
            "-nostdin",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10:duration=0.2",
            "-c:v", "libopenh264",
            "-f", "mpegts",
            dest.absolutePath
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.waitFor()
    }


    @Test
    fun testWindowsVideoPlayerStateDisposeDoesNotDeadlock() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) {
            return
        }
        val playerState = io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState()
        playerState.openUri("C:/Users/yuuji/fit-trimmer/composeApp/scratch/crop_test.mp4")
        Thread.sleep(100)
        playerState.play()
        playerState.seekTo(100f)
        playerState.pause()

        val startTime = System.currentTimeMillis()
        playerState.dispose()
        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 2500, "dispose() took too long ($duration ms), potential deadlock detected")
    }

    @Test
    fun testCrashSimulationFlow() {
        kotlinx.coroutines.runBlocking {
            val mockVideoPath = utils.CrashSimulator.setupSimulation()
            assertTrue(mockVideoPath.isNotEmpty(), "Simulated video path should not be empty")
            val mockVideoFile = File(mockVideoPath)
            assertTrue(mockVideoFile.exists(), "Simulated mock video file should exist")
            
            val viewModel = AppViewModel(null)
            viewModel.outputDir = mockVideoFile.parentFile.absolutePath
            
            assertTrue(viewModel.showBatchRestoreDialog, "Restore dialog should be visible after crash simulation")
            assertEquals(1, viewModel.pendingRestorableJobs.size, "Should have 1 restorable job")
            assertEquals("simulated-crash-job", viewModel.pendingRestorableJobs[0].id)
            
            viewModel.restorePendingBatchJobs(this)
            
            val expectedOutputFile = fit.CacheJobManager.getInstance().getSalvageOutputPath(
                videoPath = mockVideoFile.absolutePath,
                outputDir = mockVideoFile.parentFile.absolutePath,
                settings = viewModel.settings
            )
            
            var completed = false
            for (i in 1..100) {
                if (!viewModel.isSalvaging && expectedOutputFile.exists()) {
                    completed = true
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            
            assertTrue(completed, "Simulated crash restore should complete and merge segments successfully")
            assertTrue(viewModel.batchQueue.isEmpty(), "Batch queue should be cleared because it salvaged directly")
            
            mockVideoFile.parentFile.deleteRecursively()
            fit.CacheJobManager.getInstance().clearAll(mockVideoFile.absolutePath)
        }
    }

    @Test
    fun testBatchJobPhasesAutoInitialization() {
        val viewModel = AppViewModel(null)
        viewModel.videoPath = "/path/to/video1.mp4"
        viewModel.fitPath = "/path/to/fit1.fit"
        
        // Scenario 1: Blur License Plates is true
        viewModel.settings = viewModel.settings.copy(blurLicensePlates = true)
        viewModel.addToBatchQueue()
        val jobWithBlur = viewModel.batchQueue.last()
        
        val plateScanPhase = jobWithBlur.phases.find { it.type == BatchJobPhaseType.PLATE_SCAN }
        val hudEncodePhase = jobWithBlur.phases.find { it.type == BatchJobPhaseType.HUD_ENCODE }
        val concatMergePhase = jobWithBlur.phases.find { it.type == BatchJobPhaseType.CONCAT_MERGE }
        
        assertNotNull(plateScanPhase)
        assertTrue(plateScanPhase.enabled, "Plate scan should be enabled when blurLicensePlates is true")
        assertNotNull(hudEncodePhase)
        assertTrue(hudEncodePhase.enabled)
        assertNotNull(concatMergePhase)
        assertTrue(concatMergePhase.enabled)
        
        // Scenario 2: Blur License Plates is false
        viewModel.clearBatchQueue()
        viewModel.settings = viewModel.settings.copy(blurLicensePlates = false)
        viewModel.addToBatchQueue()
        val jobWithoutBlur = viewModel.batchQueue.last()
        
        val plateScanPhase2 = jobWithoutBlur.phases.find { it.type == BatchJobPhaseType.PLATE_SCAN }
        assertNotNull(plateScanPhase2)
        assertFalse(plateScanPhase2.enabled, "Plate scan should be disabled when blurLicensePlates is false")

        // Scenario 3: FIT path is empty (Fast Trim mode)
        viewModel.clearBatchQueue()
        viewModel.fitPath = ""
        viewModel.addToBatchQueue()
        val jobFastTrim = viewModel.batchQueue.last()
        
        val fastTrimPhase = jobFastTrim.phases.find { it.type == BatchJobPhaseType.FAST_TRIM }
        val plateScanPhase3 = jobFastTrim.phases.find { it.type == BatchJobPhaseType.PLATE_SCAN }
        val hudEncodePhase3 = jobFastTrim.phases.find { it.type == BatchJobPhaseType.HUD_ENCODE }
        val concatMergePhase3 = jobFastTrim.phases.find { it.type == BatchJobPhaseType.CONCAT_MERGE }
        
        assertNotNull(fastTrimPhase, "Fast Trim phase should be created when FIT path is empty")
        assertTrue(fastTrimPhase.enabled, "Fast Trim should be enabled when FIT path is empty")
        assertTrue(plateScanPhase3 == null || !plateScanPhase3.enabled, "Plate scan should be disabled/absent in Fast Trim mode")
        assertTrue(hudEncodePhase3 == null || !hudEncodePhase3.enabled, "HUD encode should be disabled/absent in Fast Trim mode")
        assertTrue(concatMergePhase3 == null || !concatMergePhase3.enabled, "Concat Merge should be disabled/absent in Fast Trim mode")
    }
}


