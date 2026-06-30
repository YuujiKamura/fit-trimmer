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
}
