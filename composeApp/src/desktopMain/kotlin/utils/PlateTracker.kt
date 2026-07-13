package utils

import fit.PlateBox
import fit.PlateRecord
import java.awt.image.BufferedImage

data class TrackedObject(
    val id: Int,
    var lastBox: PlateBox,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var lastUpdatedFrame: Long,
    var referenceHistogram: FloatArray? = null,
    var consecutiveMissedFrames: Int = 0
)

class PlateTracker(
    val inferenceInterval: Int = 10,
    val histogramBinCount: Int = 8
) {
    internal val activeTracks = mutableListOf<TrackedObject>()
    private var nextTrackId = 1

    /**
     * Intermediate frame tracking (Forward Tracking).
     * Predicts the location of existing tracks using a motion model and refines it via HSV histogram matching.
     */
    fun trackIntermediateFrame(
        frameIndex: Long,
        timeMs: Long,
        image: BufferedImage
    ): List<PlateBox> {
        for (track in activeTracks) {
            // 1. Motion prediction
            val tw = track.lastBox.x2 - track.lastBox.x1
            val th = track.lastBox.y2 - track.lastBox.y1
            val predX1 = (track.lastBox.x1 + track.velocityX).toInt().coerceIn(0, image.width - tw)
            val predY1 = (track.lastBox.y1 + track.velocityY).toInt().coerceIn(0, image.height - th)

            val refHist = track.referenceHistogram
            if (refHist == null) {
                track.lastBox = PlateBox(predX1, predY1, predX1 + tw, predY1 + th)
                track.lastUpdatedFrame = frameIndex
                continue
            }

            // 2. HSV Local Search
            var bestBox = PlateBox(predX1, predY1, predX1 + tw, predY1 + th)
            var bestDist = Float.MAX_VALUE

            // Search neighborhood around predicted position
            // Steps of 4 pixels to maintain performance
            val searchRange = -12..12 step 4
            for (dy in searchRange) {
                for (dx in searchRange) {
                    val nx = (predX1 + dx).coerceIn(0, image.width - tw)
                    val ny = (predY1 + dy).coerceIn(0, image.height - th)
                    val candidateBox = PlateBox(nx, ny, nx + tw, ny + th)

                    val candHist = computeHsvHistogram(image, candidateBox)
                    val dist = compareHistograms(refHist, candHist)

                    if (dist < bestDist) {
                        bestDist = dist
                        bestBox = candidateBox
                    }
                }
            }

            // Update track position and reference hist (slow blend)
            track.lastBox = bestBox
            track.lastUpdatedFrame = frameIndex
            val finalHist = computeHsvHistogram(image, bestBox)
            for (i in refHist.indices) {
                refHist[i] = refHist[i] * 0.9f + finalHist[i] * 0.1f
            }
        }

        return activeTracks.map { it.lastBox }
    }

    /**
     * ONNX inference frame association.
     * Matches new detections with existing tracks, updates active tracks, and handles new tracks.
     */
    fun updateWithDetections(
        frameIndex: Long,
        timeMs: Long,
        detections: List<PlateBox>,
        image: BufferedImage
    ): List<PlateBox> {
        val matchedDetectionIndices = mutableSetOf<Int>()
        val matchedTrackIndices = mutableSetOf<Int>()

        // Associate
        for (detIdx in detections.indices) {
            val det = detections[detIdx]
            val detCx = det.x1 + (det.x2 - det.x1) / 2f
            val detCy = det.y1 + (det.y2 - det.y1) / 2f

            var bestTrackIdx = -1
            var bestDist = Float.MAX_VALUE

            val detHist = computeHsvHistogram(image, det)

            for (tIdx in activeTracks.indices) {
                if (tIdx in matchedTrackIndices) continue
                val track = activeTracks[tIdx]

                // Predict position
                val delta = (frameIndex - track.lastUpdatedFrame).toFloat()
                val predCx = (track.lastBox.x1 + (track.lastBox.x2 - track.lastBox.x1) / 2f) + track.velocityX * delta
                val predCy = (track.lastBox.y1 + (track.lastBox.y2 - track.lastBox.y1) / 2f) + track.velocityY * delta

                val distPx = kotlin.math.sqrt(
                    ((detCx - predCx) * (detCx - predCx) + (detCy - predCy) * (detCy - predCy)).toDouble()
                ).toFloat()

                // Gate check: within 150 pixels for 640x640 scan space
                if (distPx > 150f) continue

                // Compare histogram
                val refHist = track.referenceHistogram
                if (refHist == null) {
                    if (bestDist == Float.MAX_VALUE) {
                        bestTrackIdx = tIdx
                    }
                    continue
                }
                
                val histDist = compareHistograms(refHist, detHist)

                // Hybrid score: combine distance and histogram (mostly color similarity inside gate)
                if (histDist < 0.45f && histDist < bestDist) {
                    bestDist = histDist
                    bestTrackIdx = tIdx
                }
            }

            if (bestTrackIdx != -1) {
                matchedDetectionIndices.add(detIdx)
                matchedTrackIndices.add(bestTrackIdx)

                val track = activeTracks[bestTrackIdx]
                val oldCx = track.lastBox.x1 + (track.lastBox.x2 - track.lastBox.x1) / 2f
                val oldCy = track.lastBox.y1 + (track.lastBox.y2 - track.lastBox.y1) / 2f
                val delta = (frameIndex - track.lastUpdatedFrame).toFloat().coerceAtLeast(1f)

                // Update velocity
                track.velocityX = (detCx - oldCx) / delta
                track.velocityY = (detCy - oldCy) / delta

                track.lastBox = det
                track.lastUpdatedFrame = frameIndex
                track.consecutiveMissedFrames = 0

                // Blend histogram
                val ref = track.referenceHistogram
                if (ref != null) {
                    for (i in ref.indices) {
                        ref[i] = ref[i] * 0.8f + detHist[i] * 0.2f
                    }
                }
            }
        }

        // Handle unmatched detections (New Tracks)
        for (detIdx in detections.indices) {
            if (detIdx in matchedDetectionIndices) continue
            val det = detections[detIdx]
            val hist = computeHsvHistogram(image, det)
            val newTrack = TrackedObject(
                id = nextTrackId++,
                lastBox = det,
                lastUpdatedFrame = frameIndex,
                referenceHistogram = hist
            )
            activeTracks.add(newTrack)
        }

        // Handle unmatched tracks
        for (tIdx in activeTracks.indices) {
            if (tIdx in matchedTrackIndices) continue
            val track = activeTracks[tIdx]
            if (track.lastUpdatedFrame != frameIndex) {
                track.consecutiveMissedFrames++
            }
        }

        // Clean old tracks (threshold: 6 frames missed at 3fps is about 2 seconds)
        activeTracks.removeAll { it.consecutiveMissedFrames > 6 }

        return activeTracks.map { it.lastBox }
    }

    /**
     * Backtracks newly created tracks from the current frame to the screen boundaries,
     * interpolating their bounding boxes across the preceding un-inferred frames.
     */
    fun performBacktracking(
        newTrack: TrackedObject,
        fromFrameIndex: Long,
        videoWidth: Int,
        videoHeight: Int
    ): Map<Long, PlateBox> {
        if (fromFrameIndex <= 0L) return emptyMap()

        val targetBox = newTrack.lastBox
        val tw = targetBox.x2 - targetBox.x1
        val th = targetBox.y2 - targetBox.y1
        val tcx = targetBox.x1 + tw / 2
        val tcy = targetBox.y1 + th / 2

        // Find closest boundary
        val distLeft = tcx
        val distRight = videoWidth - tcx
        val distTop = tcy
        val distBottom = videoHeight - tcy

        val minDist = minOf(distLeft, distRight, distTop, distBottom)

        // Calculate start boundary box (10% scale)
        val sw = (tw * 0.1f).coerceAtLeast(1f).toInt()
        val sh = (th * 0.1f).coerceAtLeast(1f).toInt()

        val startBox = when {
            minDist == distLeft -> PlateBox(0, tcy - sh / 2, sw, tcy + sh / 2)
            minDist == distRight -> PlateBox(videoWidth - sw, tcy - sh / 2, videoWidth, tcy + sh / 2)
            minDist == distTop -> PlateBox(tcx - sw / 2, 0, tcx + sw / 2, sh)
            else -> PlateBox(tcx - sw / 2, videoHeight - sh, tcx + sw / 2, videoHeight)
        }

        val interpolationMap = mutableMapOf<Long, PlateBox>()
        val steps = fromFrameIndex.toFloat()

        for (f in 0 until fromFrameIndex) {
            val ratio = f.toFloat() / steps
            val x1 = (startBox.x1 + ratio * (targetBox.x1 - startBox.x1)).toInt()
            val y1 = (startBox.y1 + ratio * (targetBox.y1 - startBox.y1)).toInt()
            val x2 = (startBox.x2 + ratio * (targetBox.x2 - startBox.x2)).toInt()
            val y2 = (startBox.y2 + ratio * (targetBox.y2 - startBox.y2)).toInt()

            interpolationMap[f] = PlateBox(x1, y1, x2, y2)
        }

        return interpolationMap
    }

    /**
     * Computes the HSV Color Histogram for a sub-region (bounding box) of the image.
     */
    fun computeHsvHistogram(image: BufferedImage, box: PlateBox): FloatArray {
        val hBins = histogramBinCount
        val sBins = 4
        val vBins = 4
        val hist = FloatArray(hBins + sBins + vBins)

        val xStart = box.x1.coerceIn(0, image.width - 1)
        val xEnd = box.x2.coerceIn(0, image.width - 1)
        val yStart = box.y1.coerceIn(0, image.height - 1)
        val yEnd = box.y2.coerceIn(0, image.height - 1)

        var pixelCount = 0
        val hsb = FloatArray(3)

        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                java.awt.Color.RGBtoHSB(r, g, b, hsb)
                val h = hsb[0]
                val s = hsb[1]
                val v = hsb[2]

                val hIdx = (h * hBins).toInt().coerceIn(0, hBins - 1)
                val sIdx = (s * sBins).toInt().coerceIn(0, sBins - 1)
                val vIdx = (v * vBins).toInt().coerceIn(0, vBins - 1)

                hist[hIdx]++
                hist[hBins + sIdx]++
                hist[hBins + sBins + vIdx]++
                pixelCount++
            }
        }

        if (pixelCount > 0) {
            for (i in hist.indices) {
                if (i < hBins) {
                    hist[i] /= pixelCount.toFloat()
                } else if (i < hBins + sBins) {
                    hist[i] /= pixelCount.toFloat()
                } else {
                    hist[i] /= pixelCount.toFloat()
                }
            }
        }

        return hist
    }

    /**
     * Computes the Bhattacharyya distance between two histograms.
     * Returns a value between 0.0 (identical) and 1.0 (completely different).
     */
    fun compareHistograms(hist1: FloatArray, hist2: FloatArray): Float {
        if (hist1.size != hist2.size) return 1.0f

        val hBins = histogramBinCount
        val sBins = 4
        val vBins = 4

        fun bhattacharyyaCoeff(start: Int, end: Int): Float {
            var sum = 0.0f
            for (i in start until end) {
                sum += kotlin.math.sqrt(hist1[i] * hist2[i])
            }
            return sum
        }

        val bcH = bhattacharyyaCoeff(0, hBins)
        val bcS = bhattacharyyaCoeff(hBins, hBins + sBins)
        val bcV = bhattacharyyaCoeff(hBins + sBins, hist1.size)

        val avgBc = (bcH + bcS + bcV) / 3.0f
        val distance = kotlin.math.sqrt((1.0f - avgBc).coerceAtLeast(0.0f))
        return distance
    }
}
