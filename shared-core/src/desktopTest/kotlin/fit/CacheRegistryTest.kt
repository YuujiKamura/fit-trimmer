package fit

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun testConcurrencyStress() {
        val executor = Executors.newFixedThreadPool(8)
        val filesCount = 500
        val groupKey = "stress_job"
        val files = mutableListOf<File>()

        for (i in 1..filesCount) {
            executor.submit {
                val f = CacheRegistry.createTempFile(
                    groupKey = groupKey,
                    fileName = "stress_file_$i.tmp",
                    parentDir = testTempDir
                )
                f.writeText("stress data")
                synchronized(files) {
                    files.add(f)
                }
            }
        }

        val cleanupExecutor = Executors.newSingleThreadExecutor()
        cleanupExecutor.submit {
            for (j in 1..20) {
                CacheRegistry.cleanStaleCache(cutoffMs = 0L)
                Thread.sleep(5)
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        cleanupExecutor.shutdown()
        cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)

        CacheRegistry.invalidateGroup(groupKey)
        files.forEach {
            assertFalse(it.exists(), "File should be deleted cleanly under concurrent stress: ${it.name}")
        }
    }

    @Test
    fun testLockConflictAndInvalidateSafety() {
        val groupKey = "lock_job"
        val tempFile = CacheRegistry.createTempFile(
            groupKey = groupKey,
            fileName = "locked_file.tmp",
            parentDir = testTempDir
        )
        tempFile.writeText("locked data")

        val raf = java.io.RandomAccessFile(tempFile, "rw")
        val channel = raf.channel
        val lock = channel.tryLock()

        try {
            CacheRegistry.invalidateGroup(groupKey)
        } finally {
            lock?.release()
            channel.close()
            raf.close()
        }
        
        tempFile.delete()
    }
}
