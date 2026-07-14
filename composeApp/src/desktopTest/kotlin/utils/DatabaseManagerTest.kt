package utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import java.io.File
import org.junit.Before
import org.junit.After

class DatabaseManagerTest {
    private val testDbFile = File("temp_work/test_rider_profile.db")

    @Before
    fun setUp() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        testDbFile.parentFile?.mkdirs()
        DatabaseManager.dbFilePath = testDbFile.absolutePath
        DatabaseManager.setupDatabase()
    }

    @After
    fun tearDown() {
        DatabaseManager.dbFilePath = null
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testSaveLoadAndDeleteActivityWithRiderProfile() {
        val dummyTelemetry = listOf(
            DbTelemetryPoint(
                timestamp = 1000.0,
                speed = 25.5,
                power = 220.0,
                cadence = 90.0,
                heartRate = 145.0,
                elevation = 120.0,
                grade = 4.5,
                lat = 35.6586,
                lon = 139.7454,
                distance = 10.0,
                elapsedSeconds = 1,
                temperature = 22.0
            )
        )

        val dummySegments = listOf(
            DbSegment(
                name = "Yabitsu Pass (ヤビツ峠)",
                distanceMeters = 5000.0,
                durationSeconds = 1200.0,
                averageGrade = 6.0,
                startIndex = 0,
                endIndex = 0,
                startLat = 35.45,
                startLon = 139.22,
                endLat = 35.48,
                endLon = 139.24,
                startElev = 100.0,
                endElev = 400.0,
                minGrade = 0.0,
                maxGrade = 10.0,
                startFitTimestamp = 1000.0,
                endFitTimestamp = 2200.0
            )
        )

        val dummyActivity = DbActivity(
            id = "test_activity_123",
            name = "Morning Ride with Climbing",
            startDate = "2026-07-14T06:00:00Z",
            startDateLocal = "2026-07-14T15:00:00",
            distance = 15000.0,
            movingTime = 3600,
            elapsedTime = 3800,
            tss = 75.2,
            sufferScore = 60,
            avgPower = 200.0,
            normalizedPower = 215.0,
            avgHr = 140.0,
            fitPath = "/path/to/test.fit",
            segments = dummySegments,
            telemetry = dummyTelemetry,
            ctl = 65.4,
            atl = 85.1,
            tsb = -19.7
        )

        // 1. Save activity to SQLite
        DatabaseManager.saveActivity(dummyActivity)

        // 2. Load all activities and assert values
        val activities = DatabaseManager.getAllActivities()
        assertEquals(1, activities.size, "Database should contain exactly 1 activity")
        
        val loaded = activities.first()
        assertEquals(dummyActivity.id, loaded.id)
        assertEquals(dummyActivity.name, loaded.name)
        assertEquals(dummyActivity.startDate, loaded.startDate)
        assertEquals(dummyActivity.startDateLocal, loaded.startDateLocal)
        assertEquals(dummyActivity.distance, loaded.distance)
        assertEquals(dummyActivity.movingTime, loaded.movingTime)
        assertEquals(dummyActivity.tss, loaded.tss)
        assertEquals(dummyActivity.sufferScore, loaded.sufferScore)
        assertEquals(dummyActivity.avgPower, loaded.avgPower)
        assertEquals(dummyActivity.normalizedPower, loaded.normalizedPower)
        assertEquals(dummyActivity.avgHr, loaded.avgHr)
        assertEquals(dummyActivity.fitPath, loaded.fitPath)
        
        // Assert Rider Profile values
        assertEquals(65.4, loaded.ctl, "CTL value must match")
        assertEquals(85.1, loaded.atl, "ATL value must match")
        assertEquals(-19.7, loaded.tsb, "TSB value must match")

        // Assert segments mapping
        assertEquals(1, loaded.segments.size, "Segments count must match")
        val seg = loaded.segments.first()
        assertEquals("Yabitsu Pass (ヤビツ峠)", seg.name)
        assertEquals(5000.0, seg.distanceMeters)
        assertEquals(1200.0, seg.durationSeconds)
        assertEquals(6.0, seg.averageGrade)

        // Assert telemetry mapping
        assertEquals(1, loaded.telemetry.size, "Telemetry count must match")
        val tele = loaded.telemetry.first()
        assertEquals(1000.0, tele.timestamp)
        assertEquals(220.0, tele.power)
        assertEquals(35.6586, tele.lat)

        // 3. Delete activity
        DatabaseManager.deleteActivity(dummyActivity.id)
        
        val afterDelete = DatabaseManager.getAllActivities()
        assertTrue(afterDelete.isEmpty(), "Database must be empty after deleting the activity")
    }
}
