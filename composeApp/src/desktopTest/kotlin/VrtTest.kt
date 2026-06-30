import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import org.jetbrains.skia.EncodedImageFormat
import viewmodel.AppViewModel
import TimeAlignmentState
import kotlin.test.fail
import java.awt.image.BufferedImage
import TimeAlignmentCard
import VideoTrimCard
import VideoSplitCard

class VrtTest {

    private fun verifyComponentVrt(
        fileName: String,
        width: Int = 320,
        height: Int = 300,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        val scene = ImageComposeScene(width = width, height = height) {
            MaterialTheme(colors = lightColors(primary = Color(0xFF007AFF))) {
                Box(modifier = Modifier.width(width.dp).padding(8.dp)) {
                    content()
                }
            }
        }
        
        scene.use {
            val skiaImage = it.render()
            val encodedData = skiaImage.encodeToData(EncodedImageFormat.PNG) ?: error("Failed to encode image")
            val bytes = encodedData.bytes
            val actualImage = ImageIO.read(ByteArrayInputStream(bytes))
            
            val goldenDir = File("src/desktopTest/resources/golden")
            val goldenFile = File(goldenDir, "$fileName.png")
            
            if (!goldenFile.exists()) {
                goldenDir.mkdirs()
                ImageIO.write(actualImage, "png", goldenFile)
                println("VRT: Created golden image at ${goldenFile.absolutePath}")
                return
            }
            
            val expectedImage = ImageIO.read(goldenFile)
            val diffPercentage = compareImages(expectedImage, actualImage)
            if (diffPercentage > 0.01) { // 0.01% threshold
                val diffDir = File("build/reports/vrt/diff")
                diffDir.mkdirs()
                val diffFile = File(diffDir, "${fileName}_diff.png")
                saveDiffImage(expectedImage, actualImage, diffFile)
                fail("VRT Failed for $fileName! Diff: $diffPercentage%. Diff image saved at ${diffFile.absolutePath}")
            }
        }
    }

    private fun compareImages(img1: BufferedImage, img2: BufferedImage): Double {
        if (img1.width != img2.width || img1.height != img2.height) {
            return 100.0
        }
        var diffPixels = 0L
        val totalPixels = img1.width.toLong() * img1.height.toLong()
        for (y in 0 until img1.height) {
            for (x in 0 until img1.width) {
                if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
                    diffPixels++
                }
            }
        }
        return (diffPixels.toDouble() / totalPixels.toDouble()) * 100.0
    }

    private fun saveDiffImage(img1: BufferedImage, img2: BufferedImage, destFile: File) {
        val width = img1.width
        val height = img1.height
        val diffImg = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb1 = img1.getRGB(x, y)
                val rgb2 = img2.getRGB(x, y)
                if (rgb1 != rgb2) {
                    diffImg.setRGB(x, y, java.awt.Color.RED.rgb)
                } else {
                    val c = java.awt.Color(rgb1, true)
                    val gray = (c.red + c.green + c.blue) / 3
                    val dimColor = java.awt.Color(gray, gray, gray, 60)
                    diffImg.setRGB(x, y, dimColor.rgb)
                }
            }
        }
        ImageIO.write(diffImg, "png", destFile)
    }

    @Test
    fun testTimeAlignmentCardAppearance() {
        val dummyState = TimeAlignmentState(0)
        verifyComponentVrt("time_alignment_card", width = 320, height = 220) {
            TimeAlignmentCard(
                state = dummyState,
                videoPath = "C:/path/to/sample_video.mp4",
                telemetryPoints = emptyList(),
                isAligning = false,
                onAlignTelemetryClick = {},
                isEncoding = false
            )
        }
    }

    @Test
    fun testVideoTrimCardAppearance() {
        verifyComponentVrt("video_trim_card", width = 320, height = 150) {
            VideoTrimCard(
                videoLengthMs = 60000L,
                trimStartSeconds = 10.0,
                trimEndSeconds = 50.0,
                onTrimStartChange = {},
                onTrimEndChange = {},
                isEncoding = false
            )
        }
    }

    @Test
    fun testVideoSplitCardAppearance() {
        val viewModel = AppViewModel(null)
        viewModel.trimStartSeconds = 5.0
        viewModel.trimEndSeconds = 55.0
        viewModel.addSplitPoint(20.0)
        viewModel.addSplitPoint(40.0)
        verifyComponentVrt("video_split_card", width = 320, height = 280) {
            VideoSplitCard(
                viewModel = viewModel,
                videoCurrentTimeMs = 30000L,
                isEncoding = false
            )
        }
    }
}
