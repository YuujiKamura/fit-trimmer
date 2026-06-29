package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoadNameBuilderTest {

    @Test
    fun testBuildCaptionTextPrefecturalRoadWithNumber() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "都道府県道",
            roadName = "瀬田竜田線",
            ref = "145",
            city = null,
            town = "菊陽町",
            village = null,
            suburb = null
        )
        assertEquals("県道145号 瀬田竜田線（菊陽町 付近）", result)
    }

    @Test
    fun testBuildCaptionTextPrefecturalRoadWithoutNumber() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "都道府県道",
            roadName = "熊本菊陽線",
            ref = null,
            city = "熊本市",
            town = null,
            village = null,
            suburb = null
        )
        assertEquals("県道 熊本菊陽線（熊本市 付近）", result)
    }

    @Test
    fun testBuildCaptionTextMunicipalCityRoad() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "市区町村道等",
            roadName = null,
            ref = null,
            city = "熊本市",
            town = null,
            village = null,
            suburb = null
        )
        assertEquals("市道（熊本市 付近）", result)
    }

    @Test
    fun testBuildCaptionTextMunicipalTownRoad() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "市区町村道等",
            roadName = null,
            ref = null,
            city = null,
            town = "菊陽町",
            village = null,
            suburb = null
        )
        assertEquals("町道（菊陽町 付近）", result)
    }

    @Test
    fun testBuildCaptionTextMunicipalVillageRoad() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "市区町村道等",
            roadName = null,
            ref = null,
            city = null,
            town = null,
            village = "西原村",
            suburb = null
        )
        assertEquals("村道（西原村 付近）", result)
    }

    @Test
    fun testBuildCaptionTextNationalHighway() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "一般国道",
            roadName = null,
            ref = "57",
            city = null,
            town = "大津町",
            village = null,
            suburb = null
        )
        assertEquals("国道57号（大津町 付近）", result)
    }

    @Test
    fun testBuildCaptionTextEmpty() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = null,
            roadName = null,
            ref = null,
            city = null,
            town = null,
            village = null,
            suburb = null
        )
        assertNull(result)
    }

    @Test
    fun testBuildCaptionTextGsiMunicipalBlockOsmSnapping() {
        // 4m 30s case: GSI says "市区町村道等" (no name), but OSM snaps to nearby "熊本菊陽線" (prefectural road)
        // Main.kt rejects OSM road name when GSI is municipal and nameless
        val finalRoadName = null // Rejected by Main.kt logic
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "市区町村道等",
            roadName = finalRoadName,
            ref = null,
            city = "熊本市",
            town = null,
            village = null,
            suburb = null
        )
        // Output should be a clean municipal road with area suffix
        assertEquals("市道（熊本市 付近）", result)
    }

    @Test
    fun testBuildCaptionTextGsiPrefecturalWithOsmName() {
        // 0s case: GSI says "都道府県道", OSM has "瀬田竜田線", ref "145", town "菊陽町"
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "都道府県道",
            roadName = "瀬田竜田線",
            ref = "145",
            city = null,
            town = "菊陽町",
            village = null,
            suburb = null
        )
        assertEquals("県道145号 瀬田竜田線（菊陽町 付近）", result)
    }

    @Test
    fun testBuildCaptionTextDetailedArea() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "一般国道",
            roadName = null,
            ref = "57",
            city = "熊本市",
            town = null,
            village = null,
            suburb = "北区",
            county = null,
            neighbourhood = "弓削"
        )
        assertEquals("国道57号（熊本市北区弓削 付近）", result)
    }

    @Test
    fun testBuildCaptionTextDetailedAreaCounty() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "都道府県道",
            roadName = "瀬田竜田線",
            ref = "145",
            city = null,
            town = "菊陽町",
            village = null,
            suburb = null,
            county = "菊池郡",
            neighbourhood = "津久礼"
        )
        assertEquals("県道145号 瀬田竜田線（菊池郡菊陽町津久礼 付近）", result)
    }

    @Test
    fun testBuildCaptionTextCityWithCountyShouldIgnoreCounty() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = "一般国道",
            roadName = null,
            ref = "57",
            city = "熊本市",
            town = null,
            village = null,
            suburb = "北区",
            county = "菊池郡",
            neighbourhood = "弓削"
        )
        assertEquals("国道57号（熊本市北区弓削 付近）", result)
    }

    @Test
    fun testBuildCaptionTextUsRoad() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = null,
            roadName = "El Camino Real",
            ref = "US 101",
            city = "San Jose",
            town = null,
            village = null,
            suburb = null,
            county = null,
            neighbourhood = null,
            countryCode = "us",
            state = "California"
        )
        assertEquals("US 101 El Camino Real (San Jose, California)", result)
    }

    @Test
    fun testBuildCaptionTextFallbackRoad() {
        val result = RoadNameBuilder.buildCaptionText(
            rdCtg = null,
            roadName = "Champs-Élysées",
            ref = "N12",
            city = "Paris",
            town = null,
            village = null,
            suburb = null,
            county = null,
            neighbourhood = null,
            countryCode = "fr",
            state = "Île-de-France"
        )
        assertEquals("Champs-Élysées (Paris, Île-de-France)", result)
    }
}
