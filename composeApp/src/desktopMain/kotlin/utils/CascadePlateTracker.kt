package utils

import fit.PlateBox

class CascadePlateTracker {

    data class RelativePlateBox(
        val rx1: Float,
        val ry1: Float,
        val rx2: Float,
        val ry2: Float
    )

    data class VehicleTrack(
        val id: Int,
        var lastBbox: PlateBox,
        var lastSeenTimeMs: Long,
        var relPlateBox: RelativePlateBox? = null,
        var plateDetectedCount: Int = 0
    )

    private val activeTracks = mutableListOf<VehicleTrack>()
    private var nextTrackId = 1

    private val iouThreshold = 0.4f // Tightened from 0.2f to prevent cross-vehicle tracking jumps
    private val maxMissedTimeMs = 1500L // 1.5 seconds tracking timeout

    private fun log(msg: String) {
        PlateDetectionManager.trackingLogs.add(msg)
    }

    fun update(
        timeMs: Long,
        detectedVehicles: List<PlateBox>,
        detectedPlates: List<PlateBox>
    ): List<PlateBox> {
        // 1. Clean up stale tracks (timeout)
        activeTracks.removeAll { track ->
            val isStale = timeMs - track.lastSeenTimeMs > maxMissedTimeMs
            if (isStale) {
                log("Frame at ${timeMs}ms: Track #${track.id} lost (timeout)")
            }
            isStale
        }

        val unmatchedVehicles = detectedVehicles.toMutableList()
        val matchedTracks = mutableSetOf<VehicleTrack>()

        // 2. Track matching using Greedy matching (by IOU)
        val sortedPairs = mutableListOf<Triple<VehicleTrack, PlateBox, Float>>()
        for (track in activeTracks) {
            for (veh in unmatchedVehicles) {
                val score = iou(track.lastBbox, veh)
                if (score >= iouThreshold) {
                    sortedPairs.add(Triple(track, veh, score))
                }
            }
        }
        sortedPairs.sortByDescending { it.third }

        val matchedVehicles = mutableSetOf<PlateBox>()
        for ((track, veh, score) in sortedPairs) {
            if (track in matchedTracks || veh in matchedVehicles) continue
            track.lastBbox = veh
            track.lastSeenTimeMs = timeMs
            matchedTracks.add(track)
            matchedVehicles.add(veh)
            unmatchedVehicles.remove(veh)
            log("Frame at ${timeMs}ms: Track #${track.id} updated (followed vehicle to [${veh.x1}, ${veh.y1}, ${veh.x2}, ${veh.y2}])")
        }

        // Create new tracks for unmatched vehicles
        for (veh in unmatchedVehicles) {
            val newTrack = VehicleTrack(
                id = nextTrackId++,
                lastBbox = veh,
                lastSeenTimeMs = timeMs
            )
            activeTracks.add(newTrack)
            matchedTracks.add(newTrack)
            log("Frame at ${timeMs}ms: Track #${newTrack.id} created for vehicle at [${veh.x1}, ${veh.y1}, ${veh.x2}, ${veh.y2}]")
        }

        // 3. Associate detected plates with active vehicle tracks
        val unmatchedPlates = detectedPlates.toMutableList()
        val outputPlates = mutableListOf<PlateBox>()

        for (track in matchedTracks) {
            val veh = track.lastBbox
            val vw = (veh.x2 - veh.x1).coerceAtLeast(1)
            val vh = (veh.y2 - veh.y1).coerceAtLeast(1)

            // Find the best plate candidate belonging to this vehicle (closest to vehicle horizontal center)
            val vehicleCenterX = (veh.x1 + veh.x2) / 2
            var bestPlateIndex = -1
            var minDistanceX = Int.MAX_VALUE

            for (pIdx in unmatchedPlates.indices) {
                val plate = unmatchedPlates[pIdx]
                if (isPlateInsideVehicle(plate, veh)) {
                    val plateCenterX = (plate.x1 + plate.x2) / 2
                    val distX = kotlin.math.abs(plateCenterX - vehicleCenterX)
                    if (distX < minDistanceX) {
                        minDistanceX = distX
                        bestPlateIndex = pIdx
                    }
                }
            }

            if (bestPlateIndex != -1) {
                val plate = unmatchedPlates.removeAt(bestPlateIndex)
                
                // Calculate relative plate box ratios
                val rx1 = (plate.x1 - veh.x1).toFloat() / vw.toFloat()
                val ry1 = (plate.y1 - veh.y1).toFloat() / vh.toFloat()
                val rx2 = (plate.x2 - veh.x1).toFloat() / vw.toFloat()
                val ry2 = (plate.y2 - veh.y1).toFloat() / vh.toFloat()

                track.relPlateBox = RelativePlateBox(rx1, ry1, rx2, ry2)
                track.plateDetectedCount++
                outputPlates.add(plate.copy(trackId = track.id))
                log("Frame at ${timeMs}ms: Track #${track.id} matched with closest plate at [${plate.x1}, ${plate.y1}, ${plate.x2}, ${plate.y2}] (DistX: $minDistanceX, Count: ${track.plateDetectedCount})")
            } else {
                // Reconstruct from relative coordinates only if we have confirmed the plate (detected at least 2 times)
                val rel = track.relPlateBox
                if (rel != null && track.plateDetectedCount >= 2) {
                    val px1 = veh.x1 + (rel.rx1 * vw.toFloat()).toInt()
                    val py1 = veh.y1 + (rel.ry1 * vh.toFloat()).toInt()
                    val px2 = veh.x1 + (rel.rx2 * vw.toFloat()).toInt()
                    val py2 = veh.y1 + (rel.ry2 * vh.toFloat()).toInt()

                    outputPlates.add(PlateBox(px1, py1, px2, py2, track.id))
                    log("Frame at ${timeMs}ms: Track #${track.id} plate lost. Reconstructed relative box at [$px1, $py1, $px2, $py2] (Confirmed)")
                } else if (rel != null) {
                    log("Frame at ${timeMs}ms: Track #${track.id} plate lost. Skipped reconstruction as it is unconfirmed (Count: ${track.plateDetectedCount})")
                }
            }
        }

        // DO NOT add unassociated plates directly in Cascade Tracking!
        // These are noise detections from the cropped background (e.g. roadside posts, trees, or side mirrors).
        // If a plate is real, it must associate with an active vehicle track.
        for (plate in unmatchedPlates) {
            log("Frame at ${timeMs}ms: Discarded unassociated noise plate at [${plate.x1}, ${plate.y1}, ${plate.x2}, ${plate.y2}]")
        }

        return outputPlates.distinct()
    }

    private fun iou(a: PlateBox, b: PlateBox): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)

        val intersection = maxOf(0, x2 - x1).toFloat() * maxOf(0, y2 - y1).toFloat()
        val areaA = (a.x2 - a.x1).toFloat() * (a.y2 - a.y1).toFloat()
        val areaB = (b.x2 - b.x1).toFloat() * (b.y2 - b.y1).toFloat()
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun isPlateInsideVehicle(plate: PlateBox, vehicle: PlateBox): Boolean {
        val px = (plate.x1 + plate.x2) / 2
        val py = (plate.y1 + plate.y2) / 2

        val vw = vehicle.x2 - vehicle.x1
        val vh = vehicle.y2 - vehicle.y1

        // Plate must be strictly inside the horizontal bounds of the vehicle
        val isInsideX = px in vehicle.x1..vehicle.x2

        // Plate is typically in the bottom 60% of the vehicle area,
        // and we allow it to extend up to 10% of vehicle height below the bumper
        val yMin = vehicle.y1 + (vh * 0.4f).toInt()
        val yMax = vehicle.y2 + (vh * 0.1f).toInt()
        val isInsideY = py in yMin..yMax

        return isInsideX && isInsideY
    }
}
