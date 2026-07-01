package fit

import java.io.File
import kotlin.test.*

class JobStateManagerTest {

    private lateinit var testTempDir: File

    @BeforeTest
    fun setUp() {
        testTempDir = File("build/tmp/test_job_state_${System.currentTimeMillis()}")
        testTempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        testTempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndLoadState() {
        val jobHash = "test_hash_456"
        val state = JobState(
            jobHash = jobHash,
            isPlateMaskStreamReady = true,
            isRoadTelemetryReady = false
        )

        JobStateManager.saveState(testTempDir, state)

        val loaded = JobStateManager.loadState(testTempDir, jobHash)
        assertEquals(jobHash, loaded.jobHash)
        assertTrue(loaded.isPlateMaskStreamReady)
        assertFalse(loaded.isRoadTelemetryReady)
    }

    @Test
    fun testLoadDefaultStateWhenNotExists() {
        val jobHash = "non_existent_hash"
        val loaded = JobStateManager.loadState(testTempDir, jobHash)
        assertEquals(jobHash, loaded.jobHash)
        assertFalse(loaded.isPlateMaskStreamReady)
        assertFalse(loaded.isRoadTelemetryReady)
    }
}
