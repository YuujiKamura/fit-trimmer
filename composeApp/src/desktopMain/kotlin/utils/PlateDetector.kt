package utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Graphics2D
import java.awt.Image
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
        session = env.createSession(modelBytes)
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
        
        val inputData = FloatArray(1 * 3 * 640 * 640)
        var idx = 0
        for (c in 0 until 3) {
            for (y in 0 until 640) {
                for (x in 0 until 640) {
                    val rgb = resized.getRGB(x, y)
                    val r = ((rgb shr 16) and 0xFF) / 255.0f
                    val g = ((rgb shr 8) and 0xFF) / 255.0f
                    val b = (rgb and 0xFF) / 255.0f
                    
                    val pixelVal = when (c) {
                        0 -> r
                        1 -> g
                        2 -> b
                        else -> 0.0f
                    }
                    inputData[idx++] = pixelVal
                }
            }
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
        val resultingImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = resultingImage.createGraphics()
        g.drawImage(originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH), 0, 0, null)
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
