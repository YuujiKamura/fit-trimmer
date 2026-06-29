package utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuiCacheTest {
    @Test
    fun shouldDeferSaveOnlyWhileExistingVideoStartUtcIsPending() {
        val videoFile = kotlin.io.path.createTempFile(suffix = ".mp4").toFile()
        try {
            assertTrue(
                GuiCache.shouldDeferSaveUntilVideoStartIsLoaded(
                    videoPath = videoFile.absolutePath,
                    videoStartUtc = ""
                )
            )

            assertFalse(
                GuiCache.shouldDeferSaveUntilVideoStartIsLoaded(
                    videoPath = videoFile.absolutePath,
                    videoStartUtc = "2026-06-21T02:09:49Z"
                )
            )
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun shouldNotDeferSaveForMissingOrEmptyVideoPath() {
        assertFalse(
            GuiCache.shouldDeferSaveUntilVideoStartIsLoaded(
                videoPath = "",
                videoStartUtc = ""
            )
        )

        assertFalse(
            GuiCache.shouldDeferSaveUntilVideoStartIsLoaded(
                videoPath = "Z:\\missing\\video.mp4",
                videoStartUtc = ""
            )
        )
    }
}
