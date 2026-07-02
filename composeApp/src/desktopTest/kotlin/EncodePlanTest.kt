import fit.HudSettings
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
    fun testBuildEncodeOutputFileNameUsesSampleSuffix() {
        val settings = HudSettings(exportResolution = "2.7k")

        val fileName = buildEncodeOutputFileName(
            settings = settings,
            videoPath = "C:/videos/ride.mov",
            isSample = true
        )

        assertEquals("ride_TEST_HUD.mp4", fileName)
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
}
