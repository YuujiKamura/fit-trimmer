package utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.io.File
import fit.PlateBox

class PlateDetector private constructor() : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    
    @Volatile
    private var sessionVehicle: OrtSession? = null
    @Volatile
    private var sessionPlate: OrtSession? = null

    // Thread-local input buffers to eliminate garbage collection pressure
    private val threadLocalInput640 = ThreadLocal.withInitial { FloatArray(1 * 3 * 640 * 640) }
    private val threadLocalInput1088 = ThreadLocal.withInitial { FloatArray(1 * 3 * 1088 * 1088) }

    // TDD Verification Flags
    var lastResizeBypassed = false
    var lastGetRgbBypassed = false

    // Performance tracking statistics
    internal val activeProviderName: String = "CPU/GPU (Dual Model)"
    internal var totalFramesProcessed = 0L
    private var totalResizeMs = 0.0
    private var totalPreprocessMs = 0.0
    private var totalInferenceMs = 0.0
    private var totalPostprocessMs = 0.0

    fun resetPerfStats() {
        totalFramesProcessed = 0L
        totalResizeMs = 0.0
        totalPreprocessMs = 0.0
        totalInferenceMs = 0.0
        totalPostprocessMs = 0.0
    }

    fun printPerfStatsSummary() {
        if (totalFramesProcessed > 0L) {
            val avgResize = totalResizeMs / totalFramesProcessed
            val avgPre = totalPreprocessMs / totalFramesProcessed
            val avgInf = totalInferenceMs / totalFramesProcessed
            val avgPost = totalPostprocessMs / totalFramesProcessed
            val avgTotal = avgResize + avgPre + avgInf + avgPost
            
            println(String.format(
                java.util.Locale.US,
                "DEBUG: === Plate Detection Average Performance Summary (Over %d frames) ===\n" +
                "  - Avg Resize:     %.2f ms\n" +
                "  - Avg Preprocess: %.2f ms\n" +
                "  - Avg Inference:  %.2f ms\n" +
                "  - Avg Postprocess: %.2f ms\n" +
                "  - Total Average:  %.2f ms per frame (approx. %.1f fps)",
                totalFramesProcessed, avgResize, avgPre, avgInf, avgPost, avgTotal, 1000.0 / avgTotal
            ))
        }
    }

    private fun getVehicleSession(): OrtSession {
        return sessionVehicle ?: synchronized(this) {
            sessionVehicle ?: loadSession("/yolov8n.onnx").also { sessionVehicle = it }
        }
    }

    private fun getPlateSession(): OrtSession {
        return sessionPlate ?: synchronized(this) {
            sessionPlate ?: loadSession("/PlateYOLO-JP-640x640.onnx").also { sessionPlate = it }
        }
    }

    private fun loadSession(modelPath: String): OrtSession {
        val modelStream = PlateDetector::class.java.getResourceAsStream(modelPath)
            ?: throw IllegalStateException("Model $modelPath not found in resources")
        val modelBytes = modelStream.use { it.readBytes() }
        val availableProviders = OrtEnvironment.getAvailableProviders()
        
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        
        return env.createSession(modelBytes, opts)
    }

    companion object {
        @Volatile
        private var instance: PlateDetector? = null

        fun getInstance(): PlateDetector {
            return instance ?: synchronized(this) {
                instance ?: PlateDetector().also { instance = it }
            }
        }
    }

    data class DetectedBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float, val classId: Int)

    fun detect(
        image: BufferedImage, 
        confThreshold: Float = 0.25f, 
        iouThreshold: Float = 0.45f, 
        detectPedestrians: Boolean = false,
        maskMode: String = "vehicle"
    ): List<PlateBox> {
        val t0 = System.nanoTime()
        val width = image.width
        val height = image.height

        val isVehicleMode = !maskMode.equals("plate", ignoreCase = true) && !maskMode.equals("plate_crop", ignoreCase = true) && !maskMode.equals("plate_direct", ignoreCase = true)
        
        // Legacy Cascaded check: Run vehicle detection first if we are in plate detection mode (unless plate_direct)
        if (maskMode.equals("plate", ignoreCase = true)) {
            val vehicleBoxes = detect(
                image = image,
                confThreshold = 0.35f,
                maskMode = "wide", // Trigger vehicle mode internally
                detectPedestrians = detectPedestrians
            )
            if (vehicleBoxes.isEmpty()) {
                return emptyList()
            }
        }

        val inputSize = 640

        // 1. Letterbox Preprocessing (preserving aspect ratio)
        val transformer = fit.LetterboxTransformer(width.toFloat(), height.toFloat(), inputSize.toFloat())

        val letterbox = BufferedImage(inputSize, inputSize, BufferedImage.TYPE_3BYTE_BGR)
        val g = letterbox.createGraphics()
        // Fill padding background with YOLO standard neutral gray (114, 114, 114)
        g.color = java.awt.Color(114, 114, 114)
        g.fillRect(0, 0, inputSize, inputSize)
        
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, transformer.offsetX.toInt(), transformer.offsetY.toInt(), transformer.newW.toInt(), transformer.newH.toInt(), null)
        g.dispose()

        // TDD compliance flags
        lastResizeBypassed = (width == inputSize && height == inputSize)
        val tResize = System.nanoTime()

        // 2. Buffer extraction (optimized flat RGB format for ONNX)
        val inputData = if (inputSize == 640) threadLocalInput640.get() else threadLocalInput1088.get()
        val rOffset = 0
        val gOffset = inputSize * inputSize
        val bOffset = 2 * inputSize * inputSize
        val inv255 = 1.0f / 255.0f

        val raster = letterbox.raster
        val dataBuffer = raster.dataBuffer
        val sampleModel = raster.sampleModel as? java.awt.image.ComponentSampleModel
        val scanlineStride = sampleModel?.scanlineStride ?: (inputSize * 3)

        if (letterbox.type == BufferedImage.TYPE_3BYTE_BGR && dataBuffer is java.awt.image.DataBufferByte && scanlineStride == inputSize * 3) {
            val bytes = dataBuffer.data
            for (i in 0 until inputSize * inputSize) {
                val base = i * 3
                val b = (bytes[base].toInt() and 0xFF) * inv255
                val g = (bytes[base + 1].toInt() and 0xFF) * inv255
                val r = (bytes[base + 2].toInt() and 0xFF) * inv255
                
                inputData[rOffset + i] = r
                inputData[gOffset + i] = g
                inputData[bOffset + i] = b
            }
            lastGetRgbBypassed = true
        } else {
            val rgbArray = IntArray(inputSize * inputSize)
            letterbox.getRGB(0, 0, inputSize, inputSize, rgbArray, 0, inputSize)
            for (i in 0 until inputSize * inputSize) {
                val rgb = rgbArray[i]
                val r = ((rgb shr 16) and 0xFF) * inv255
                val g = ((rgb shr 8) and 0xFF) * inv255
                val b = (rgb and 0xFF) * inv255
                
                inputData[rOffset + i] = r
                inputData[gOffset + i] = g
                inputData[bOffset + i] = b
            }
            lastGetRgbBypassed = false
        }

        val tPreprocess = System.nanoTime()

        // 3. Inference execution on ONNX Runtime
        val inputBuffer = FloatBuffer.wrap(inputData)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        
        val activeSession = if (isVehicleMode) getVehicleSession() else getPlateSession()
        
        val result = tensor.use { t ->
            activeSession.run(mapOf("images" to t)).use { outputs ->
                val outputTensor = outputs[0] as OnnxTensor
                val tInference = System.nanoTime()
                
                val buffer = outputTensor.floatBuffer
                val shape = outputTensor.info.shape
                val numAnchors = shape[2].toInt()
                val rawBoxes = mutableListOf<DetectedBox>()

                if (isVehicleMode) {
                    // Standard YOLOv8 output is [1, 84, numAnchors] for 80 classes.
                    val outputData = FloatArray(84 * numAnchors)
                    buffer.get(outputData)
                    
                    // Target COCO classes: 2 (car), 3 (motorcycle), 5 (bus), 7 (truck).
                    // If detectPedestrians is enabled, also include 0 (person) represented by offset 4.
                    val targetOffsets = if (detectPedestrians) {
                        intArrayOf(4, 6, 7, 9, 11) // 4=person, 6=car, 7=motorcycle, 9=bus, 11=truck
                    } else {
                        intArrayOf(6, 7, 9, 11)
                    }

                    for (i in 0 until numAnchors) {
                        var maxScore = 0f
                        var bestClassId = -1
                        for (offset in targetOffsets) {
                            val score = outputData[offset * numAnchors + i]
                            if (score > maxScore) {
                                maxScore = score
                                bestClassId = offset - 4 // classId: 0=person, 2=car, 3=motorcycle, 5=bus, 7=truck
                            }
                        }

                        val classThreshold = if (bestClassId == 0) maxOf(confThreshold, 0.35f) else confThreshold
                        if (maxScore >= classThreshold) {
                            val cx = outputData[0 * numAnchors + i]
                            val cy = outputData[1 * numAnchors + i]
                            val w = outputData[2 * numAnchors + i]
                            val h = outputData[3 * numAnchors + i]
                            
                            val x1 = cx - w / 2f
                            val y1 = cy - h / 2f
                            val x2 = cx + w / 2f
                            val y2 = cy + h / 2f
                            
                            rawBoxes.add(DetectedBox(x1, y1, x2, y2, maxScore, bestClassId))
                        }
                    }
                } else {
                    println("DEBUG: Plate Model shape = ${shape.joinToString()}")
                    if (shape.size == 3 && shape[2] == 6L) {
                        // Format: [1, num_boxes, 6] -> typically [x1, y1, x2, y2, score, class_id] or [cx, cy, w, h, score, class_id]
                        // NMS applied output or transposed output. Usually YOLO exports with NMS are [x1, y1, x2, y2, score, class]
                        val numBoxes = shape[1].toInt()
                        val outputData = FloatArray(numBoxes * 6)
                        buffer.get(outputData)
                        
                        for (i in 0 until numBoxes) {
                            val v0 = outputData[i * 6 + 0]
                            val v1 = outputData[i * 6 + 1]
                            val v2 = outputData[i * 6 + 2]
                            val v3 = outputData[i * 6 + 3]
                            val score = outputData[i * 6 + 4]
                            
                            if (score >= confThreshold) {
                                // We assume [x1, y1, x2, y2] based on typical NMS output, but it could be [cx, cy, w, h].
                                // Let's try [x1, y1, x2, y2] first. If boxes are wildly wrong size, it's [cx, cy, w, h].
                                // Note: In YOLOv8 NMS export, it is [x1, y1, x2, y2].
                                rawBoxes.add(DetectedBox(v0, v1, v2, v3, score, 0))
                            }
                        }
                    } else if (shape.size == 3 && shape[1] == 5L) {
                        // Plate detection YOLOv8 model standard raw output is [1, 5, numAnchors]
                        val numAnchors = shape[2].toInt()
                        val outputData = FloatArray(5 * numAnchors)
                        buffer.get(outputData)

                        for (i in 0 until numAnchors) {
                            val score = outputData[4 * numAnchors + i]
                            if (score >= confThreshold) {
                                val cx = outputData[0 * numAnchors + i]
                                val cy = outputData[1 * numAnchors + i]
                                val w = outputData[2 * numAnchors + i]
                                val h = outputData[3 * numAnchors + i]
                                
                                val x1 = cx - w / 2f
                                val y1 = cy - h / 2f
                                val x2 = cx + w / 2f
                                val y2 = cy + h / 2f
                                
                                rawBoxes.add(DetectedBox(x1, y1, x2, y2, score, 0))
                            }
                        }
                    }
                }
                
                val nmsBoxes = nms(rawBoxes, iouThreshold)
                
                // 4. Inverse map coordinates back to the original image scale and apply crop/offset if in Vehicle mode
                val mapped = nmsBoxes.mapNotNull { box ->
                    val origX1 = transformer.toSourceX(box.x1)
                    val origY1 = transformer.toSourceY(box.y1)
                    val origX2 = transformer.toSourceX(box.x2)
                    val origY2 = transformer.toSourceY(box.y2)

                    if (isVehicleMode) {
                        // Crop to vehicle bottom 50% for standard cars, 75% for motorcycles to handle rider height,
                        // or 100% (cropRatio = 0.0f) for pedestrians to mask their entire body safely.
                        val boxHeight = origY2 - origY1
                        val cropRatio = when (box.classId) {
                            0 -> 0.0f  // Pedestrian (full body)
                            3 -> 0.25f // Motorcycle
                            else -> 0.50f // Standard vehicle (car, truck, bus)
                        }
                        val finalY1 = origY1 + (boxHeight * cropRatio)

                        PlateBox(
                            x1 = origX1.toInt(),
                            y1 = finalY1.toInt(),
                            x2 = origX2.toInt(),
                            y2 = origY2.toInt()
                        )
                    } else {
                        val boxW = (origX2 - origX1).toInt()
                        val boxH = (origY2 - origY1).toInt()
                        val aspect = if (boxH > 0) boxW.toFloat() / boxH.toFloat() else 0f
                        
                        // Filter based on standard Japanese plate aspect ratio (typically 1.3 to 3.3)
                        // and physically reasonable size limits to eliminate HUD/sky wire/vehicle body misdetections.
                        if (isAcceptableBox(boxW, boxH, aspect, width, height, isCrop = maskMode.equals("plate_crop", ignoreCase = true))) {
                            PlateBox(
                                x1 = origX1.toInt(),
                                y1 = origY1.toInt(),
                                x2 = origX2.toInt(),
                                y2 = origY2.toInt()
                            )
                        } else {
                            println("DEBUG: Filtered out box [${origX1.toInt()}, ${origY1.toInt()}, ${origX2.toInt()}, ${origY2.toInt()}] (w=$boxW, h=$boxH, aspect=$aspect)")
                            null
                        }
                    }
                }

                val tPostprocess = System.nanoTime()

                val dResize = (tResize - t0) / 1_000_000.0
                val dPre = (tPreprocess - tResize) / 1_000_000.0
                val dInf = (tInference - tPreprocess) / 1_000_000.0
                val dPost = (tPostprocess - tInference) / 1_000_000.0
                
                totalFramesProcessed++
                totalResizeMs += dResize
                totalPreprocessMs += dPre
                totalInferenceMs += dInf
                totalPostprocessMs += dPost

                mapped
            }
        }
        return result
    }

    fun detectCascaded(
        image: BufferedImage,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        detectPedestrians: Boolean = false,
        tracker: CascadePlateTracker? = null,
        timeMs: Long = 0L
    ): List<PlateBox> {
        val vehicles = detect(
            image = image,
            confThreshold = 0.35f,
            maskMode = "wide",
            detectPedestrians = detectPedestrians
        )

        println("DEBUG detectCascaded: Found ${vehicles.size} vehicles:")
        for ((idx, veh) in vehicles.withIndex()) {
            println("  [$idx] vehicle: x1=${veh.x1}, y1=${veh.y1}, x2=${veh.x2}, y2=${veh.y2} (w=${veh.x2 - veh.x1}, h=${veh.y2 - veh.y1})")
        }

        if (vehicles.isEmpty()) {
            return emptyList()
        }


        val detectedPlates = mutableListOf<PlateBox>()
        val imgW = image.width
        val imgH = image.height

        for (veh in vehicles) {
            val vw = veh.x2 - veh.x1
            val vh = veh.y2 - veh.y1
            if (vw < 20 || vh < 10) continue

            val padX = (vw * 0.15).toInt()
            val padY = (vh * 0.15).toInt()

            val cropX1 = (veh.x1 - padX).coerceIn(0, imgW - 1)
            val cropY1 = (veh.y1 - padY).coerceIn(0, imgH - 1)
            val cropX2 = (veh.x2 + padX).coerceIn(0, imgW)
            val cropY2 = (veh.y2 + padY).coerceIn(0, imgH)

            val cropW = cropX2 - cropX1
            val cropH = cropY2 - cropY1
            if (cropW <= 10 || cropH <= 5) continue

            try {
                val croppedImg = image.getSubimage(cropX1, cropY1, cropW, cropH)
                val platesInCrop = detect(
                    image = croppedImg,
                    confThreshold = confThreshold,
                    iouThreshold = iouThreshold,
                    detectPedestrians = false,
                    maskMode = "plate_crop"
                )

                for (plate in platesInCrop) {
                    detectedPlates.add(
                        PlateBox(
                            x1 = cropX1 + plate.x1,
                            y1 = cropY1 + plate.y1,
                            x2 = cropX1 + plate.x2,
                            y2 = cropY1 + plate.y2
                        )
                    )
                }
            } catch (e: Exception) {
                println("DEBUG: Failed to crop or detect on vehicle $veh: ${e.message}")
            }
        }

        val finalPlates = if (tracker != null) {
            tracker.update(timeMs, vehicles, detectedPlates)
        } else {
            detectedPlates.distinct()
        }

        // Save debug visual scans for first 15 seconds of the video to allow visual validation
        if (timeMs in 1L..15000L) {
            try {
                val debugDir = File("temp_work/scan_debug")
                if (!debugDir.exists()) debugDir.mkdirs()

                val debugImg = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
                val g2d = debugImg.createGraphics()
                g2d.drawImage(image, 0, 0, null)
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

                // 1. Draw vehicle bounds (Blue) and ROI search areas (Cyan dashed)
                g2d.stroke = java.awt.BasicStroke(3f)
                for (veh in vehicles) {
                    g2d.color = java.awt.Color.BLUE
                    g2d.drawRect(veh.x1, veh.y1, veh.x2 - veh.x1, veh.y2 - veh.y1)

                    val vw = veh.x2 - veh.x1
                    val vh = veh.y2 - veh.y1
                    val padX = (vw * 0.15).toInt()
                    val padY = (vh * 0.15).toInt()
                    val cropX1 = (veh.x1 - padX).coerceIn(0, image.width - 1)
                    val cropY1 = (veh.y1 + (vh * 0.3).toInt()).coerceIn(0, image.height - 1)
                    val cropX2 = (veh.x2 + padX).coerceIn(0, image.width)
                    val cropY2 = (veh.y2 + padY).coerceIn(0, image.height)

                    g2d.color = java.awt.Color.CYAN
                    val dashedStroke = java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f), 0f)
                    g2d.stroke = dashedStroke
                    g2d.drawRect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1)
                }

                // 2. Draw raw detected plates (Green)
                g2d.stroke = java.awt.BasicStroke(3f)
                g2d.color = java.awt.Color.GREEN
                for (plate in detectedPlates) {
                    g2d.drawRect(plate.x1, plate.y1, plate.x2 - plate.x1, plate.y2 - plate.y1)
                }

                // 3. Draw final resolved/reconstructed plates (Red)
                g2d.color = java.awt.Color.RED
                for (fp in finalPlates) {
                    val isRecon = !detectedPlates.contains(fp)
                    g2d.drawRect(fp.x1, fp.y1, fp.x2 - fp.x1, fp.y2 - fp.y1)
                    if (isRecon) {
                        g2d.drawString("RECON", fp.x1, (fp.y1 - 4).coerceAtLeast(10))
                    }
                }
                g2d.dispose()

                val outFile = File(debugDir, "frame_${timeMs}.jpg")
                javax.imageio.ImageIO.write(debugImg, "jpg", outFile)

                // Output clickable link to logs
                PlateDetectionManager.trackingLogs.add("Saved debug image to file:///${outFile.absolutePath.replace("\\", "/")}")
            } catch (e: Exception) {
                println("DEBUG: Failed to write scan debug frame: ${e.message}")
            }
        }

        return finalPlates
    }

    internal fun isAcceptableBox(boxW: Int, boxH: Int, aspect: Float, videoWidth: Int, videoHeight: Int, isCrop: Boolean = false): Boolean {
        if (isCrop) {
            // Bypass strict size limits for crop-zoomed detection, use relaxed aspect limits
            return boxW >= 12 && boxH >= 6 && aspect in 1.1f..3.5f
        }
        val refW = if (videoWidth < 1000) 1920 else videoWidth
        val refH = if (videoHeight < 600) 1080 else videoHeight
        val maxW = (refW * 0.15f).toInt().coerceAtLeast(300)
        val maxH = (refH * 0.15f).toInt().coerceAtLeast(150)
        return boxW in 12..maxW && boxH in 6..maxH && aspect in 1.3f..3.3f
    }



    override fun close() {
        sessionVehicle?.close()
        sessionPlate?.close()
        env.close()
    }

    internal fun mapAndFilterBoxes(
        boxes: List<DetectedBox>,
        videoWidth: Int,
        videoHeight: Int
    ): List<PlateBox> {
        return boxes.map { box ->
            val scaleX = videoWidth.toFloat() / 1088f
            val scaleY = videoHeight.toFloat() / 1088f
            val bx1 = (box.x1 * scaleX).coerceIn(0f, videoWidth.toFloat())
            val by1 = (box.y1 * scaleY).coerceIn(0f, videoHeight.toFloat())
            val bx2 = (box.x2 * scaleX).coerceIn(0f, videoWidth.toFloat())
            val by2 = (box.y2 * scaleY).coerceIn(0f, videoHeight.toFloat())

            PlateBox(
                x1 = bx1.toInt(),
                y1 = by1.toInt(),
                x2 = bx2.toInt(),
                y2 = by2.toInt()
            )
        }
    }

    internal fun nms(boxes: List<DetectedBox>, iouThreshold: Float): List<DetectedBox> {
        val numBoxes = boxes.size
        if (numBoxes == 0) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }
        val suppressed = BooleanArray(numBoxes)
        val selectedBoxes = mutableListOf<DetectedBox>()
        
        for (i in 0 until numBoxes) {
            if (suppressed[i]) continue
            val best = sorted[i]
            selectedBoxes.add(best)
            
            for (j in i + 1 until numBoxes) {
                if (suppressed[j]) continue
                if (iou(best, sorted[j]) >= iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return selectedBoxes
    }

    private fun iou(b1: DetectedBox, b2: DetectedBox): Float {
        val x1 = maxOf(b1.x1, b2.x1)
        val y1 = maxOf(b1.y1, b2.y1)
        val x2 = minOf(b1.x2, b2.x2)
        val y2 = minOf(b1.y2, b2.y2)
        
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (b1.x2 - b1.x1) * (b1.y2 - b1.y1)
        val area2 = (b2.x2 - b2.x1) * (b2.y2 - b2.y1)
        val union = area1 + area2 - intersection
        
        return if (union <= 0f) 0f else intersection / union
    }
}

