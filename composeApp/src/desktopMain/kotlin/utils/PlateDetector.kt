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

    // TDD Verification Flags
    var lastResizeBypassed = false
    var lastGetRgbBypassed = false

    // Performance tracking statistics
    private var totalFramesProcessed = 0L
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
            ?: throw IllegalStateException("Model yolov8n_plate.onnx not found in resources")
        val modelBytes = modelStream.use { it.readBytes() }
        
        val opts = OrtSession.SessionOptions()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val threads = (cores / 2).coerceIn(2, 4) // Target 2 to 4 threads for optimal hardware throughput
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(threads)
        
        session = env.createSession(modelBytes, opts)
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

    data class DetectedBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

    fun detect(image: BufferedImage, confThreshold: Float = 0.25f, iouThreshold: Float = 0.45f): List<PlateBox> {
        val t0 = System.nanoTime()
        val width = image.width
        val height = image.height

        val resized: BufferedImage
        if (width == 640 && height == 640) {
            resized = image
            lastResizeBypassed = true
        } else {
            resized = resizeImage(image, 640, 640)
            lastResizeBypassed = false
        }
        val tResize = System.nanoTime()

        val inputData = FloatArray(1 * 3 * 640 * 640)
        val rOffset = 0
        val gOffset = 640 * 640
        val bOffset = 2 * 640 * 640
        val inv255 = 1.0f / 255.0f

        val raster = resized.raster
        val dataBuffer = raster.dataBuffer
        if (resized.type == BufferedImage.TYPE_3BYTE_BGR && dataBuffer is java.awt.image.DataBufferByte) {
            val bytes = dataBuffer.data
            for (i in 0 until 640 * 640) {
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
            val rgbArray = IntArray(640 * 640)
            resized.getRGB(0, 0, 640, 640, rgbArray, 0, 640)
            for (i in 0 until 640 * 640) {
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

        val inputBuffer = FloatBuffer.wrap(inputData)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, 640, 640))
        
        val result = tensor.use { t ->
            session.run(mapOf("images" to t)).use { outputs ->
                val outputTensor = outputs[0] as OnnxTensor
                val tInference = System.nanoTime()
                
                // YOLOv8 output is [1, 5, 8400]
                // Access direct FloatBuffer to prevent allocating 8400 FloatArray objects per frame,
                // which causes severe GC pressure and stalls on long video scans.
                val buffer = outputTensor.floatBuffer
                
                val boxes = mutableListOf<DetectedBox>()
                for (i in 0 until 8400) {
                    val score = buffer.get(4 * 8400 + i)
                    if (score >= confThreshold) {
                        val cx = buffer.get(0 * 8400 + i)
                        val cy = buffer.get(1 * 8400 + i)
                        val w = buffer.get(2 * 8400 + i)
                        val h = buffer.get(3 * 8400 + i)
                        
                        val x1 = cx - w / 2f
                        val y1 = cy - h / 2f
                        val x2 = cx + w / 2f
                        val y2 = cy + h / 2f
                        
                        boxes.add(DetectedBox(x1, y1, x2, y2, score))
                    }
                }
                
                val nmsBoxes = nms(boxes, iouThreshold)
                val mapped = nmsBoxes.map { box ->
                    val scaleX = width.toFloat() / 640f
                    val scaleY = height.toFloat() / 640f
                    val x1 = (box.x1 * scaleX).toInt().coerceAtLeast(0)
                    val y1 = (box.y1 * scaleY).toInt().coerceAtLeast(0)
                    val x2 = (box.x2 * scaleX).toInt().coerceAtMost(width)
                    val y2 = (box.y2 * scaleY).toInt().coerceAtMost(height)
                    PlateBox(x1, y1, x2, y2)
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

                if (totalFramesProcessed % 100 == 0L || totalFramesProcessed <= 5L) {
                    val avgResize = totalResizeMs / totalFramesProcessed
                    val avgPre = totalPreprocessMs / totalFramesProcessed
                    val avgInf = totalInferenceMs / totalFramesProcessed
                    val avgPost = totalPostprocessMs / totalFramesProcessed
                    val avgTotal = avgResize + avgPre + avgInf + avgPost
                    
                    println(String.format(
                        java.util.Locale.US,
                        "DEBUG: YOLO Scan [Frame %d] - Avg: resize=%.2fms, preprocess=%.2fms, inference=%.2fms, postprocess=%.2fms | Avg total: %.2fms",
                        totalFramesProcessed, avgResize, avgPre, avgInf, avgPost, avgTotal
                    ))
                }
                mapped
            }
        }
        return result
    }

    private fun resizeImage(originalImage: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        var currentImg = originalImage
        var w = originalImage.width
        var h = originalImage.height
        
        // Stepwise half-downscaling (mimicking mipmaps) to prevent aliasing artifacts
        while (w > targetWidth * 2 || h > targetHeight * 2) {
            w = (w / 2).coerceAtLeast(targetWidth)
            h = (h / 2).coerceAtLeast(targetHeight)
            val nextImg = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
            val g = nextImg.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(currentImg, 0, 0, w, h, null)
            g.dispose()
            currentImg = nextImg
        }
        
        // Final resize to target size
        val resultingImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR)
        val g: Graphics2D = resultingImage.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(currentImg, 0, 0, targetWidth, targetHeight, null)
        g.dispose()
        return resultingImage
    }

    private fun nms(boxes: List<DetectedBox>, iouThreshold: Float): List<DetectedBox> {
        val sortedBoxes = boxes.sortedByDescending { it.score }.toMutableList()
        val selectedBoxes = mutableListOf<DetectedBox>()
        
        while (sortedBoxes.isNotEmpty()) {
            val best = sortedBoxes.removeAt(0)
            selectedBoxes.add(best)
            sortedBoxes.removeAll { iou(best, it) >= iouThreshold }
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
