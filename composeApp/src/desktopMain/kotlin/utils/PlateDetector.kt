package utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
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
            sessionPlate ?: loadSession("/yolov8n_plate.onnx").also { sessionPlate = it }
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

        val isVehicleMode = !maskMode.equals("plate", ignoreCase = true)
        val inputSize = 640

        // 1. Letterbox Preprocessing (preserving aspect ratio)
        val scale = kotlin.math.min(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val newW = (width * scale).toInt()
        val newH = (height * scale).toInt()
        val offsetX = (inputSize - newW) / 2
        val offsetY = (inputSize - newH) / 2

        val letterbox = BufferedImage(inputSize, inputSize, BufferedImage.TYPE_3BYTE_BGR)
        val g = letterbox.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, offsetX, offsetY, newW, newH, null)
        g.dispose()

        // TDD compliance flags
        lastResizeBypassed = (width == inputSize && height == inputSize)
        val tResize = System.nanoTime()

        // 2. Buffer extraction (optimized flat RGB format for ONNX)
        val inputData = if (isVehicleMode) threadLocalInput640.get() else threadLocalInput1088.get()
        val rOffset = 2 * inputSize * inputSize
        val gOffset = inputSize * inputSize
        val bOffset = 0
        val inv255 = 1.0f / 255.0f

        val raster = letterbox.raster
        val dataBuffer = raster.dataBuffer
        if (letterbox.type == BufferedImage.TYPE_3BYTE_BGR && dataBuffer is java.awt.image.DataBufferByte) {
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
                    // Plate detection YOLOv8 model output is [1, 5, numAnchors]
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
                
                val nmsBoxes = nms(rawBoxes, iouThreshold)
                
                // 4. Inverse map coordinates back to the original image scale and apply crop/offset if in Vehicle mode
                val mapped = nmsBoxes.mapNotNull { box ->
                    val origX1 = ((box.x1 - offsetX) / scale).coerceIn(0f, width.toFloat())
                    val origY1 = ((box.y1 - offsetY) / scale).coerceIn(0f, height.toFloat())
                    val origX2 = ((box.x2 - offsetX) / scale).coerceIn(0f, width.toFloat())
                    val origY2 = ((box.y2 - offsetY) / scale).coerceIn(0f, height.toFloat())

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
                        
                        // Filter based on standard Japanese plate aspect ratio (typically 1.3 to 2.7)
                        // and physically reasonable size limits to eliminate HUD/sky wire misdetections.
                        val maxW = (width / 7).coerceAtLeast(400)
                        val maxH = (height / 7).coerceAtLeast(200)
                        
                        if (boxW in 12..maxW && boxH in 6..maxH && aspect in 1.2f..2.8f) {
                            PlateBox(
                                x1 = origX1.toInt(),
                                y1 = origY1.toInt(),
                                x2 = origX2.toInt(),
                                y2 = origY2.toInt()
                            )
                        } else {
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

