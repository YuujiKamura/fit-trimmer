package utils

import java.io.File
import fit.PathResolver
import fit.findFfmpegPath
import fit.HudSettings
import fit.JobStateManager
import fit.JobState

object CrashSimulator {
    fun setupSimulation(): String {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fittrimmer_crash_sim_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        val mockVideoFile = File(tempDir, "ft_crash_test_video.mp4")
        if (!mockVideoFile.exists()) {
            mockVideoFile.createNewFile()
        }
        
        // 1. Setup batch queue JSON cache
        val simulatedJob = SerializedBatchJob(
            id = "simulated-crash-job",
            videoPath = mockVideoFile.absolutePath,
            fitPath = "dummy.fit",
            videoStartUtc = "2026-07-08T10:00:00Z",
            timeOffsetMillis = 0L,
            trimStartSeconds = 0.0,
            trimEndSeconds = 0.4,
            splitPoints = emptyList(),
            settings = HudSettings(),
            autoDetectRoadCaptionsOnEncode = false,
            outputFileNames = listOf("ft_crash_test_video_salvaged.mp4"),
            status = "WAITING",
            progress = 0f,
            errorMessage = null
        )
        
        BatchQueueCache.save(listOf(simulatedJob))
        
        // 2. Setup job folder
        val workDir = PathResolver.getTempWorkDir(mockVideoFile.absolutePath)
        val jobDir = File(workDir, "job_simulated_crash")
        jobDir.mkdirs()
        
        // Write .video_source
        File(jobDir, ".video_source").writeText(mockVideoFile.absolutePath)
        
        // Create 2 mock TS files so we can verify merging actually works
        val part1 = File(jobDir, "part_0000.ts")
        val part2 = File(jobDir, "part_0001.ts")
        createMockTsFile(part1)
        createMockTsFile(part2)
        
        // Also save JobStateManager state to make CacheJobManager recognize it
        JobStateManager.saveState(jobDir, JobState("simulated_crash", videoPath = mockVideoFile.absolutePath))
        
        println("🚀 [CrashSimulator] Setup crash simulation environment:")
        println("  - Temp Dir: ${tempDir.absolutePath}")
        println("  - Mock Video: ${mockVideoFile.absolutePath}")
        println("  - Job Dir: ${jobDir.absolutePath}")
        println("  - Queue Cache File: ${BatchQueueCache.file.absolutePath}")
        
        return mockVideoFile.absolutePath
    }
    
    private fun createMockTsFile(dest: File) {
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val pb = ProcessBuilder(
            ffmpegPath, "-y",
            "-nostdin",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=10:duration=0.2",
            "-c:v", "libopenh264",
            "-f", "mpegts",
            dest.absolutePath
        )
        pb.redirectErrorStream(true)
        try {
            val p = pb.start()
            p.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
