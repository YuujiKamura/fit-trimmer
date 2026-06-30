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
        val width = image.width
        val height = image.height

        val resized = resizeImage(image, 640, 640)
        
        val rgbArray = IntArray(640 * 640)
        resized.getRGB(0, 0, 640, 640, rgbArray, 0, 640)
        
        val inputData = FloatArray(1 * 3 * 640 * 640)
        val rOffset = 0
        val gOffset = 640 * 640
        val bOffset = 2 * 640 * 640
        
        for (i in 0 until 640 * 640) {
            val rgb = rgbArray[i]
            val r = ((rgb shr 16) and 0xFF) / 255.0f
            val g = ((rgb shr 8) and 0xFF) / 255.0f
            val b = (rgb and 0xFF) / 255.0f
            
            inputData[rOffset + i] = r
            inputData[gOffset + i] = g
            inputData[bOffset + i] = b
        }

        val inputBuffer = FloatBuffer.wrap(inputData)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, 640, 640))
        
        val outputs = session.run(mapOf("images" to tensor))
        val outputTensor = outputs[0] as OnnxTensor
        
        // YOLOv8 output is [1, 5, 8400]
        val outputData = outputTensor.value as Array<Array<FloatArray>>
        val pred = outputData[0] // [5, 8400]
        
        val boxes = mutableListOf<DetectedBox>()
        for (i in 0 until 8400) {
            val score = pred[4][i]
            if (score >= confThreshold) {
                val cx = pred[0][i]
                val cy = pred[1][i]
                val w = pred[2][i]
                val h = pred[3][i]
                
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                
                boxes.add(DetectedBox(x1, y1, x2, y2, score))
            }
        }
        
        val nmsBoxes = nms(boxes, iouThreshold)
        
        return nmsBoxes.map { box ->
            val scaleX = width.toFloat() / 640f
            val scaleY = height.toFloat() / 640f
            val x1 = (box.x1 * scaleX).toInt().coerceAtLeast(0)
            val y1 = (box.y1 * scaleY).toInt().coerceAtLeast(0)
            val x2 = (box.x2 * scaleX).toInt().coerceAtMost(width)
            val y2 = (box.y2 * scaleY).toInt().coerceAtMost(height)
            PlateBox(x1, y1, x2, y2)
        }
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
