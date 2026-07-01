package fit

import java.io.File
import kotlin.test.*

class CacheRegistryTest {

    private lateinit var testTempDir: File

    @BeforeTest
    fun setUp() {
        testTempDir = File("build/tmp/test_cache_registry_${System.currentTimeMillis()}")
        testTempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        testTempDir.deleteRecursively()
    }

    @Test
    fun testCreateAndTrackTempFile() {
        val groupKey = "test_job_123"
        val tempFile = CacheRegistry.createTempFile(
            groupKey = groupKey,
            fileName = "test_file.tmp",
            parentDir = testTempDir
        )

        tempFile.writeText("dummy content")
        assertTrue(tempFile.exists())

        CacheRegistry.invalidateGroup(groupKey)
        assertFalse(tempFile.exists())
    }

    @Test
    fun testCleanStaleCache() {
        val groupKey = "stale_job"
        val tempFile = CacheRegistry.createTempFile(
            groupKey = groupKey,
            fileName = "stale_file.tmp",
            parentDir = testTempDir
        )
        tempFile.writeText("stale")
        assertTrue(tempFile.exists())

        CacheRegistry.cleanStaleCache(cutoffMs = 0L)
        assertFalse(tempFile.exists())
    }
}
