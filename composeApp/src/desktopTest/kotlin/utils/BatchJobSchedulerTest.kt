package utils

import viewmodel.BatchJob
import viewmodel.BatchJobStatus
import fit.HudSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchJobSchedulerTest {

    @Test
    fun testAddToQueueAndOperations() {
        var saveQueueCalled = false
        var saveHistoryCalled = false
        
        val scheduler = BatchJobScheduler(
            onSaveBatchQueue = { saveQueueCalled = true },
            onSaveCurrentHistory = { saveHistoryCalled = true },
            onCreateBatchJob = { video, fit, start, tStart, tEnd, splits, duration ->
                BatchJob(
                    id = "test-job-id",
                    videoPath = video,
                    fitPath = fit,
                    videoStartUtc = start,
                    timeOffsetMillis = 0L,
                    trimStartSeconds = tStart,
                    trimEndSeconds = tEnd,
                    splitPoints = splits,
                    initialSettings = HudSettings(),
                    initialStatus = BatchJobStatus.WAITING
                )
            }
        )

        assertEquals(0, scheduler.batchQueue.size)
        
        // Add
        scheduler.addToQueue(
            videoPath = "video.mp4",
            fitPath = "fit.fit",
            videoStartUtc = "2026-06-30T08:44:58Z",
            trimStartSeconds = 0.0,
            trimEndSeconds = 10.0,
            splitPoints = emptyList(),
            videoLengthMs = 10000L
        )

        assertEquals(1, scheduler.batchQueue.size)
        assertTrue(saveQueueCalled)
        
        // Rename
        scheduler.renameBatchJobEntry("test-job-id", "new_name")
        assertEquals("new_name.mp4", scheduler.batchQueue[0].outputFileNames.firstOrNull())
        
        // Clear
        scheduler.clearQueue()
        assertEquals(0, scheduler.batchQueue.size)
    }
}
