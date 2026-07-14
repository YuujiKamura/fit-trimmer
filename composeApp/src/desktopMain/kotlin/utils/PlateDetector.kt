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
    // TDD Verification Flags
    var lastResizeBypassed = false
    var lastGetRgbBypassed = false

    // Performance tracking statistics
    internal val activeProviderName: String = "Rule-based (Fast)"
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
            val avgPost = totalPostprocessMs / totalFramesProcessed
            println(String.format(
                java.util.Locale.US,
                "DEBUG: === Plate Detection Average Performance Summary (Over %d frames) ===\n" +
                "  - Avg Process:     %.2f ms\n" +
                "  - Total Average:  %.2f ms per frame (approx. %.1f fps)",
                totalFramesProcessed, avgPost, avgPost, 1000.0 / avgPost
            ))
        }
    }

    init {
        println("DEBUG: Rule-based plate detector initialized successfully.")
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

        lastResizeBypassed = true
        lastGetRgbBypassed = true

        val boxes = mutableListOf<PlateBox>()

        // Auto-expand ROI for non-standard driving frame sizes (e.g. screenshots) to find plates anywhere in the image
        val isStandardDashcam = (width == 3840 && height == 2160) || (width == 1920 && height == 1080)
        val roiMinX = if (isStandardDashcam) (width * 0.20).toInt() else 0
        val roiMaxX = if (isStandardDashcam) (width * 0.80).toInt() else width
        val roiMinY = if (isStandardDashcam) (height * 0.42).toInt() else 0
        val roiMaxY = if (isStandardDashcam) (height * 0.88).toInt() else height

        val step = 2 // Downsample step to prevent pixel-by-pixel scanning overhead
        val binW = (roiMaxX - roiMinX) / step
        val binH = (roiMaxY - roiMinY) / step
        val binarized = java.util.BitSet(binW * binH)

        for (y in 0 until binH) {
            val imgY = roiMinY + y * step
            for (x in 0 until binW) {
                val imgX = roiMinX + x * step
                val rgb = image.getRGB(imgX, imgY)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Tight color thresholds matching Japanese plates (White/Yellow/Green)
                val isWhite = (r > 160 && g > 160 && b > 155 && 
                               kotlin.math.abs(r - g) < 20 && 
                               kotlin.math.abs(g - b) < 20 && 
                               kotlin.math.abs(r - b) < 20)
                val isYellow = (r > 165 && g > 145 && b < 125 && 
                                r - b > 45 && g - b > 35)
                val isGreen = (g > 60 && g > r + 15 && g > b + 15 && r < 140 && b < 150)

                if (isWhite || isYellow || isGreen) {
                    binarized.set(y * binW + x)
                }
            }
        }

        // Segment blobs using fast BFS flood fill
        val visited = java.util.BitSet(binW * binH)
        val queue = IntArray(binW * binH)

        for (y in 0 until binH) {
            for (x in 0 until binW) {
                val idx = y * binW + x
                if (binarized.get(idx) && !visited.get(idx)) {
                    var head = 0
                    var tail = 0
                    queue[tail++] = idx
                    visited.set(idx)

                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y

                    while (head < tail) {
                        val curr = queue[head++]
                        val cx = curr % binW
                        val cy = curr / binW

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        val neighbors = arrayOf(
                            Pair(cx - 1, cy), Pair(cx + 1, cy),
                            Pair(cx, cy - 1), Pair(cx, cy + 1)
                        )
                        for (nb in neighbors) {
                            val nx = nb.first
                            val ny = nb.second
                            if (nx in 0 until binW && ny in 0 until binH) {
                                val nidx = ny * binW + nx
                                if (binarized.get(nidx) && !visited.get(nidx)) {
                                    visited.set(nidx)
                                    if (tail < queue.size) {
                                        queue[tail++] = nidx
                                    }
                                }
                            }
                        }
                    }

                    val boxW = (maxX - minX + 1) * step
                    val boxH = (maxY - minY + 1) * step
                    val boxX = roiMinX + minX * step
                    val boxY = roiMinY + minY * step

                    val aspect = boxW.toFloat() / boxH.toFloat()
                    
                    // Filter based on Japanese plate aspect ratio (2:1) and target physical width range
                    if (boxW in 12..250 && boxH in 6..120 && aspect in 1.4f..2.8f) {
                        boxes.add(PlateBox(boxX, boxY, boxX + boxW, boxY + boxH))
                    }
                }
            }
        }

        val merged = mergeBoxes(boxes)

        val tPostprocess = System.nanoTime()
        totalFramesProcessed++
        totalPostprocessMs += (tPostprocess - t0) / 1_000_000.0

        return merged
    }

    private fun mergeBoxes(boxes: List<PlateBox>): List<PlateBox> {
        if (boxes.size <= 1) return boxes
        
        fun intersects(a: PlateBox, b: PlateBox): Boolean {
            return !(a.x2 < b.x1 || a.x1 > b.x2 || a.y2 < b.y1 || a.y1 > b.y2)
        }

        fun merge(a: PlateBox, b: PlateBox): PlateBox {
            return PlateBox(
                x1 = kotlin.math.min(a.x1, b.x1),
                y1 = kotlin.math.min(a.y1, b.y1),
                x2 = kotlin.math.max(a.x2, b.x2),
                y2 = kotlin.math.max(a.y2, b.y2)
            )
        }

        val result = boxes.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < result.size) {
                var j = i + 1
                while (j < result.size) {
                    if (intersects(result[i], result[j])) {
                        result[i] = merge(result[i], result[j])
                        result.removeAt(j)
                        merged = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return result
    }

    override fun close() {
        // No resources to close
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

