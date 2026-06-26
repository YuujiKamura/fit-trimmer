package utils

import fit.FitParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

object TelemetryAligner {
    
    data class ImuData(
        val times: DoubleArray,
        val accX: DoubleArray,
        val accY: DoubleArray,
        val accZ: DoubleArray,
        val gyroX: DoubleArray,
        val gyroY: DoubleArray,
        val gyroZ: DoubleArray
    )

    private fun extractImuOffsetsFast(filepath: String): ImuData {
        val file = File(filepath)
        val size = file.length()
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(size - 200)
            val endBuf = ByteArray(200)
            raf.readFully(endBuf)
            
            val buffer = ByteBuffer.wrap(endBuf).order(ByteOrder.LITTLE_ENDIAN)
            val extraSize = buffer.getInt(160).toLong() and 0xFFFFFFFFL
            val extraStart = size - extraSize
            
            var pos = 150
            var foundOffsets = false
            var offsetsSize = 0
            var offsetsPos = 0
            
            while (true) {
                var foundPos = -1
                for (i in pos downTo 0) {
                    if (i + 1 < endBuf.size && endBuf[i] == 0.toByte() && endBuf[i+1] == 0.toByte()) {
                        foundPos = i
                        break
                    }
                }
                if (foundPos == -1) break
                
                if (foundPos + 6 <= endBuf.size) {
                    val sizeVal = ByteBuffer.wrap(endBuf, foundPos + 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
                    if (sizeVal in 20..500 && sizeVal % 10 == 0L) {
                        offsetsSize = sizeVal.toInt()
                        offsetsPos = foundPos
                        foundOffsets = true
                        break
                    }
                }
                pos = foundPos - 1
            }
            
            if (!foundOffsets) {
                throw IllegalArgumentException("Failed to locate Offsets header in end buffer")
            }
            
            val offsetFromEnd = 200 - offsetsPos
            raf.seek(size - offsetFromEnd - offsetsSize)
            val offsetsBuf = ByteArray(offsetsSize)
            raf.readFully(offsetsBuf)
            
            var gyroOffset = -1L
            var gyroSize = -1
            val offsetsWrapper = ByteBuffer.wrap(offsetsBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until offsetsSize step 10) {
                if (i + 10 > offsetsSize) break
                val recId = offsetsBuf[i].toInt() and 0xFF
                val recSize = offsetsWrapper.getInt(i + 2).toLong() and 0xFFFFFFFFL
                val recOffset = offsetsWrapper.getInt(i + 6).toLong() and 0xFFFFFFFFL
                if (recId == 3) {
                    gyroOffset = recOffset
                    gyroSize = recSize.toInt()
                }
            }
            
            if (gyroOffset == -1L || gyroSize == -1) {
                throw IllegalArgumentException("Gyro record (ID=3) not found in offsets")
            }
            
            val targetPos = extraStart + gyroOffset
            raf.seek(targetPos)
            val block = ByteArray(gyroSize)
            raf.readFully(block)
            
            val sampleSize = 20
            val sampleCount = gyroSize / sampleSize
            val times = DoubleArray(sampleCount)
            val accX = DoubleArray(sampleCount)
            val accY = DoubleArray(sampleCount)
            val accZ = DoubleArray(sampleCount)
            val gyroX = DoubleArray(sampleCount)
            val gyroY = DoubleArray(sampleCount)
            val gyroZ = DoubleArray(sampleCount)
            
            val blockWrapper = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                val offset = i * sampleSize
                val timecode = blockWrapper.getLong(offset)
                
                times[i] = timecode.toDouble() / 1_000_000.0
                accX[i] = blockWrapper.getShort(offset + 8).toDouble()
                accY[i] = blockWrapper.getShort(offset + 10).toDouble()
                accZ[i] = blockWrapper.getShort(offset + 12).toDouble()
                gyroX[i] = blockWrapper.getShort(offset + 14).toDouble()
                gyroY[i] = blockWrapper.getShort(offset + 16).toDouble()
                gyroZ[i] = blockWrapper.getShort(offset + 18).toDouble()
            }
            
            val indices = times.indices.sortedBy { times[it] }
            val sortedTimes = DoubleArray(sampleCount) { times[indices[it]] }
            val sortedAccX = DoubleArray(sampleCount) { accX[indices[it]] }
            val sortedAccY = DoubleArray(sampleCount) { accY[indices[it]] }
            val sortedAccZ = DoubleArray(sampleCount) { accZ[indices[it]] }
            val sortedGyroX = DoubleArray(sampleCount) { gyroX[indices[it]] }
            val sortedGyroY = DoubleArray(sampleCount) { gyroY[indices[it]] }
            val sortedGyroZ = DoubleArray(sampleCount) { gyroZ[indices[it]] }
            
            return ImuData(
                sortedTimes,
                sortedAccX, sortedAccY, sortedAccZ,
                sortedGyroX, sortedGyroY, sortedGyroZ
            )
        }
    }

    private fun getFirstFileImuOffset(videoPath: String): Double {
        var firstFileImuOffset = 2.664490
        try {
            val file = File(videoPath)
            val videoDir = file.parentFile
            val videoName = file.name
            val regex = Regex("(.*_)(\\d+)(\\.mp4|\\.lrv)", RegexOption.IGNORE_CASE)
            val match = regex.matchEntire(videoName)
            if (match != null) {
                val prefix = match.groupValues[1]
                val ext = match.groupValues[3]
                val firstFileName = "${prefix}001${ext}"
                val firstFilePath = File(videoDir, firstFileName)
                if (firstFilePath.exists()) {
                    val imuData = extractImuOffsetsFast(firstFilePath.absolutePath)
                    if (imuData.times.isNotEmpty()) {
                        firstFileImuOffset = imuData.times[0]
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Failed to extract first file IMU offset: ${e.message}")
        }
        return firstFileImuOffset
    }

    private fun gaussianFilter1D(input: DoubleArray, sigma: Double): DoubleArray {
        val radius = Math.ceil(4.0 * sigma).toInt()
        val size = 2 * radius + 1
        val kernel = DoubleArray(size)
        var sum = 0.0
        for (i in -radius..radius) {
            val x = i.toDouble()
            val v = Math.exp(-(x * x) / (2.0 * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in 0 until size) {
            kernel[i] /= sum
        }

        val n = input.size
        val output = DoubleArray(n)
        for (i in 0 until n) {
            var value = 0.0
            for (k in -radius..radius) {
                val j = i + k
                val refIdx = when {
                    j < 0 -> -j
                    j >= n -> 2 * n - 2 - j
                    else -> j
                }
                val safeIdx = Math.max(0, Math.min(n - 1, refIdx))
                value += input[safeIdx] * kernel[k + radius]
            }
            output[i] = value
        }
        return output
    }

    private fun correlateValid(a: DoubleArray, v: DoubleArray): DoubleArray {
        val n = a.size
        val m = v.size
        if (n < m) return DoubleArray(0)
        val outSize = n - m + 1
        val output = DoubleArray(outSize)
        for (i in 0 until outSize) {
            var sum = 0.0
            for (j in 0 until m) {
                sum += a[i + j] * v[j]
            }
            output[i] = sum
        }
        return output
    }

    private fun normalize(arr: DoubleArray): DoubleArray {
        val mean = arr.average()
        val variance = arr.map { (it - mean) * (it - mean) }.average()
        val std = Math.sqrt(variance)
        val output = DoubleArray(arr.size)
        for (i in arr.indices) {
            output[i] = (arr[i] - mean) / (std + 1e-6)
        }
        return output
    }

    private fun interpolate(x: DoubleArray, xp: DoubleArray, fp: DoubleArray): DoubleArray {
        val output = DoubleArray(x.size)
        for (i in x.indices) {
            val target = x[i]
            if (target <= xp.first()) {
                output[i] = fp.first()
                continue
            }
            if (target >= xp.last()) {
                output[i] = fp.last()
                continue
            }
            val idx = xp.binarySearch(target)
            if (idx >= 0) {
                output[i] = fp[idx]
            } else {
                val insertIdx = -idx - 1
                val idx0 = insertIdx - 1
                val idx1 = insertIdx
                val t = (target - xp[idx0]) / (xp[idx1] - xp[idx0])
                output[i] = fp[idx0] + t * (fp[idx1] - fp[idx0])
            }
        }
        return output
    }

    /**
     * Aligns video start time with fit telemetry using IMU correlation.
     * Calculated natively in Kotlin.
     * Returns the videoStartUtc String (ISO-8601) if successful, null otherwise.
     */
    suspend fun alignVideoWithTelemetry(
        videoPath: String,
        telemetryPoints: List<FitParser.TelemetryPoint>,
        approxStartUtc: String = ""
    ): String? = withContext(Dispatchers.IO) {
        if (telemetryPoints.isEmpty() || videoPath.isEmpty()) {
            println("DEBUG: Auto alignment skipped (empty inputs)")
            return@withContext null
        }
        
        try {
            // 1. Extract video IMU data and calculate vibration
            val imuData = extractImuOffsetsFast(videoPath)
            val times = imuData.times
            if (times.isEmpty()) {
                println("ERROR: No IMU samples extracted from video")
                return@withContext null
            }
            
            val accNorms = DoubleArray(times.size) { i ->
                val x = imuData.accX[i]
                val y = imuData.accY[i]
                val z = imuData.accZ[i]
                Math.sqrt(x * x + y * y + z * z)
            }
            
            val accDiffs = DoubleArray(accNorms.size)
            for (i in 0 until accNorms.size - 1) {
                accDiffs[i] = Math.abs(accNorms[i + 1] - accNorms[i])
            }
            accDiffs[accNorms.size - 1] = 0.0
            
            val relTimes = DoubleArray(times.size) { times[it] - times[0] }
            val maxVTime = Math.ceil(relTimes.last()).toInt()
            
            val vGrid = DoubleArray(maxVTime + 1) { it.toDouble() }
            val vVib = interpolate(vGrid, relTimes, accDiffs)
            
            val firstFileImuOffset = getFirstFileImuOffset(videoPath)

            // 2. Prepare telemetry points
            val fitTs = DoubleArray(telemetryPoints.size) { telemetryPoints[it].timestamp.toDouble() }
            val fitSpeed = DoubleArray(telemetryPoints.size) { telemetryPoints[it].speed.toDouble() }
            
            val startTs = fitTs.first()
            val endTs = fitTs.last()
            val fitGridSize = (endTs - startTs).toInt() + 1
            val fitGrid = DoubleArray(fitGridSize) { startTs + it }
            val fitSpeedGrid = interpolate(fitGrid, fitTs, fitSpeed)

            // 3. Perform Binary Correlation alignment (default robust algorithm for stops)
            val vSig = gaussianFilter1D(vVib, 3.0)
            
            // 10th percentile
            val sortedVSig = vSig.sorted()
            val pct10Idx = (sortedVSig.size * 0.10).toInt()
            val pct10 = sortedVSig[Math.max(0, Math.min(sortedVSig.size - 1, pct10Idx))]
            val vThresh = Math.max(20.0, pct10 * 1.5)
            
            val vMov = DoubleArray(vSig.size) { if (vSig[it] > vThresh) 1.0 else 0.0 }
            val fitMov = DoubleArray(fitSpeedGrid.size) { if (fitSpeedGrid[it] > 2.0) 1.0 else 0.0 }

            val fitSigSmooth = gaussianFilter1D(fitMov, 3.0)
            val vSigSmooth = gaussianFilter1D(vMov, 3.0)

            val fitSigNorm = normalize(fitSigSmooth)
            val vSigNorm = normalize(vSigSmooth)

            val corr = correlateValid(fitSigNorm, vSigNorm)
            if (corr.isEmpty()) {
                println("ERROR: Video is longer than ride telemetry. Cannot align.")
                return@withContext null
            }

            // Find best offset
            var approxStartTs = Double.NaN
            if (approxStartUtc.isNotEmpty()) {
                try {
                    val instant = Instant.parse(approxStartUtc)
                    approxStartTs = (instant.toEpochMilli() / 1000.0) - 631065600.0
                } catch (e: Exception) {
                    println("DEBUG: Failed to parse approxStartUtc: ${e.message}")
                }
            }

            var bestIdx = -1
            var maxCorr = Double.NEGATIVE_INFINITY

            if (!approxStartTs.isNaN()) {
                val window = 900.0
                val minTs = approxStartTs - window
                val maxTs = approxStartTs + window
                for (i in corr.indices) {
                    val ts = fitGrid[i]
                    if (ts in minTs..maxTs) {
                        if (corr[i] > maxCorr) {
                            maxCorr = corr[i]
                            bestIdx = i
                        }
                    }
                }
            }

            if (bestIdx == -1) {
                // global fallback
                for (i in corr.indices) {
                    if (corr[i] > maxCorr) {
                        maxCorr = corr[i]
                        bestIdx = i
                    }
                }
            }

            val videoStartTs = fitGrid[bestIdx]
            val trueFileStartTs = videoStartTs - firstFileImuOffset

            // Convert Garmin epoch back to Unix and generate UTC ISO-8601
            val unixSec = (trueFileStartTs + 631065600.0).toLong()
            val unixNano = ((trueFileStartTs + 631065600.0 - unixSec) * 1_000_000_000).toLong()
            val instant = Instant.ofEpochSecond(unixSec, unixNano)
            
            val utcStr = instant.toString()
            println("DEBUG: Native auto alignment success. Video start: $utcStr, correlation score: $maxCorr")
            return@withContext utcStr

        } catch (e: Exception) {
            println("ERROR: Exception during native auto alignment: ${e.message}")
            e.printStackTrace()
        }
        
        return@withContext null
    }
}
