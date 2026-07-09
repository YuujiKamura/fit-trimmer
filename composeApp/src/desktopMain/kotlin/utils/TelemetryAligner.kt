package utils

import fit.TelemetryPoint

import fit.FitParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

object TelemetryAligner {
    @Volatile
    var lastMaxCorr: Double = 0.0

    @Volatile
    var lastAnchorSec: Double = 0.0

    @kotlinx.serialization.Serializable
    data class AlignConfig(
        val speed_threshold: Double = 2.0,
        val vib_threshold_factor: Double = 1.5,
        val min_vib_threshold: Double = 20.0,
        val power_min_threshold: Double = 30.0,
        val power_max_threshold: Double = 120.0,
        val power_threshold_ratio: Double = 0.15,
        val power_active_weight: Double = 0.35,
        val power_edge_weight: Double = 0.65,
        val gaussian_sigma_speed: Double = 3.0,
        val gaussian_sigma_power: Double = 1.0,
        val gaussian_sigma_vib: Double = 2.0,
        val window_seconds: Double = 60.0
    )

    var config = AlignConfig()
        private set

    fun loadConfig(configFile: File) {
        try {
            if (configFile.exists()) {
                val jsonStr = configFile.readText()
                config = kotlinx.serialization.json.Json.decodeFromString(jsonStr)
                println("ℹ️ Loaded dynamic IMU alignment parameters from: ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            println("⚠️ Failed to load alignment config: ${e.message}, using defaults.")
        }
    }
    
    data class ImuData(
        val times: DoubleArray,
        val accX: DoubleArray,
        val accY: DoubleArray,
        val accZ: DoubleArray,
        val gyroX: DoubleArray,
        val gyroY: DoubleArray,
        val gyroZ: DoubleArray
    )

    data class AlignmentCandidate(
        val alignedUtc: String,
        val offsetSeconds: Double?,
        val fitStartSeconds: Double,
        val correlation: Double,
        val rank: Int
    )

    internal fun extractImuOffsetsFast(filepath: String): ImuData {
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
        telemetryPoints: List<TelemetryPoint>,
        approxStartUtc: String = "",
        method: String = "binary",
        windowSeconds: Double = 90.0
    ): String? = withContext(Dispatchers.IO) {
        val candidates = alignVideoWithTelemetryCandidates(
            videoPath = videoPath,
            telemetryPoints = telemetryPoints,
            approxStartUtc = approxStartUtc,
            method = method,
            windowSeconds = windowSeconds,
            maxCandidates = 1
        )
        return@withContext candidates.firstOrNull()?.alignedUtc
    }

    suspend fun alignVideoWithTelemetryCandidates(
        videoPath: String,
        telemetryPoints: List<TelemetryPoint>,
        approxStartUtc: String = "",
        method: String = "binary",
        windowSeconds: Double = 90.0,
        maxCandidates: Int = 5,
        minSeparationSeconds: Int = 30
    ): List<AlignmentCandidate> = withContext(Dispatchers.IO) {
        if (telemetryPoints.isEmpty() || videoPath.isEmpty()) {
            println("DEBUG: Auto alignment skipped (empty inputs)")
            return@withContext emptyList()
        }
        
        try {
            // 1. Extract video IMU data and calculate vibration
            val imuData = extractImuOffsetsFast(videoPath)
            val times = imuData.times
            if (times.isEmpty()) {
                println("ERROR: No IMU samples extracted from video")
                return@withContext emptyList()
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
            return@withContext alignVibWithTelemetryCandidatesCore(
                vVib = vVib,
                telemetryPoints = telemetryPoints,
                approxStartUtc = approxStartUtc,
                method = method,
                windowSeconds = windowSeconds,
                firstFileImuOffset = firstFileImuOffset,
                maxCandidates = maxCandidates,
                minSeparationSeconds = minSeparationSeconds
            )
        } catch (e: Exception) {
            println("ERROR: Exception during native auto alignment: ${e.message}")
            e.printStackTrace()
        }
        
        return@withContext emptyList()
    }

    /**
     * Core alignment logic working directly on resampled vibration arrays,
     * allowing file-less testing.
     */
    fun alignVibWithTelemetryCore(
        vVib: DoubleArray,
        telemetryPoints: List<TelemetryPoint>,
        approxStartUtc: String,
        method: String,
        windowSeconds: Double,
        firstFileImuOffset: Double
    ): String? {
        return alignVibWithTelemetryCandidatesCore(
            vVib = vVib,
            telemetryPoints = telemetryPoints,
            approxStartUtc = approxStartUtc,
            method = method,
            windowSeconds = windowSeconds,
            firstFileImuOffset = firstFileImuOffset,
            maxCandidates = 1
        ).firstOrNull()?.alignedUtc
    }

    fun alignVibWithTelemetryCandidatesCore(
        vVib: DoubleArray,
        telemetryPoints: List<TelemetryPoint>,
        approxStartUtc: String,
        method: String,
        windowSeconds: Double,
        firstFileImuOffset: Double,
        maxCandidates: Int = 5,
        minSeparationSeconds: Int = 30
    ): List<AlignmentCandidate> {
        if (telemetryPoints.isEmpty() || vVib.isEmpty()) return emptyList()

        // 2. Prepare telemetry points
        val fitTs = DoubleArray(telemetryPoints.size) { telemetryPoints[it].timestamp.toDouble() }
        val fitSpeed = DoubleArray(telemetryPoints.size) { telemetryPoints[it].speed.toDouble() }
        val fitPower = DoubleArray(telemetryPoints.size) { telemetryPoints[it].power.toDouble() }
        
        val startTs = fitTs.first()
        val endTs = fitTs.last()
        val fitGridSize = (endTs - startTs).toInt() + 1
        val fitGrid = DoubleArray(fitGridSize) { startTs + it }
        val fitSpeedGrid = interpolate(fitGrid, fitTs, fitSpeed)
        val fitPowerGrid = interpolate(fitGrid, fitTs, fitPower)

        // Auto-pause gap correction: set interpolated telemetry signals to 0.0 during gaps > 2.0 seconds
        for (i in 0 until fitTs.size - 1) {
            val ts1 = fitTs[i]
            val ts2 = fitTs[i + 1]
            if (ts2 - ts1 > 2.0) {
                val idxStart = Math.ceil(ts1 - startTs).toInt()
                val idxEnd = Math.floor(ts2 - startTs).toInt()
                for (idx in idxStart..idxEnd) {
                    if (idx in fitSpeedGrid.indices) {
                        fitSpeedGrid[idx] = 0.0
                        fitPowerGrid[idx] = 0.0
                    }
                }
            }
        }

        val corr: DoubleArray
        val fitSigSmooth: DoubleArray
        val vSigSmooth: DoubleArray

        if (method == "binary") {
            val maxPower = fitPowerGrid.maxOrNull() ?: 0.0
            val usablePowerSamples = fitPowerGrid.count { it > 10.0 }
            if (maxPower < config.power_min_threshold || usablePowerSamples < 5) {
                println("WARNING: Power data insufficient (maxPower=$maxPower, usableSamples=$usablePowerSamples). Falling back to speed-based sync signal.")
                
                // Speed-based logic (old binary method logic)
                val vSig = gaussianFilter1D(vVib, config.gaussian_sigma_speed)
                
                // 10th percentile
                val sortedVSig = vSig.sorted()
                val pct10Idx = (sortedVSig.size * 0.10).toInt()
                val pct10 = sortedVSig[Math.max(0, Math.min(sortedVSig.size - 1, pct10Idx))]
                val vThresh = Math.max(config.min_vib_threshold, pct10 * config.vib_threshold_factor)
                
                val vMov = DoubleArray(vSig.size) { if (vSig[it] > vThresh) 1.0 else 0.0 }
                val fitMov = DoubleArray(fitSpeedGrid.size) { if (fitSpeedGrid[it] > config.speed_threshold) 1.0 else 0.0 }

                fitSigSmooth = gaussianFilter1D(fitMov, config.gaussian_sigma_speed)
                vSigSmooth = gaussianFilter1D(vMov, config.gaussian_sigma_speed)
            } else {
                val powerThreshold = Math.max(config.power_min_threshold, Math.min(config.power_max_threshold, maxPower * config.power_threshold_ratio))
                val fitPowerSmooth = gaussianFilter1D(fitPowerGrid, config.gaussian_sigma_power)
                val fitPowerDelta = DoubleArray(fitPowerSmooth.size)
                for (i in 1 until fitPowerSmooth.size) {
                    fitPowerDelta[i] = Math.abs(fitPowerSmooth[i] - fitPowerSmooth[i - 1])
                }
                val maxPowerDelta = fitPowerDelta.maxOrNull()?.takeIf { it > 1e-6 } ?: 1.0
                val fitPowerEvent = DoubleArray(fitPowerSmooth.size) { i ->
                    val active = if (fitPowerSmooth[i] > powerThreshold) 1.0 else 0.0
                    val edge = fitPowerDelta[i] / maxPowerDelta
                    config.power_active_weight * active + config.power_edge_weight * edge
                }

                val vSig = gaussianFilter1D(vVib, config.gaussian_sigma_vib)
                val vDelta = DoubleArray(vSig.size)
                for (i in 1 until vSig.size) {
                    vDelta[i] = Math.abs(vSig[i] - vSig[i - 1])
                }
                val maxVDelta = vDelta.maxOrNull()?.takeIf { it > 1e-6 } ?: 1.0
                val vEvent = DoubleArray(vSig.size) { i -> vDelta[i] / maxVDelta }

                fitSigSmooth = gaussianFilter1D(fitPowerEvent, config.gaussian_sigma_vib)
                vSigSmooth = gaussianFilter1D(vEvent, config.gaussian_sigma_vib)
                println(
                    "DEBUG: Using power-event sync signal. " +
                        "maxPower=$maxPower threshold=$powerThreshold usableSamples=$usablePowerSamples"
                )
            }

            // Calculate Pearson's Local Normalized Cross-Correlation (NCC)
            val outSize = fitSigSmooth.size - vSigSmooth.size + 1
            if (outSize <= 0) {
                corr = DoubleArray(0)
            } else {
                corr = DoubleArray(outSize)
                val m = vSigSmooth.size
                val meanV = vSigSmooth.average()
                val varV = vSigSmooth.map { (it - meanV) * (it - meanV) }.average()
                val stdV = Math.sqrt(varV)

                if (stdV > 1e-6) {
                    for (i in corr.indices) {
                        var sumA = 0.0
                        var sumA2 = 0.0
                        for (j in 0 until m) {
                            val valA = fitSigSmooth[i + j]
                            sumA += valA
                            sumA2 += valA * valA
                        }
                        val meanA = sumA / m
                        val varA = (sumA2 / m) - (meanA * meanA)
                        val stdA = Math.sqrt(Math.max(0.0, varA))

                        if (stdA > 1e-6) {
                            var sumCov = 0.0
                            for (j in 0 until m) {
                                sumCov += (fitSigSmooth[i + j] - meanA) * (vSigSmooth[j] - meanV)
                            }
                            corr[i] = sumCov / (m * stdA * stdV)
                        } else {
                            corr[i] = 0.0
                        }
                    }
                }
            }
        } else {
            // Acceleration Method
            val fitAcc = DoubleArray(fitSpeedGrid.size)
            for (i in 0 until fitSpeedGrid.size - 1) {
                fitAcc[i] = Math.abs(fitSpeedGrid[i + 1] - fitSpeedGrid[i])
            }
            fitAcc[fitSpeedGrid.size - 1] = 0.0

            val vSig = gaussianFilter1D(vVib, 3.0)
            val vAcc = DoubleArray(vSig.size)
            for (i in 0 until vSig.size - 1) {
                vAcc[i] = Math.abs(vSig[i + 1] - vSig[i])
            }
            vAcc[vSig.size - 1] = 0.0

            val fitSigSmooth = gaussianFilter1D(fitAcc, 3.0)
            val vSigSmooth = gaussianFilter1D(vAcc, 3.0)

            val fitSigNorm = normalize(fitSigSmooth)
            val vSigNorm = normalize(vSigSmooth)
            corr = correlateValid(fitSigNorm, vSigNorm)
        }

        if (corr.isEmpty()) {
            println("ERROR: Video is longer than ride telemetry. Cannot align.")
            return emptyList()
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

        val useStrictWindowOnly = !approxStartTs.isNaN() && windowSeconds > 0.0 && windowSeconds < 999999.0

        val allowedIndices = if (useStrictWindowOnly) {
            val minTs = approxStartTs - windowSeconds
            val maxTs = approxStartTs + windowSeconds
            corr.indices.filter { fitGrid[it] in minTs..maxTs }
        } else {
            corr.indices.toList()
        }

        if (allowedIndices.isEmpty()) {
            println("DEBUG: Alignment candidate search found no valid indices in the requested window.")
            return emptyList()
        }

        val peakIndices = allowedIndices.filter { i ->
            val prev = if (i > 0) corr[i - 1] else Double.NEGATIVE_INFINITY
            val next = if (i + 1 < corr.size) corr[i + 1] else Double.NEGATIVE_INFINITY
            corr[i] >= prev && corr[i] >= next
        }.ifEmpty { allowedIndices }

        val selected = mutableListOf<Int>()
        for (idx in peakIndices.sortedByDescending { corr[it] }) {
            if (selected.size >= maxCandidates.coerceAtLeast(1)) break
            if (selected.none { Math.abs(it - idx) < minSeparationSeconds }) {
                selected.add(idx)
            }
        }

        if (selected.isEmpty()) return emptyList()

        val approxInstant = approxStartUtc.takeIf { it.isNotEmpty() }?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        }
        val candidates = selected.mapIndexed { rankIndex, idx ->
            val videoStartTs = fitGrid[idx]
            val trueFileStartTs = videoStartTs - firstFileImuOffset
            val unixSecondsDouble = trueFileStartTs + 631065600.0
            val unixSec = Math.floor(unixSecondsDouble).toLong()
            val unixNano = ((unixSecondsDouble - unixSec) * 1_000_000_000).toLong()
            val instant = Instant.ofEpochSecond(unixSec, unixNano)
            val offsetSeconds = approxInstant?.let { (instant.toEpochMilli() - it.toEpochMilli()) / 1000.0 }

            AlignmentCandidate(
                alignedUtc = instant.toString(),
                offsetSeconds = offsetSeconds,
                fitStartSeconds = trueFileStartTs - startTs,
                correlation = corr[idx],
                rank = rankIndex + 1
            )
        }

        lastMaxCorr = candidates.first().correlation
        lastAnchorSec = firstFileImuOffset

        val mode = if (useStrictWindowOnly) "window" else "global"
        println("DEBUG: $mode alignment candidates: " + candidates.joinToString { "#${it.rank}@${it.alignedUtc}(r=${it.correlation})" })

        return candidates
    }

    fun calculateOffsetFromTargetSec(
        videoStartUtc: String,
        fitStartUtc: String,
        targetSec: Double
    ): Long {
        if (videoStartUtc.isEmpty() || fitStartUtc.isEmpty()) return 0L
        try {
            val videoInstant = java.time.Instant.parse(videoStartUtc)
            val fitInstant = java.time.Instant.parse(fitStartUtc)
            
            val videoStartMs = videoInstant.toEpochMilli()
            val fitStartMs = fitInstant.toEpochMilli()
            
            val adjustedStartMs = fitStartMs - (targetSec * 1000.0).toLong()
            return adjustedStartMs - videoStartMs
        } catch (e: Exception) {
            e.printStackTrace()
            return 0L
        }
    }

    fun calculateOffsetForVideoStartAtFitSec(
        videoStartUtc: String,
        fitStartUtc: String,
        videoStartFitSec: Double
    ): Long {
        if (videoStartUtc.isEmpty() || fitStartUtc.isEmpty()) return 0L
        return try {
            val videoInstant = java.time.Instant.parse(videoStartUtc)
            val fitInstant = java.time.Instant.parse(fitStartUtc)

            val desiredAdjustedStartMs = fitInstant.toEpochMilli() + (videoStartFitSec * 1000.0).toLong()
            desiredAdjustedStartMs - videoInstant.toEpochMilli()
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
}
