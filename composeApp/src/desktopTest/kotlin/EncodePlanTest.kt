import fit.HudSettings
import fit.PlateBox
import fit.PlateRecord
import fit.VideoPlatesCache
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodePlanTest {
    @Test
    fun testBuildEncodeOutputFileNameUsesResolutionAndPartSuffix() {
        val settings = HudSettings(exportResolution = "1080p")

        val fileName = buildEncodeOutputFileName(
            settings = settings,
            videoPath = "C:/videos/ride.mp4",
            partIndex = 1,
            numParts = 3
        )

        assertEquals("ride_part2_KMP_HUD_1080p.mp4", fileName)
    }

    @Test
    fun testBuildEncodeOutputFileNameUsesStravaResolution() {
        val settings = HudSettings(exportResolution = "strava")

        val fileName = buildEncodeOutputFileName(
            settings = settings,
            videoPath = "C:/videos/ride.mp4",
            partIndex = -1,
            numParts = 1
        )

        assertEquals("ride_KMP_HUD_strava.mp4", fileName)
    }

    @Test
    fun testBuildEncodePlanCreatesSegmentsAndOutputFiles() {
        val settings = HudSettings(exportResolution = "2.7k")
        val plan = buildEncodePlan(
            settings = settings,
            videoPath = "C:/videos/ride.mp4",
            outputDir = "D:/out",
            moveOutputToSource = false,
            ranges = buildEncodeRanges(
                trimStartSeconds = 10.0,
                trimEndSeconds = 40.0,
                splitPoints = listOf(20.0, 30.0)
            )
        )

        assertEquals(3, plan.segments.size)
        assertEquals(30.0, plan.totalDurationSeconds)
        assertEquals("ride_part1_KMP_HUD_2.7k.mp4", plan.segments[0].finalOutputFile.name)
        assertEquals("ride_part2_KMP_HUD_2.7k.mp4", plan.segments[1].finalOutputFile.name)
        assertEquals("ride_part3_KMP_HUD_2.7k.mp4", plan.segments[2].finalOutputFile.name)
    }

    @Test
    fun testBuildEncodePlanCanIncludeTrimRangeInOutputFiles() {
        val settings = HudSettings(exportResolution = "2.7k")
        val plan = buildEncodePlan(
            settings = settings,
            videoPath = "C:/videos/VID_20260630_174458_001.mp4",
            outputDir = "D:/out",
            moveOutputToSource = false,
            ranges = buildEncodeRanges(
                trimStartSeconds = 197.465,
                trimEndSeconds = 339.082,
                splitPoints = emptyList()
            ),
            includeTrimRangeInFileName = true,
            dateTag = "20260630"
        )

        assertEquals(1, plan.segments.size)
        assertEquals("VID_20260630_174458_001_03m17s-05m39s_KMP_HUD_2.7k.mp4", plan.segments[0].finalOutputFile.name)
    }

    @Test
    fun testBuildEncodePlanAddsDateWhenSourceNameHasNoDate() {
        val settings = HudSettings(exportResolution = "2.7k")
        val plan = buildEncodePlan(
            settings = settings,
            videoPath = "C:/videos/ride.mp4",
            outputDir = "D:/out",
            moveOutputToSource = false,
            ranges = buildEncodeRanges(
                trimStartSeconds = 197.465,
                trimEndSeconds = 339.082,
                splitPoints = emptyList()
            ),
            includeTrimRangeInFileName = true,
            dateTag = "20260630"
        )

        assertEquals("ride_20260630_03m17s-05m39s_KMP_HUD_2.7k.mp4", plan.segments[0].finalOutputFile.name)
    }

    @Test
    fun testBuildPlateCutSpansMergesBufferedPlateRecords() {
        val cache = VideoPlatesCache(
            videoPath = "ride.mp4",
            records = listOf(
                PlateRecord(10_000L, listOf(PlateBox(0, 0, 10, 10))),
                PlateRecord(10_200L, listOf(PlateBox(0, 0, 10, 10))),
                PlateRecord(20_000L, listOf(PlateBox(0, 0, 10, 10)))
            )
        )

        val cuts = buildPlateCutSpans(
            plateCache = cache,
            trimStartSeconds = 0.0,
            trimEndSeconds = 30.0,
            bufferMs = 300L
        )

        assertEquals(listOf(fit.CutSpan(9.7, 10.5), fit.CutSpan(19.7, 20.3)), cuts)
    }

    @Test
    fun testBuildEncodeRangesWithPlateCutModeSubtractsDetectedSpans() {
        val cache = VideoPlatesCache(
            videoPath = "ride.mp4",
            records = listOf(
                PlateRecord(10_000L, listOf(PlateBox(0, 0, 10, 10))),
                PlateRecord(20_000L, listOf(PlateBox(0, 0, 10, 10)))
            )
        )
        val settings = HudSettings(
            blurLicensePlates = true,
            plateMaskMode = "cut",
            plateMaskTimeBufferMs = 500L
        )

        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = 0.0,
            trimEndSeconds = 30.0,
            splitPoints = emptyList(),
            settings = settings,
            plateCache = cache
        )

        assertEquals(listOf(0.0 to 9.5, 10.5 to 19.5, 20.5 to 30.0), ranges)
    }

    @Test
    fun testBuildEncodeRangesWithPlateCutModeSkipsIfRemainingBelowThreshold() {
        val cache = VideoPlatesCache(
            videoPath = "ride.mp4",
            records = listOf(
                PlateRecord(10_000L, listOf(PlateBox(0, 0, 10, 10)))
            )
        )
        val settings = HudSettings(
            blurLicensePlates = true,
            plateMaskMode = "cut",
            plateMaskTimeBufferMs = 500L,
            minRemainingSecondsForCut = 29.5
        )

        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = 0.0,
            trimEndSeconds = 30.0,
            splitPoints = emptyList(),
            settings = settings,
            plateCache = cache
        )

        kotlin.test.assertTrue(ranges.isEmpty())
    }

    @Test
    fun testBuildEncodeRangesWithPlateMaskModeKeepsOriginalRanges() {
        val cache = VideoPlatesCache(
            videoPath = "ride.mp4",
            records = listOf(PlateRecord(10_000L, listOf(PlateBox(0, 0, 10, 10))))
        )
        val settings = HudSettings(blurLicensePlates = true, plateMaskMode = "plate")

        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = 0.0,
            trimEndSeconds = 30.0,
            splitPoints = listOf(15.0),
            settings = settings,
            plateCache = cache
        )

        assertEquals(listOf(0.0 to 15.0, 15.0 to 30.0), ranges)
    }

    @Test
    fun testBuildEncodeRangesWithStravaAutoTrim() {
        val settings = HudSettings(exportResolution = "strava")
        val ranges = buildEncodeRangesWithPlatePolicy(
            trimStartSeconds = 10.0,
            trimEndSeconds = 50.0,
            splitPoints = emptyList(),
            settings = settings,
            plateCache = null
        )

        assertEquals(listOf(10.0 to 40.0), ranges)
    }

    @Test
    fun testHasTrimmedRangeComparesAgainstVideoDuration() {
        assertEquals(false, hasTrimmedRange(0.0, 120.0, 120.0))
        assertEquals(true, hasTrimmedRange(5.0, 120.0, 120.0))
        assertEquals(true, hasTrimmedRange(0.0, 90.0, 120.0))
    }

    @Test
    fun testBuildDateTagFromUtc() {
        assertEquals("20260630", buildDateTagFromUtc("2026-06-30T08:44:58Z"))
        assertEquals(null, buildDateTagFromUtc(""))
        assertEquals(null, buildDateTagFromUtc("20260630"))
    }

    @Test
    fun testParseBatchStatusText() {
        val status1 = "[1/2] [Part 1/3] Encoding: 45% | 00:09 / 00:20 | Speed: 15.4 fps (0.5x) | ETA: 00:11"
        val parsed1 = parseBatchStatusText(status1)
        kotlin.test.assertTrue(parsed1.isParsed)
        assertEquals("Part 1/3", parsed1.partInfo)
        assertEquals("Encoding: 45%", parsed1.actionText)
        assertEquals("00:09 / 00:20", parsed1.timeInfo)
        assertEquals("15.4 fps (0.5x)", parsed1.speedInfo)
        assertEquals("ETA: 00:11", parsed1.etaInfo)

        val status2 = "プレートスキャンを実行中..."
        val parsed2 = parseBatchStatusText(status2)
        kotlin.test.assertFalse(parsed2.isParsed)
    }
}
