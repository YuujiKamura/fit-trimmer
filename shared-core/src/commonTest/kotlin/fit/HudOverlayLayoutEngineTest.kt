package fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudOverlayLayoutEngineTest {

    @Test
    fun testHudOverlayLayoutCalculation() {
        val engine = HudOverlayLayoutEngine()

        // Setup mock configurations
        val config = HudConfig(
            valSize = 40f,
            tightness = 2f,
            spacing = 20f,
            xOffset = 50f,
            yOffset = 100f,
            graphH = 60f,
            graphW = 300f,
            showSpeed = true,
            showCadence = true,
            showHeartRate = true,
            showPower = true,
            showWkg = true,
            bodyWeightKg = 70.0,
            useImperialUnits = false
        )

        val point = TelemetryPoint(
            timestamp = 1782278400.0,
            speed = 10.0, // 10 m/s = 36.0 km/h
            power = 280.0, // 280W / 70kg = 4.0 W/kg
            cadence = 90.0,
            heartRate = 165.0, // Zone 3 (160-169)
            elevation = 100.0,
            grade = 5.0,
            lat = 32.8,
            lon = 130.8,
            distance = 5000.0,
            elapsedSeconds = 600,
            temperature = 22.0
        )

        // Mock text width measurer (length * size * 0.6)
        val textWidthMeasurer: (String, Float, Boolean) -> Float = { text, size, _ ->
            text.length * (size * 0.6f)
        }

        val layout = engine.calculateLayout(
            config = config,
            point = point,
            isValid = true,
            sf = 1.0f,
            zonesCurrent = IntArray(7) { 10 },
            cachedZonesTotal = IntArray(7) { 100 },
            getTextWidth = textWidthMeasurer,
            formatDateTime = { "18:40:00" },
            formatOneDecimal = { "%.1f".format(java.util.Locale.US, it) },
            formatGrade = { "%.1f".format(java.util.Locale.US, it) },
            getLabel = { it }
        )

        assertTrue(layout.isReady)

        // Verify Speed Meter values and units
        assertNotNull(layout.speed)
        assertEquals("10.0", layout.speed.valueText)
        assertEquals("km/h", layout.speed.unitText)
        assertEquals(50f, layout.speed.x) // Should start at xOffset (50f)
        assertEquals(140f, layout.speed.y) // Date display height (20f) + spacing (20f)

        // Verify Heart Rate Zone Color mappings (Zone 3)
        assertNotNull(layout.heartRate)
        assertEquals("#22c55e", layout.heartRate.zoneColor) // Zone 3 should map to Green (#22c55e)

        // Verify W/kg (4.0 W/kg)
        assertNotNull(layout.wkg)
        assertEquals("4.0", layout.wkg.valueText)
        assertEquals(569f, layout.finalCy)
    }

    @Test
    fun testImperialUnitConversion() {
        val engine = HudOverlayLayoutEngine()
        val config = HudConfig(
            valSize = 40f,
            tightness = 2f,
            spacing = 20f,
            xOffset = 50f,
            yOffset = 100f,
            graphH = 60f,
            graphW = 300f,
            showSpeed = true,
            useImperialUnits = true // Imperial
        )

        val point = TelemetryPoint(
            timestamp = 1782278400.0,
            speed = 10.0, // 10 m/s * 2.23694 = 22.3694 mph
            power = 0.0,
            cadence = 0.0,
            heartRate = 0.0,
            elevation = 0.0,
            grade = 0.0,
            lat = 0.0,
            lon = 0.0,
            distance = 0.0,
            elapsedSeconds = 0,
            temperature = 22.0
        )

        val textWidthMeasurer: (String, Float, Boolean) -> Float = { text, size, _ ->
            text.length * (size * 0.6f)
        }

        val layout = engine.calculateLayout(
            config = config,
            point = point,
            isValid = true,
            sf = 1.0f,
            zonesCurrent = IntArray(7),
            cachedZonesTotal = null,
            getTextWidth = textWidthMeasurer,
            formatDateTime = { "" },
            formatOneDecimal = { "%.1f".format(java.util.Locale.US, it) },
            formatGrade = { "%.1f".format(java.util.Locale.US, it) },
            getLabel = { it }
        )

        assertNotNull(layout.speed)
        assertEquals("6.2", layout.speed.valueText) // 10 km/h = 6.2 mph
        assertEquals("mph", layout.speed.unitText)
    }
}
