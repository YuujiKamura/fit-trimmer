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

    private fun createMockTsFile(dest: File) {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val pb = ProcessBuilder(
            ffmpegPath, "-y",
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
        val filesCount = 200
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

        // 並行して無関係なクリーンアップ（24時間前をターゲット）を走らせることで、競合状態をテスト
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        cleanupExecutor.submit {
            for (j in 1..20) {
                CacheRegistry.cleanStaleCache(cutoffMs = 24 * 60 * 60 * 1000L)
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

    @Test
    fun testScanAvailableJobsAndSalvageAndMerge() {
        val mockVideoPath = File(testTempDir, "video_mock.mp4").absolutePath
        val workDir = PathResolver.getTempWorkDir(mockVideoPath)
        val jobDir = File(workDir, "job_99999")
        jobDir.mkdirs()

        val part1 = File(jobDir, "part_0000.ts")
        val part2 = File(jobDir, "part_0001.ts")
        createMockTsFile(part1)
        createMockTsFile(part2)
        JobStateManager.saveState(jobDir, JobState("99999", videoPath = mockVideoPath))
        File(jobDir, ".video_source").writeText(mockVideoPath)

        assertTrue(part1.exists() && part1.length() > 0, "Mock TS part 1 should be a valid video stream")
        assertTrue(part2.exists() && part2.length() > 0, "Mock TS part 2 should be a valid video stream")

        try {
            val jobs = CacheRegistry.scanAvailableJobs(mockVideoPath)
            val myJob = jobs.find { it.jobHash == "99999" }
            assertNotNull(myJob, "Should scan and find our dummy job")
            assertEquals(2, myJob.partsCount, "Parts count should match")

            val salvagedOutput = File(testTempDir, "salvaged_output.mp4").absolutePath
            val progressList = mutableListOf<Float>()
            val statusList = mutableListOf<String>()
            CacheRegistry.salvageAndMerge(jobDir, salvagedOutput) { progress, status ->
                println("Salvage progress: $progress -> $status")
                progressList.add(progress)
                statusList.add(status)
            }

            val mergingStatuses = statusList.filter { "Merging video segments" in it }
            assertTrue(mergingStatuses.isNotEmpty(), "Should report merging status")
            val hasPercentAndMb = mergingStatuses.any { "%" in it && "MB" in it }
            assertTrue(hasPercentAndMb, "Merging status should report numeric progress in % and size in MB (actual statuses: $mergingStatuses)")

            val outFile = File(salvagedOutput)
            assertTrue(outFile.exists(), "Salvaged output file should be generated")
            assertTrue(outFile.length() > 0, "Salvaged file should contain data")
            assertFalse(jobDir.exists(), "Job directory should be cleaned up after successful salvage")
        } finally {
            part1.delete()
            part2.delete()
            jobDir.delete()
        }
    }

    @Test
    fun testVideoSwitchingCacheIsolationAndRecovery() {
        val videoPathA = File(testTempDir, "video_a.mp4").absolutePath
        val videoPathB = File(testTempDir, "video_b.mp4").absolutePath

        val workDirA = PathResolver.getTempWorkDir(videoPathA)
        val workDirB = PathResolver.getTempWorkDir(videoPathB)

        val jobDirA = File(workDirA, "job_aaaaa")
        val jobDirB = File(workDirB, "job_bbbbb")

        jobDirA.mkdirs()
        jobDirB.mkdirs()

        val partA = File(jobDirA, "part_0000.ts")
        val partB = File(jobDirB, "part_0000.ts")

        createMockTsFile(partA)
        createMockTsFile(partB)
        JobStateManager.saveState(jobDirA, JobState("aaaaa", videoPath = videoPathA))
        JobStateManager.saveState(jobDirB, JobState("bbbbb", videoPath = videoPathB))
        File(jobDirA, ".video_source").writeText(videoPathA)
        File(jobDirB, ".video_source").writeText(videoPathB)

        try {
            // 1. Scan & Isolation: Scan of A must not find job B caches
            val jobsA = CacheRegistry.scanAvailableJobs(videoPathA)
            val foundJobA = jobsA.find { it.jobHash == "aaaaa" }
            val foundJobBInA = jobsA.find { it.jobHash == "bbbbb" }
            assertNotNull(foundJobA, "Should find job A cache under video A path")
            assertNull(foundJobBInA, "Should NOT find job B cache under video A path")

            // 2. Verify Scan B
            val jobsB = CacheRegistry.scanAvailableJobs(videoPathB)
            val foundJobB = jobsB.find { it.jobHash == "bbbbb" }
            val foundJobAInB = jobsB.find { it.jobHash == "aaaaa" }
            assertNotNull(foundJobB, "Should find job B cache under video B path")
            assertNull(foundJobAInB, "Should NOT find job A cache under video B path")

            // 3. Simulate re-loading video A (switching back)
            val reloadJobsA = CacheRegistry.scanAvailableJobs(videoPathA)
            assertNotNull(reloadJobsA.find { it.jobHash == "aaaaa" }, "Should recover job A cache upon re-scanning video A path")

            // 4. Verify bulk cleanup (clearAllCaches)
            CacheRegistry.clearAllCaches(videoPathA)
            assertFalse(jobDirA.exists(), "Job directory A should be completely removed by clearAllCaches")
            assertTrue(jobDirB.exists(), "Job directory B must remain untouched and isolated")

            // 5. Post-cleanup scanning state
            assertTrue(CacheRegistry.scanAvailableJobs(videoPathA).isEmpty(), "Scanned jobs for A should be empty after clearAllCaches")
            assertFalse(CacheRegistry.scanAvailableJobs(videoPathB).isEmpty(), "Scanned jobs for B must still be intact")
        } finally {
            partA.delete()
            partB.delete()
            jobDirA.delete()
            jobDirB.delete()
            CacheRegistry.clearAllCaches(videoPathA)
            CacheRegistry.clearAllCaches(videoPathB)
        }
    }
}
