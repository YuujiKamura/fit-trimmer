package fit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import crc.Crc16

class EncoderIntegrationTest {

    @Test
    fun testRealEncodingWithDummyVideoAndFit() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "test_input.fit")
        val inputMp4 = File(tempDir, "test_input.mp4")
        val outputMp4 = File(tempDir, "test_output.mp4")

        try {
            // 1. Generate a valid dummy .fit file programmatically with 10 data records aligned to 10s video timeline
            val headerSize = 14
            val recordsSize = 12 + (9 * 10) // Definition(12) + 10 Data Records(9 bytes each) = 102 bytes
            val totalSize = headerSize + recordsSize + 2
            val bytes = ByteArray(totalSize)

            bytes[0] = headerSize.toByte()
            bytes[1] = 32 // Protocol Version
            bytes[2] = 0xDC.toByte() // Profile Version
            bytes[3] = 0x07.toByte()
            bytes[4] = (recordsSize and 0xFF).toByte() // Records Size
            bytes[5] = ((recordsSize shr 8) and 0xFF).toByte()
            bytes[6] = 0x00.toByte()
            bytes[7] = 0x00.toByte()
            ".FIT".encodeToByteArray().copyInto(bytes, 8)

            var offset = headerSize
            bytes[offset] = 0x40.toByte() // Definition header
            bytes[offset + 1] = 0
            bytes[offset + 2] = 0 // Little Endian
            bytes[offset + 3] = 0x14.toByte() // Msg 20 (Record)
            bytes[offset + 4] = 0x00.toByte()
            bytes[offset + 5] = 2 // Field count

            bytes[offset + 6] = 253.toByte() // Field 1: timestamp
            bytes[offset + 7] = 4.toByte()
            bytes[offset + 8] = 0x86.toByte()

            bytes[offset + 9] = 5.toByte() // Field 2: distance
            bytes[offset + 10] = 4.toByte()
            bytes[offset + 11] = 0x86.toByte()

            offset += 12

            val baseFitTimestamp = 1000000000L
            // Write 10 data records corresponding to 0..9 seconds of the 10-second video
            for (t in 0..9) {
                bytes[offset] = 0x00.toByte() // Data header, local ID 0
                val ts = baseFitTimestamp + t
                // timestamp (4 bytes, little endian)
                bytes[offset + 1] = (ts and 0xFF).toByte()
                bytes[offset + 2] = ((ts shr 8) and 0xFF).toByte()
                bytes[offset + 3] = ((ts shr 16) and 0xFF).toByte()
                bytes[offset + 4] = ((ts shr 24) and 0xFF).toByte()
                // distance (4 bytes, little endian)
                val dist = 10000 + t * 10
                bytes[offset + 5] = (dist and 0xFF).toByte()
                bytes[offset + 6] = ((dist shr 8) and 0xFF).toByte()
                bytes[offset + 7] = ((dist shr 16) and 0xFF).toByte()
                bytes[offset + 8] = ((dist shr 24) and 0xFF).toByte()
                offset += 9
            }

            // CRC
            val computedCrc = Crc16.calculate(bytes, offset = 0, length = totalSize - 2)
            bytes[totalSize - 2] = (computedCrc and 0xFF).toByte()
            bytes[totalSize - 1] = ((computedCrc shr 8) and 0xFF).toByte()

            inputFit.writeBytes(bytes)
            println("📄 Created test dummy FIT file at: ${inputFit.absolutePath}")

            // 2. Generate a 10-second SOLID BLUE test video at 25fps using local ffmpeg
            val ffmpegPath = try {
                findFfmpegPath()
            } catch (e: Exception) {
                "ffmpeg"
            }
            println("🎥 Using ffmpeg binary: $ffmpegPath")

            val pbVideo = ProcessBuilder(
                ffmpegPath, "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=1920x1080:d=10",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "25", "-t", "10",
                inputMp4.absolutePath
            )
            pbVideo.redirectErrorStream(true)
            val pVideo = pbVideo.start()
            val videoOutput = pVideo.inputStream.bufferedReader().readText()
            val completed = pVideo.waitFor(15000, java.util.concurrent.TimeUnit.MILLISECONDS)
            
            assertTrue(completed, "FFmpeg dummy video generation should complete within 15s.")
            assertTrue(pVideo.exitValue() == 0, "FFmpeg dummy video generation failed. output:\n$videoOutput")
            println("📄 Generated test dummy video at: ${inputMp4.absolutePath}")

            // 3. Instantiate NativeHudEncoder and request road caption burn-in
            val testCaption = RoadCaptionSegment(
                id = "test-integration-caption",
                startSeconds = 0.0,
                endSeconds = 8.0,
                text = "INTEGRATION TEST CAPTION 101",
                isEnabled = true
            )
            val settings = HudSettings(
                roadCaptions = listOf(testCaption),
                captionPosition = "top_center",
                exportResolution = "360p" // 640x360 resolution
            )

            val renderedFrames = mutableListOf<java.awt.image.BufferedImage>()

            val encoder = NativeHudEncoder(settings,
                onProgress = { prog, status ->
                    println("Encoding Progress: ${(prog * 100).toInt()}% - $status")
                },
                onFrameRendered = { img ->
                    val copy = java.awt.image.BufferedImage(img.width, img.height, img.type)
                    val g = copy.createGraphics()
                    g.drawImage(img, 0, 0, null)
                    g.dispose()
                    synchronized(renderedFrames) {
                        renderedFrames.add(copy)
                    }
                }
            )

            // 4. Trigger actual encoding pipeline (no mocks!)
            // We dynamically compute startUtc to match FIT base timestamp exactly
            val fitEpochSec = 631065600L
            val startInstant = java.time.Instant.ofEpochSecond(baseFitTimestamp + fitEpochSec)
            val computedStartUtc = startInstant.toString()
            println("ℹ️ Computed startUtc for test alignment: $computedStartUtc")

            // Encode 8 seconds (leaving 2 seconds of headroom in video to prevent EOF/Broken-pipe crash exits)
            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = inputMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = computedStartUtc,
                maxDurationSeconds = 8,
                trimStartSeconds = 0.0,
                trimEndSeconds = 8.0
            )

            // 5. Check physical file output assertions
            assertTrue(outputMp4.exists(), "Output video file should be successfully created.")
            assertTrue(outputMp4.length() > 0, "Output video file size should be greater than 0 bytes.")

            // 6. Visual Verification (Pixel Scan VRT)
            assertTrue(renderedFrames.isNotEmpty(), "At least some frames should have been rendered and captured in memory.")
            
            val testFrame = renderedFrames.getOrNull(renderedFrames.size / 2)
            assertTrue(testFrame != null, "A mid-encode frame must exist for visual assertion.")
            
            // Save the frame to temporary directory for debug
            val actualFrameFile = File(tempDir, "actual_hud_frame.png")
            javax.imageio.ImageIO.write(testFrame, "png", actualFrameFile)
            println("📸 Saved rendered verification frame to: ${actualFrameFile.absolutePath}")
            
            // Save to workspace artifact directory for user direct inspection
            val artifactDest = File("C:\\Users\\yuuji\\.gemini\\antigravity-cli\\brain\\ffc6a737-4d8c-49be-bb01-5585b611f73a\\actual_hud_frame.png")
            try {
                actualFrameFile.copyTo(artifactDest, overwrite = true)
                println("📸 Copied visual VRT artifact to: ${artifactDest.absolutePath}")
            } catch (e: Exception) {
                println("⚠️ Failed to copy artifact to workspace: ${e.message}")
            }

            // Scan the top_center area where the caption is expected to be drawn.
            // Width: 640, Height: 360
            // Caption Box is roughly: X in [150, 490], Y in [40, 80]
            var nonBluePixels = 0
            var whitePixels = 0
            var darkPixels = 0
            
            for (x in 150 until 490) {
                for (y in 10 until 50) {
                    val rgb = testFrame.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    
                    // If it is a white text pixel (RGB > 200, 200, 200)
                    if (r > 200 && g > 200 && b > 200) {
                        whitePixels++
                    }
                    // If it is the dark translucent background box (blended translucent black, RGB < 100)
                    if (r < 100 && g < 100 && b < 100) {
                        darkPixels++
                    }
                    // If it is not the pure blue background (R or G has value, or B is lower due to overlay)
                    if (r > 50 || g > 50 || b < 200) {
                        nonBluePixels++
                    }
                }
            }
            
            println("📊 Pixel Scan VRT Statistics in Caption Area:")
            println("   - Dark/Black pixels (Caption box background): $darkPixels")
            println("   - White pixels (Caption text): $whitePixels")
            println("   - Total non-blue pixels: $nonBluePixels")
            
            // Assert that the translucent black box and white text pixels are present
            assertTrue(darkPixels > 100, "Caption background box (dark pixels) must be rendered in the top-center region.")
            assertTrue(whitePixels > 20, "Caption text (white pixels) must be rendered inside the top-center region.")
            assertTrue(nonBluePixels > 500, "Caption overlay must show non-background pixels in top-center region.")
            
            println("✅ Visual regression test passed: Caption box and white text verified in render buffer.")

        } finally {
            // Cleanup all temporary test files
            try { inputFit.delete() } catch (e: Exception) {}
            try { inputMp4.delete() } catch (e: Exception) {}
            try { outputMp4.delete() } catch (e: Exception) {}
            try { tempDir.deleteRecursively() } catch (e: Exception) {}
        }
    }
}
