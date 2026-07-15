package proto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import fit.LetterboxTransformer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class ProtoBox(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val classId: Int)

/**
 * 独立したプロトタイプ: Kotlin版 Privacy Blur
 * Python版 (video-privacy-blur) のアルゴリズムを完全トレースし、さらに発展させる。
 */
class PrivacyBlurProto(
    private val modelPath: String = "/PlateYOLO-JP-640x640.onnx",
    private val inputSize: Int = 960, // Python版のデフォルト imgsize
    private val confThreshold: Float = 0.35f, // Python版のデフォルト conf
    private val scale: Float = 1.35f // Python版のデフォルト scale
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelStream = PrivacyBlurProto::class.java.getResourceAsStream(modelPath)
            ?: throw IllegalStateException("Model not found: $modelPath")
        val modelBytes = modelStream.use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        opts.setInterOpNumThreads(4)
        session = env.createSession(modelBytes, opts)
    }

    /**
     * BBox の拡張 (Python版の `expand_box` に相当)
     */
    private fun expandBox(box: ProtoBox, imgW: Int, imgH: Int): ProtoBox {
        val w = box.w
        val h = box.h
        val cx = box.x + w / 2.0f
        val cy = box.y + h / 2.0f
        
        val newW = w * scale
        val newH = h * scale
        
        val nx1 = max(0.0f, cx - newW / 2.0f)
        val ny1 = max(0.0f, cy - newH / 2.0f)
        val nx2 = min(imgW.toFloat() - 1f, cx + newW / 2.0f)
        val ny2 = min(imgH.toFloat() - 1f, cy + newH / 2.0f)
        
        return ProtoBox(
            x = nx1,
            y = ny1,
            w = nx2 - nx1,
            h = ny2 - ny1,
            score = box.score,
            classId = box.classId
        )
    }

    /**
     * 推論 (Python版の `__call__` に相当)
     */
    fun detectAndExpand(image: BufferedImage): List<ProtoBox> {
        val width = image.width
        val height = image.height

        // 1. Letterbox (アスペクト比を維持してパディング)
        val transformer = LetterboxTransformer(width.toFloat(), height.toFloat(), inputSize.toFloat())
        val letterbox = BufferedImage(inputSize, inputSize, BufferedImage.TYPE_3BYTE_BGR)
        val g = letterbox.createGraphics()
        g.color = Color(114, 114, 114)
        g.fillRect(0, 0, inputSize, inputSize)
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, transformer.offsetX.toInt(), transformer.offsetY.toInt(), transformer.newW.toInt(), transformer.newH.toInt(), null)
        g.dispose()

        // 2. Extract RGB float buffer
        val inputData = FloatArray(1 * 3 * inputSize * inputSize)
        val rOffset = 0
        val gOffset = inputSize * inputSize
        val bOffset = 2 * inputSize * inputSize
        val inv255 = 1.0f / 255.0f

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

        // 3. Inference
        val inputBuffer = FloatBuffer.wrap(inputData)
        val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        
        val resultBoxes = mutableListOf<ProtoBox>()
        
        tensor.use { t ->
            session.run(mapOf("images" to t)).use { outputs ->
                val outputTensor = outputs[0] as OnnxTensor
                val floatArray = outputTensor.floatBuffer.array()
                
                // YOLOv8 output shape: [1, 4 + classes, 8400]
                val numClasses = 1 // assuming plate only for now
                val elementsPerBox = 4 + numClasses
                val numBoxes = floatArray.size / elementsPerBox

                val boxes = mutableListOf<ProtoBox>()
                for (i in 0 until numBoxes) {
                    var maxScore = 0f
                    var maxClassId = -1
                    for (c in 0 until numClasses) {
                        val score = floatArray[(4 + c) * numBoxes + i]
                        if (score > maxScore) {
                            maxScore = score
                            maxClassId = c
                        }
                    }

                    if (maxScore > confThreshold) {
                        val cx = floatArray[0 * numBoxes + i]
                        val cy = floatArray[1 * numBoxes + i]
                        val bw = floatArray[2 * numBoxes + i]
                        val bh = floatArray[3 * numBoxes + i]

                        val x1 = cx - bw / 2f
                        val y1 = cy - bh / 2f
                        val x2 = cx + bw / 2f
                        val y2 = cy + bh / 2f

                        val rx1 = transformer.toSourceX(x1.toFloat())
                        val ry1 = transformer.toSourceY(y1.toFloat())
                        val rx2 = transformer.toSourceX(x2.toFloat())
                        val ry2 = transformer.toSourceY(y2.toFloat())

                        boxes.add(ProtoBox(rx1, ry1, rx2 - rx1, ry2 - ry1, maxScore, maxClassId))
                    }
                }
                
                // 4. NMS (簡易実装)
                boxes.sortByDescending { it.score }
                val kept = mutableListOf<ProtoBox>()
                for (box in boxes) {
                    var shouldKeep = true
                    for (k in kept) {
                        if (iou(box, k) > 0.45f) { // NMS threshold
                            shouldKeep = false
                            break
                        }
                    }
                    if (shouldKeep) {
                        kept.add(box)
                    }
                }
                
                // 5. Expand Box
                for (box in kept) {
                    resultBoxes.add(expandBox(box, width, height))
                }
            }
        }
        
        return resultBoxes
    }

    private fun iou(a: ProtoBox, b: ProtoBox): Float {
        val ax1 = a.x; val ay1 = a.y; val ax2 = a.x + a.w; val ay2 = a.y + a.h
        val bx1 = b.x; val by1 = b.y; val bx2 = b.x + b.w; val by2 = b.y + b.h
        val interX1 = max(ax1, bx1)
        val interY1 = max(ay1, by1)
        val interX2 = min(ax2, bx2)
        val interY2 = min(ay2, by2)
        val iw = max(0f, interX2 - interX1)
        val ih = max(0f, interY2 - interY1)
        val inter = iw * ih
        val areaA = max(0f, ax2 - ax1) * max(0f, ay2 - ay1)
        val areaB = max(0f, bx2 - bx1) * max(0f, by2 - by1)
        val denom = areaA + areaB - inter + 1e-6f
        return inter / denom
    }

    override fun close() {
        session.close()
    }
}
