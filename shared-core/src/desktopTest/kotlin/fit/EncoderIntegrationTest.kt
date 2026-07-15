package fit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import crc.Crc16

class EncoderIntegrationTest {

    @Test
    fun testNativeHudEncoderIsHudEncoder() {
        val factory: HudEncoderFactory = NativeHudEncoder.Companion
        val settings = HudSettings()
        val encoder: HudEncoder = factory.create(settings)
        assertTrue(encoder is HudEncoder)
    }

    @Test
    fun testHasResumeCacheMatchesEncodeHash() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-hash-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val fitPath = File(tempDir, "hash_test.fit").absolutePath
        val videoPath = File(tempDir, "hash_test.mp4").absolutePath
        val startUtc = "2026-06-30T08:44:58Z"
        val settings = HudSettings(
            exportResolution = "1080p",
            blurLicensePlates = true,
            plateMaskExpandRatio = 0.5
        )

        val plateCache = VideoPlatesCache(
            videoPath = videoPath,
            records = listOf(PlateRecord(0, listOf(PlateBox(10, 10, 20, 20)))),
            sourceWidth = 640,
            sourceHeight = 360
        )

        val initialResult = NativeHudEncoder.hasResumeCache(
            fitPath = fitPath,
            videoPath = videoPath,
            startUtc = startUtc,
            maxDurationSeconds = -1,
            trimStartSeconds = 0.0,
            trimEndSeconds = 10.0,
            settings = settings,
            plateCache = plateCache
        )
        assertFalse(initialResult, "Should have no cache initially")

        val config = HudConfig(
            valSize = settings.valSize, tightness = settings.tightness, spacing = settings.spacing,
            xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW,
            captionPosition = settings.captionPosition,
            roadCaptions = settings.roadCaptions,
            powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
            useImperialUnits = settings.useImperialUnits,
            language = settings.language
        )
        
        val (exportWidth, exportHeight) = 1920 to 1080
        val videoWidth = 1920
        val videoHeight = 1080
        val longHash = kotlin.math.abs((
            fitPath + videoPath + startUtc + (-1) + 0.0 + 10.0 +
                videoWidth + videoHeight + exportWidth + exportHeight + config.hashCode() +
                settings.exportResolution + settings.blurLicensePlates + settings.plateMaskExpandRatio.toString() +
                (plateCache.sourceWidth) + (plateCache.sourceHeight) +
                (plateCache.records.hashCode())
        ).hashCode()).toString()

        val workDir = PathResolver.getTempWorkDir(videoPath)
        val jobDir = File(workDir, "job_$longHash")
        jobDir.mkdirs()
        
        val dummyPart = File(jobDir, "part_0000.ts")
        dummyPart.writeText("dummy ts data")

        try {
            val hasCache = NativeHudEncoder.hasResumeCache(
                fitPath = fitPath,
                videoPath = videoPath,
                startUtc = startUtc,
                maxDurationSeconds = -1,
                trimStartSeconds = 0.0,
                trimEndSeconds = 10.0,
                settings = settings,
                plateCache = plateCache
            )
            assertTrue(hasCache, "Cache should be successfully detected if jobHash logic is synchronized")
        } finally {
            dummyPart.delete()
            jobDir.delete()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEncodingProfileCapturesDetailedStageTimings() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        
        for (maskMode in listOf("off", "plate", "wide")) {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-profile-${maskMode}-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val inputFit = File(tempDir, "profile_input.fit")
            val inputMp4 = File(tempDir, "profile_input.mp4")
            val outputMp4 = File(tempDir, "profile_output.mp4")

            try {
                val baseFitTimestamp = 1000000000L
                inputFit.writeBytes(createProfileFit(baseFitTimestamp, seconds = 4))

                val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
                val pbVideo = ProcessBuilder(
                    ffmpegPath, "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=10:duration=3",
                    "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "10", "-t", "3",
                    inputMp4.absolutePath
                )
                pbVideo.redirectErrorStream(true)
                val pVideo = pbVideo.start()
                val videoOutput = pVideo.inputStream.bufferedReader().readText()
                assertTrue(pVideo.waitFor(15000, java.util.concurrent.TimeUnit.MILLISECONDS), "Profile video generation should finish.")
                assertEquals(0, pVideo.exitValue(), "Profile video generation failed:\n$videoOutput")

                val cache = VideoPlatesCache(
                    videoPath = inputMp4.absolutePath,
                    records = listOf(
                        PlateRecord(0, listOf(PlateBox(100, 120, 180, 150))),
                        PlateRecord(1000, listOf(PlateBox(130, 130, 210, 160)))
                    ),
                    sourceWidth = 640,
                    sourceHeight = 360
                )
                PlateCacheManager.saveCache(inputMp4.absolutePath, cache)

                var profile: EncodeProfileReport? = null
                val encoder = NativeHudEncoder(
                    settings = HudSettings(
                        exportResolution = "360p",
                        blurLicensePlates = (maskMode != "off"),
                        plateMaskExpandRatio = if (maskMode == "wide") 1.5 else 0.2
                    ),
                    showLivePreviewSupplier = { false },
                    profileSink = { report ->
                        profile = report
                        println("Mode: $maskMode -> ${report.toMetricLine()}")
                        report.appendToHistory(maskMode)
                    }
                )

                val fitEpochSec = 631065600L
                val computedStartUtc = java.time.Instant.ofEpochSecond(baseFitTimestamp + fitEpochSec).toString()
                encoder.encode(
                    fitPath = inputFit.absolutePath,
                    videoPath = inputMp4.absolutePath,
                    output = outputMp4.absolutePath,
                    startUtc = computedStartUtc,
                    maxDurationSeconds = 2,
                    trimStartSeconds = 0.0,
                    trimEndSeconds = 2.0
                )

                val report = assertNotNull(profile, "Encoding profile report must be emitted for mode: $maskMode")
                assertTrue(outputMp4.exists() && outputMp4.length() > 0, "Profile encode output must be created for mode: $maskMode")
                assertTrue(report.frameCount > 0, "Profile must count encoded frames for mode: $maskMode")
                
                if (maskMode == "off") {
                    assertEquals(0.0, report.maskPlanMs, "Mask planning should be 0 for off mode.")
                    assertEquals(0.0, report.maskVideoMs, "Mask video generation should be 0 for off mode.")
                } else {
                    assertTrue(report.maskPlanMs >= 0.0, "Profile must include mask planning time for mode: $maskMode")
                    assertTrue(report.maskVideoMs > 0.0, "Mask video generation should be active for FFmpeg-based blur mode: $maskMode")
                }
                
                assertTrue(report.hudRenderMs > 0.0, "Profile must include HUD render time for mode: $maskMode")
                assertTrue(report.rawCopyMs > 0.0, "Profile must include raw frame copy time for mode: $maskMode")
                assertTrue(report.pipeWriteMs > 0.0, "Profile must include pipe write time for mode: $maskMode")
                assertTrue(report.pipeBytes > 0, "Profile must include pipe byte count for mode: $maskMode")
                assertTrue(report.pipeMiB < 25.0, "2s 360p profile should keep HUD pipe volume bounded after mask stream split for mode: $maskMode")
            } finally {
                try { PlateCacheManager.deleteCache(inputMp4.absolutePath) } catch (e: Exception) {}
                try { inputFit.delete() } catch (e: Exception) {}
                try { inputMp4.delete() } catch (e: Exception) {}
                try { outputMp4.delete() } catch (e: Exception) {}
                try { tempDir.deleteRecursively() } catch (e: Exception) {}
            }
        }
    }

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

            val testSegment = AutoDetectedSegment(
                id = "test-seg",
                name = "Yabitsu Pass (ヤビツ峠)",
                startIndex = 0,
                endIndex = 250,
                distanceMeters = 5000.0,
                durationSeconds = 1200.0,
                averageGrade = 6.0,
                startFitTimestamp = 0.0,
                endFitTimestamp = 10.0,
                prTimeSeconds = 420.0,
                komTimeSeconds = 360.0,
                recentTimeSeconds = 400.0
            )
            HudEncoderSegmentRegistry.activeSegments = listOf(testSegment)

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
                },
                customRenderer = { canvas, point, allPoints, trimmedPoints, pBuf, progressRatio ->
                    // Mutate point properties to force CTL and active segment status
                    point.ctl = 65.0
                    point.atl = 85.0
                    point.tsb = -20.0
                    
                    // Create a mock point and points list to simultaneously trigger both 
                    // 50% CHECKPOINT and 10 MIN SUMMARY popups in the same verification frame
                    val mockPoint = TelemetryPoint(
                        timestamp = 5.0, // Exactly 50% of the segment (0.0 to 10.0)
                        speed = point.speed,
                        power = 240.0,
                        cadence = point.cadence,
                        heartRate = point.heartRate,
                        elevation = point.elevation,
                        grade = point.grade,
                        lat = point.lat,
                        lon = point.lon,
                        distance = 2500.0, // 50% of 5000m
                        elapsedSeconds = 600, // Trigger 10 MIN SUMMARY (600 % 600 == 0)
                        temperature = point.temperature,
                        ctl = 65.0,
                        atl = 85.0,
                        tsb = -20.0
                    )
                    
                    val mockAllPoints = (0..59).map { idx ->
                        TelemetryPoint(
                            timestamp = 5.0 - (60 - idx),
                            speed = point.speed,
                            power = 240.0,
                            cadence = point.cadence,
                            heartRate = point.heartRate,
                            elevation = point.elevation,
                            grade = point.grade,
                            lat = point.lat,
                            lon = point.lon,
                            distance = 2500.0,
                            elapsedSeconds = 600 - (60 - idx),
                            temperature = point.temperature
                        )
                    } + mockPoint

                    val renderer = HudRenderer(HudConfig(
                        valSize = settings.valSize, tightness = settings.tightness, spacing = settings.spacing,
                        xOffset = settings.xOffset, yOffset = settings.yOffset, graphH = settings.graphH, graphW = settings.graphW,
                        captionPosition = settings.captionPosition,
                        roadCaptions = settings.roadCaptions,
                        powerTrendSpanSeconds = settings.powerTrendSpanSeconds,
                        useImperialUnits = settings.useImperialUnits,
                        language = settings.language,
                        elevationGraphScope = settings.elevationGraphScope,
                        heartRateAccumulationScope = settings.heartRateAccumulationScope,
                        showSpeed = settings.showSpeed,
                        showCadence = settings.showCadence,
                        showHeartRate = settings.showHeartRate,
                        showPower = settings.showPower,
                        showWkg = settings.showWkg,
                        showPowerTrend = settings.showPowerTrend,
                        showGrade = settings.showGrade,
                        showElevation = settings.showElevation,
                        showDistanceTime = settings.showDistanceTime,
                        bodyWeightKg = settings.bodyWeightKg,
                        customCaptions = settings.customCaptions,
                        mapSizeScale = settings.mapSizeScale,
                        mapType = settings.mapType,
                        mapPosition = settings.mapPosition,
                        hudBgAlpha = settings.hudBgAlpha,
                        mapZoomScale = settings.mapZoomScale,
                        mapZoomOffset = settings.mapZoomOffset,
                        fixMapNorthUp = settings.fixMapNorthUp,
                        mapMarkerSizeScale = settings.mapMarkerSizeScale,
                        mapTextSizeScale = settings.mapTextSizeScale,
                        mapRangeMode = settings.mapRangeMode,
                        textShadowAlpha = settings.textShadowAlpha,
                        detectedSegments = HudEncoderSegmentRegistry.activeSegments
                    ))
                    renderer.renderFrame(canvas, mockPoint, mockAllPoints, mockAllPoints, pBuf, progressRatio)
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
            val artifactDest = File("C:\\Users\\yuuji\\.gemini\\antigravity-cli\\brain\\5ce67e2f-0e29-4828-aa28-4bd61a45238d\\actual_hud_frame.png")
            val tempWorkDest = File("temp_work/preview_hud.png")
            try {
                artifactDest.parentFile?.mkdirs()
                actualFrameFile.copyTo(artifactDest, overwrite = true)
                println("📸 Copied visual VRT artifact to: ${artifactDest.absolutePath}")
                
                tempWorkDest.parentFile?.mkdirs()
                actualFrameFile.copyTo(tempWorkDest, overwrite = true)
                println("📸 Copied preview frame to: ${tempWorkDest.absolutePath}")
            } catch (e: Exception) {
                println("⚠️ Failed to copy artifact: ${e.message}")
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

    @Test
    fun testRealEncodingWithOffsetTrim() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-test-offset-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "test_input.fit")
        val inputMp4 = File(tempDir, "test_input.mp4")
        val outputMp4 = File(tempDir, "test_output.mp4")

        try {
            // 1. Generate a valid dummy .fit file programmatically with 10 data records
            val headerSize = 14
            val recordsSize = 12 + (9 * 10)
            val totalSize = headerSize + recordsSize + 2
            val bytes = ByteArray(totalSize)

            bytes[0] = headerSize.toByte()
            bytes[1] = 32
            bytes[2] = 0xDC.toByte()
            bytes[3] = 0x07.toByte()
            bytes[4] = (recordsSize and 0xFF).toByte()
            bytes[5] = ((recordsSize shr 8) and 0xFF).toByte()
            bytes[6] = 0x00.toByte()
            bytes[7] = 0x00.toByte()
            ".FIT".encodeToByteArray().copyInto(bytes, 8)

            var offset = headerSize
            bytes[offset] = 0x40.toByte()
            bytes[offset + 1] = 0
            bytes[offset + 2] = 0
            bytes[offset + 3] = 0x14.toByte()
            bytes[offset + 4] = 0x00.toByte()
            bytes[offset + 5] = 2

            bytes[offset + 6] = 253.toByte()
            bytes[offset + 7] = 4.toByte()
            bytes[offset + 8] = 0x86.toByte()

            bytes[offset + 9] = 5.toByte()
            bytes[offset + 10] = 4.toByte()
            bytes[offset + 11] = 0x86.toByte()

            offset += 12

            val baseFitTimestamp = 1000000000L
            for (t in 0..9) {
                bytes[offset] = 0x00.toByte()
                val ts = baseFitTimestamp + t
                bytes[offset + 1] = (ts and 0xFF).toByte()
                bytes[offset + 2] = ((ts shr 8) and 0xFF).toByte()
                bytes[offset + 3] = ((ts shr 16) and 0xFF).toByte()
                bytes[offset + 4] = ((ts shr 24) and 0xFF).toByte()
                
                val dist = 10000 + t * 10
                bytes[offset + 5] = (dist and 0xFF).toByte()
                bytes[offset + 6] = ((dist shr 8) and 0xFF).toByte()
                bytes[offset + 7] = ((dist shr 16) and 0xFF).toByte()
                bytes[offset + 8] = ((dist shr 24) and 0xFF).toByte()
                offset += 9
            }

            val computedCrc = Crc16.calculate(bytes, offset = 0, length = totalSize - 2)
            bytes[totalSize - 2] = (computedCrc and 0xFF).toByte()
            bytes[totalSize - 1] = ((computedCrc shr 8) and 0xFF).toByte()

            inputFit.writeBytes(bytes)

            // 2. Generate a 10-second SOLID BLUE test video at 25fps using local ffmpeg
            val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
            val pbVideo = ProcessBuilder(
                ffmpegPath, "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=1920x1080:d=10",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "25", "-t", "10",
                inputMp4.absolutePath
            )
            pbVideo.redirectErrorStream(true)
            val pVideo = pbVideo.start()
            pVideo.waitFor()

            // 3. Request road caption burn-in
            val testCaption = RoadCaptionSegment(
                id = "test-integration-caption-offset",
                startSeconds = 0.0,
                endSeconds = 5.0,
                text = "OFFSET TRIM TEST",
                isEnabled = true
            )
            val settings = HudSettings(
                roadCaptions = listOf(testCaption),
                captionPosition = "top_center",
                exportResolution = "360p"
            )

            val renderedFrames = mutableListOf<java.awt.image.BufferedImage>()
            val encoder = NativeHudEncoder(settings,
                onProgress = { _, _ -> },
                onFrameRendered = { img ->
                    val copy = java.awt.image.BufferedImage(img.width, img.height, img.type)
                    val g = copy.createGraphics()
                    g.drawImage(img, 0, 0, null)
                    g.dispose()
                    synchronized(renderedFrames) { renderedFrames.add(copy) }
                }
            )

            // 4. Trigger actual encoding pipeline with Offset (trimStartSeconds = 3.0, trimEndSeconds = 8.0)
            val fitEpochSec = 631065600L
            val startInstant = java.time.Instant.ofEpochSecond(baseFitTimestamp + fitEpochSec)
            val computedStartUtc = startInstant.toString()

            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = inputMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = computedStartUtc,
                maxDurationSeconds = 5,
                trimStartSeconds = 3.0,
                trimEndSeconds = 8.0
            )

            // 5. Check physical file output assertions
            assertTrue(outputMp4.exists() && outputMp4.length() > 0)

            // 6. Visual Verification
            assertTrue(renderedFrames.isNotEmpty())
            val testFrame = renderedFrames.getOrNull(renderedFrames.size / 2)
            assertTrue(testFrame != null)

            var nonBluePixels = 0
            var whitePixels = 0
            var darkPixels = 0
            for (x in 150 until 490) {
                for (y in 10 until 50) {
                    val rgb = testFrame.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    if (r > 200 && g > 200 && b > 200) whitePixels++
                    if (r < 100 && g < 100 && b < 100) darkPixels++
                    if (r > 50 || g > 50 || b < 200) nonBluePixels++
                }
            }

            assertTrue(darkPixels > 100, "Caption box must exist in offset trim.")
            assertTrue(whitePixels > 20, "Caption text must exist in offset trim.")
            println("✅ Offset trim VRT passed successfully!")

        } finally {
            try { inputFit.delete() } catch (e: Exception) {}
            try { inputMp4.delete() } catch (e: Exception) {}
            try { outputMp4.delete() } catch (e: Exception) {}
            try { tempDir.deleteRecursively() } catch (e: Exception) {}
        }
    }

    @Test
    fun testEncodingFailsWithInvalidVideoPath() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-test-fail-video-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "test_input.fit")
        val headerSize = 14
        val bytes = ByteArray(headerSize + 2)
        bytes[0] = headerSize.toByte()
        bytes[1] = 32
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        ".FIT".encodeToByteArray().copyInto(bytes, 8)
        inputFit.writeBytes(bytes)

        val invalidMp4 = File(tempDir, "non_existent_video.mp4")
        val outputMp4 = File(tempDir, "test_output.mp4")

        val settings = HudSettings(exportResolution = "360p")
        val encoder = NativeHudEncoder(settings)

        var thrown = false
        try {
            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = invalidMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = "2026-06-21T02:09:49Z",
                maxDurationSeconds = 5
            )
        } catch (e: Exception) {
            println("Caught expected exception for invalid video: ${e.message}")
            thrown = true
        }

        assertTrue(thrown, "Should throw exception when video file does not exist")

        try { inputFit.delete() } catch (e: Exception) {}
        try { tempDir.deleteRecursively() } catch (e: Exception) {}
    }

    @Test
    fun testEncodingCancelCleanup() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-test-cancel-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "test_input.fit")
        val headerSize = 14
        val bytes = ByteArray(headerSize + 2)
        bytes[0] = headerSize.toByte()
        bytes[1] = 32
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        ".FIT".encodeToByteArray().copyInto(bytes, 8)
        inputFit.writeBytes(bytes)

        val dummyMp4 = File(tempDir, "dummy_video.mp4")
        val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
        val pbVideo = ProcessBuilder(
            ffmpegPath, "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=128x128:d=2",
            "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "25", "-t", "2",
            dummyMp4.absolutePath
        )
        pbVideo.redirectErrorStream(true)
        val pVideo = pbVideo.start()
        pVideo.waitFor()

        val outputMp4 = File(tempDir, "test_output.mp4")
        val settings = HudSettings(exportResolution = "360p")
        val encoder = NativeHudEncoder(settings, cancelSupplier = { true })

        var thrown = false
        try {
            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = dummyMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = "2026-06-21T02:09:49Z",
                maxDurationSeconds = 2
            )
        } catch (e: Exception) {
            println("Caught expected cancel exception: ${e.message}")
            if (e.message?.contains("canceled") == true) {
                thrown = true
            }
        }

        assertTrue(thrown, "Should throw exception stating that encoding was canceled")

        val workDir = PathResolver.getTempWorkDir(dummyMp4.absolutePath)
        val jobDirs = workDir.listFiles { _, name -> name.startsWith("job_") }
        assertTrue(jobDirs.isNullOrEmpty(), "Job directories should be fully cleaned up after cancellation")

        try { inputFit.delete() } catch (e: Exception) {}
        try { dummyMp4.delete() } catch (e: Exception) {}
        try { tempDir.deleteRecursively() } catch (e: Exception) {}
    }

    @Test
    fun testFindTelemetryLerpInterpolation() {
        val p0 = fit.TelemetryPoint(
            timestamp = 100.0,
            speed = 10.0,
            power = 100.0,
            cadence = 80.0,
            heartRate = 120.0,
            elevation = 50.0,
            grade = 2.0,
            lat = 35.0,
            lon = 135.0,
            distance = 100.0,
            elapsedSeconds = 10
        )
        val p1 = fit.TelemetryPoint(
            timestamp = 200.0,
            speed = 20.0,
            power = 200.0,
            cadence = 90.0,
            heartRate = 140.0,
            elevation = 60.0,
            grade = 4.0,
            lat = 36.0,
            lon = 136.0,
            distance = 300.0,
            elapsedSeconds = 30
        )
        val telemetry = listOf(p0, p1)
        val lerped = findTelemetryLerp(telemetry, 150.0)

        assertEquals(150.0, lerped.timestamp, 0.001)
        assertEquals(15.0, lerped.speed, 0.001)
        assertEquals(150.0, lerped.power, 0.001)
        assertEquals(85.0, lerped.cadence, 0.001)
        assertEquals(130.0, lerped.heartRate, 0.001)
        assertEquals(55.0, lerped.elevation, 0.001)
        assertEquals(3.0, lerped.grade, 0.001)
        assertEquals(35.5, lerped.lat, 0.001)
        assertEquals(135.5, lerped.lon, 0.001)
        assertEquals(200.0, lerped.distance, 0.001)
        assertEquals(20, lerped.elapsedSeconds)
    }

    @Test
    fun testFindTelemetryLerpOutOfBoundsZero() {
        val p0 = fit.TelemetryPoint(
            timestamp = 100.0,
            speed = 10.0,
            power = 100.0,
            cadence = 80.0,
            heartRate = 120.0,
            elevation = 50.0,
            grade = 2.0,
            lat = 35.0,
            lon = 135.0,
            distance = 100.0,
            elapsedSeconds = 10
        )
        val p1 = fit.TelemetryPoint(
            timestamp = 200.0,
            speed = 20.0,
            power = 200.0,
            cadence = 90.0,
            heartRate = 140.0,
            elevation = 60.0,
            grade = 4.0,
            lat = 36.0,
            lon = 136.0,
            distance = 300.0,
            elapsedSeconds = 30
        )
        val telemetry = listOf(p0, p1)

        val lerpedUnder = findTelemetryLerp(telemetry, 50.0)
        assertEquals(50.0, lerpedUnder.timestamp, 0.001)
        assertEquals(0.0, lerpedUnder.speed, 0.001, "Speed should be 0.0 when OOB")
        assertEquals(0.0, lerpedUnder.power, 0.001, "Power should be 0.0 when OOB")
        assertEquals(0.0, lerpedUnder.cadence, 0.001, "Cadence should be 0.0 when OOB")
        assertEquals(120.0, lerpedUnder.heartRate, 0.001, "Heart rate should be first point when OOB")
        assertEquals(50.0, lerpedUnder.elevation, 0.001)
        assertEquals(100.0, lerpedUnder.distance, 0.001)

        val lerpedOver = findTelemetryLerp(telemetry, 250.0)
        assertEquals(250.0, lerpedOver.timestamp, 0.001)
        assertEquals(0.0, lerpedOver.speed, 0.001, "Speed should be 0.0 when OOB")
        assertEquals(0.0, lerpedOver.power, 0.001, "Power should be 0.0 when OOB")
        assertEquals(0.0, lerpedOver.cadence, 0.001, "Cadence should be 0.0 when OOB")
        assertEquals(140.0, lerpedOver.heartRate, 0.001, "Heart rate should be last point when OOB")
        assertEquals(60.0, lerpedOver.elevation, 0.001)
        assertEquals(300.0, lerpedOver.distance, 0.001)
    }

    @Test
    fun testLivePreviewTogglingDuringEncode() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-test-toggle-preview-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "test_input.fit")
        val inputMp4 = File(tempDir, "test_input.mp4")
        val outputMp4 = File(tempDir, "test_output.mp4")

        try {
            inputFit.writeBytes(createProfileFit(1000000000L, seconds = 3))
            
            val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
            val pbVideo = ProcessBuilder(
                ffmpegPath, "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=128x128:d=3",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "10", "-t", "3",
                inputMp4.absolutePath
            )
            pbVideo.redirectErrorStream(true)
            val pVideo = pbVideo.start()
            pVideo.waitFor()

            var livePreviewState = false
            val renderedFrames = mutableListOf<java.awt.image.BufferedImage>()
            var progressCalls = 0

            val encoder = NativeHudEncoder(
                settings = HudSettings(exportResolution = "360p"),
                onProgress = { prog, _ ->
                    progressCalls++
                    if (progressCalls == 10) {
                        livePreviewState = true
                    }
                },
                onFrameRendered = { img ->
                    synchronized(renderedFrames) {
                        renderedFrames.add(img)
                    }
                },
                showLivePreviewSupplier = { livePreviewState }
            )

            val fitEpochSec = 631065600L
            val computedStartUtc = java.time.Instant.ofEpochSecond(1000000000L + fitEpochSec).toString()

            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = inputMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = computedStartUtc,
                maxDurationSeconds = 2,
                trimStartSeconds = 0.0,
                trimEndSeconds = 2.0
            )

            assertTrue(renderedFrames.isNotEmpty(), "Frames should be rendered after preview is toggled ON")
            assertTrue(renderedFrames.size < 20, "No frames should be rendered before preview is toggled ON, so total frames must be less than 20")
        } finally {
            try { inputFit.delete() } catch (e: Exception) {}
            try { inputMp4.delete() } catch (e: Exception) {}
            try { outputMp4.delete() } catch (e: Exception) {}
            try { tempDir.deleteRecursively() } catch (e: Exception) {}
        }
    }

    @Test
    fun testEncodingWithSpeedSegments() {
        System.setProperty("FIT_TRIMMER_FORCE_CPU", "true")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fit-trimmer-speed-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val inputFit = File(tempDir, "speed_input.fit")
        val inputMp4 = File(tempDir, "speed_input.mp4")
        val outputMp4 = File(tempDir, "speed_output.mp4")

        try {
            val baseFitTimestamp = 1000000000L
            inputFit.writeBytes(createProfileFit(baseFitTimestamp, seconds = 10))

            val ffmpegPath = try { findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
            val pbVideo = ProcessBuilder(
                ffmpegPath, "-y",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=10:duration=6",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-r", "10", "-t", "6",
                inputMp4.absolutePath
            )
            pbVideo.redirectErrorStream(true)
            val pVideo = pbVideo.start()
            val videoOutput = pVideo.inputStream.bufferedReader().readText()
            assertTrue(pVideo.waitFor(15000, java.util.concurrent.TimeUnit.MILLISECONDS), "Speed test video generation should finish.")
            assertEquals(0, pVideo.exitValue(), "Speed test video generation failed:\n$videoOutput")

            val speedSeg = SpeedSegment(
                id = "speed-seg-1",
                startSeconds = 1.0,
                endSeconds = 3.0,
                speedFactor = 2.0
            )

            val settings = HudSettings(
                exportResolution = "360p",
                speedSegments = listOf(speedSeg)
            )

            val renderedFrames = mutableListOf<java.awt.image.BufferedImage>()

            val encoder = NativeHudEncoder(settings,
                onProgress = { prog, status ->
                    println("Encoding speed Progress: ${(prog * 100).toInt()}% - $status")
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

            val fitEpochSec = 631065600L
            val computedStartUtc = java.time.Instant.ofEpochSecond(baseFitTimestamp + fitEpochSec).toString()

            encoder.encode(
                fitPath = inputFit.absolutePath,
                videoPath = inputMp4.absolutePath,
                output = outputMp4.absolutePath,
                startUtc = computedStartUtc,
                maxDurationSeconds = 6,
                trimStartSeconds = 0.0,
                trimEndSeconds = 6.0
            )

            assertTrue(outputMp4.exists(), "Output video file must be generated")
            assertTrue(outputMp4.length() > 0, "Output video file must not be empty")
            
            val frameCount = renderedFrames.size
            println("📷 Sped-up video rendered frames: $frameCount (expected: 50)")
            assertTrue(frameCount in 48..52, "Sped-up output frame count should be around 50, but was $frameCount")

        } catch (e: Exception) {
            val workDir = File(tempDir, "temp_work")
            val logFile = File(workDir, "ffmpeg_log.txt")
            if (logFile.exists()) {
                println("=== FFMPEG LOG ===")
                println(logFile.readText())
                println("==================")
            } else {
                val fallbackLog = File(File(System.getProperty("user.dir")).parentFile ?: File(System.getProperty("user.dir")), "temp_work/ffmpeg_log.txt")
                if (fallbackLog.exists()) {
                    println("=== FFMPEG LOG (FALLBACK) ===")
                    println(fallbackLog.readText())
                    println("=============================")
                }
            }
            throw e
        } finally {
            try { inputFit.delete() } catch (e: Exception) {}
            try { inputMp4.delete() } catch (e: Exception) {}
            try { outputMp4.delete() } catch (e: Exception) {}
            try { tempDir.deleteRecursively() } catch (e: Exception) {}
        }
    }

    private fun createProfileFit(baseFitTimestamp: Long, seconds: Int): ByteArray {
        val headerSize = 14
        val recordsSize = 12 + (9 * seconds)
        val totalSize = headerSize + recordsSize + 2
        val bytes = ByteArray(totalSize)

        bytes[0] = headerSize.toByte()
        bytes[1] = 32
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        bytes[4] = (recordsSize and 0xFF).toByte()
        bytes[5] = ((recordsSize shr 8) and 0xFF).toByte()
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x00.toByte()
        ".FIT".encodeToByteArray().copyInto(bytes, 8)

        var offset = headerSize
        bytes[offset] = 0x40.toByte()
        bytes[offset + 1] = 0
        bytes[offset + 2] = 0
        bytes[offset + 3] = 0x14.toByte()
        bytes[offset + 4] = 0x00.toByte()
        bytes[offset + 5] = 2
        bytes[offset + 6] = 253.toByte()
        bytes[offset + 7] = 4.toByte()
        bytes[offset + 8] = 0x86.toByte()
        bytes[offset + 9] = 5.toByte()
        bytes[offset + 10] = 4.toByte()
        bytes[offset + 11] = 0x86.toByte()
        offset += 12

        for (t in 0 until seconds) {
            bytes[offset] = 0x00.toByte()
            val ts = baseFitTimestamp + t
            bytes[offset + 1] = (ts and 0xFF).toByte()
            bytes[offset + 2] = ((ts shr 8) and 0xFF).toByte()
            bytes[offset + 3] = ((ts shr 16) and 0xFF).toByte()
            bytes[offset + 4] = ((ts shr 24) and 0xFF).toByte()
            val dist = 10000 + t * 10
            bytes[offset + 5] = (dist and 0xFF).toByte()
            bytes[offset + 6] = ((dist shr 8) and 0xFF).toByte()
            bytes[offset + 7] = ((dist shr 16) and 0xFF).toByte()
            bytes[offset + 8] = ((dist shr 24) and 0xFF).toByte()
            offset += 9
        }

        val computedCrc = Crc16.calculate(bytes, offset = 0, length = totalSize - 2)
        bytes[totalSize - 2] = (computedCrc and 0xFF).toByte()
        bytes[totalSize - 1] = ((computedCrc shr 8) and 0xFF).toByte()
        return bytes
    }
}
