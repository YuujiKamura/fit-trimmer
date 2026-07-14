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
    private val session: OrtSession

    // Thread-local buffer reuse to eliminate garbage collection pressure on large scans
    private val threadLocalInputData = ThreadLocal.withInitial { FloatArray(1 * 3 * 1088 * 1088) }

    // TDD Verification Flags
    var lastResizeBypassed = false
    var lastGetRgbBypassed = false

    // Performance tracking statistics
    internal val activeProviderName: String
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

    init {
        val modelStream = PlateDetector::class.java.getResourceAsStream("/yolov8n_plate.onnx")
            ?: throw IllegalStateException("Model yolov8n.onnx not found in resources")
        val modelBytes = modelStream.use { it.readBytes() }
        
        val availableProviders = OrtEnvironment.getAvailableProviders()
        println("DEBUG: ONNX Runtime available execution providers: $availableProviders")

        val opts = OrtSession.SessionOptions()
        // Single model inference achieves best CPU latency and lowest context switching overhead
        // when using 1 inter-op thread and a small number of intra-op threads.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Allow up to 8 threads (or half of CPU cores) for faster parallel ONNX execution on multi-core host
        val intraThreads = (cores / 2).coerceIn(1, 8)
        opts.setIntraOpNumThreads(intraThreads)
        opts.setInterOpNumThreads(1)
        
        var selectedProvider = "CPU"
        try {
            val hasCuda = availableProviders.any { it.toString().equals("CUDA", ignoreCase = true) }
            val hasDml = availableProviders.any { it.toString().equals("DIRECTML", ignoreCase = true) }
            
            if (hasCuda) {
                opts.addCUDA(0)
                selectedProvider = "GPU (CUDA)"
            } else if (hasDml) {
                opts.addDirectML(0)
                selectedProvider = "GPU (DirectML)"
            }
        } catch (e: Exception) {
            println("WARNING: Failed to initialize GPU execution provider: ${e.message}. Falling back to CPU.")
            selectedProvider = "CPU (Fallback)"
        }

        session = env.createSession(modelBytes, opts)
        activeProviderName = selectedProvider
        println("DEBUG-META: Input Metadata = ${session.inputInfo.map { "${it.key} => ${it.value.toString()}" }}")
        println("DEBUG-META: Output Metadata = ${session.outputInfo.map { "${it.key} => ${it.value.toString()}" }}")
        println("DEBUG: ONNX session initialized successfully with provider: $activeProviderName")
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

    fun detect(image: BufferedImage, confThreshold: Float = 0.25f, iouThreshold: Float = 0.45f, detectPedestrians: Boolean = false): List<PlateBox> {
        val t0 = System.nanoTime()
        val width = image.width
        val height = image.height

        val resized: BufferedImage
        if (width == 1088 && height == 1088) {
            resized = image
            lastResizeBypassed = true
        } else {
            resized = resizeImage(image, 1088, 1088)
            lastResizeBypassed = false
        }
        val tResize = System.nanoTime()

        val inputData = threadLocalInputData.get()
        val rOffset = 2 * 1088 * 1088
        val gOffset = 1088 * 1088
        val bOffset = 0
        val inv255 = 1.0f / 255.0f

        val raster = resized.raster
        val dataBuffer = raster.dataBuffer
        if (false && resized.type == BufferedImage.TYPE_3BYTE_BGR && dataBuffer is java.awt.image.DataBufferByte) {
            val bytes = dataBuffer.data
            for (i in 0 until 1088 * 1088) {
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
            val rgbArray = IntArray(1088 * 1088)
            resized.getRGB(0, 0, 1088, 1088, rgbArray, 0, 1088)
            for (i in 0 until 1088 * 1088) {
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
        // Debug: Save the preprocessed resized image to temp_work to visually audit what is fed to YOLO
        val debugOutFile = java.io.File(fit.PathResolver.getTempWorkDir(), "yolo_input_debug.jpg")
        if (!debugOutFile.exists()) {
            try {
                debugOutFile.parentFile?.mkdirs()
                javax.imageio.ImageIO.write(resized, "jpg", debugOutFile)
                println("📸 Wrote YOLO input debug frame to: ${debugOutFile.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val tPreprocess = System.nanoTime()

        val inputBuffer = FloatBuffer.wrap(inputData)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, 1088, 1088))
        
        val result = tensor.use { t ->
            session.run(mapOf("images" to t)).use { outputs ->
                val outputTensor = outputs[0] as OnnxTensor
                val tInference = System.nanoTime()
                println("DEBUG-SHAPE: outputTensor shape = ${outputTensor.info.shape.joinToString()}")
                
                // Plate detection YOLOv8 model output is [1, 5, 24276] for 1 class (license-plate).
                // Bulk copy the output data into a JVM heap array in a single operation
                // to eliminate DirectBuffer.get(index) JNI boundary checking overhead inside the loop.
                val buffer = outputTensor.floatBuffer
                val outputData = FloatArray(5 * 24276)
                buffer.get(outputData)
                
                val boxes = mutableListOf<DetectedBox>()
                var printed = 0

                for (i in 0 until 24276) {
                    val score = outputData[4 * 24276 + i]
                    if (score >= confThreshold) {
                        val cx = outputData[0 * 24276 + i]
                        val cy = outputData[1 * 24276 + i]
                        val w = outputData[2 * 24276 + i]
                        val h = outputData[3 * 24276 + i]
                        
                        val x1 = cx - w / 2f
                        val y1 = cy - h / 2f
                        val x2 = cx + w / 2f
                        val y2 = cy + h / 2f
                        
                        if (printed < 5) {
                            println("DEBUG-BOX: raw index $i, score=$score, cx=$cx, cy=$cy, w=$w, h=$h -> [$x1, $y1, $x2, $y2]")
                            printed++
                        }
                        boxes.add(DetectedBox(x1, y1, x2, y2, score, 0))
                    }
                }
                if (printed > 0) {
                    println("DEBUG-BOX: Total detected raw boxes before NMS = ${boxes.size}")
                }
                
                val nmsBoxes = nms(boxes, iouThreshold)
                val mapped = mapAndFilterBoxes(nmsBoxes, width, height)
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

                if (totalFramesProcessed % 100 == 0L || totalFramesProcessed <= 5L) {
                    val avgResize = totalResizeMs / totalFramesProcessed
                    val avgPre = totalPreprocessMs / totalFramesProcessed
                    val avgInf = totalInferenceMs / totalFramesProcessed
                    val avgPost = totalPostprocessMs / totalFramesProcessed
                    val avgTotal = avgResize + avgPre + avgInf + avgPost
                    println(
                        "DEBUG: YOLO Scan Stats [Frame $totalFramesProcessed] - " +
                        "Avg: resize=%.2fms, preprocess=%.2fms, inference=%.2fms, postprocess=%.2fms | " +
                        "Avg total=%.2fms (%.1f fps)".format(avgResize, avgPre, avgInf, avgPost, avgTotal, 1000.0 / avgTotal)
                    )
                }
                mapped
            }
        }
        return result
    }

    private fun resizeImage(originalImage: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        val resultingImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR)
        val g: Graphics2D = resultingImage.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
        g.dispose()
        return resultingImage
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
        
        // Sort boxes directly to avoid index boxing and lookup cache misses
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

    override fun close() {
        session.close()
        env.close()
    }
}
