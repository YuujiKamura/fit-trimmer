package fit

import crc.Crc16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitParserTest {

    @Test
    fun testParseAndTrim() {
        val headerSize = 14
        val recordsSize = 39
        val totalSize = headerSize + recordsSize + 2
        val bytes = ByteArray(totalSize)

        // 1. Construct Header
        bytes[0] = headerSize.toByte()
        bytes[1] = 32 // Protocol Version
        // Profile Version = 2012 (0x07DC) -> DC, 07
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        // Records Size = 39 (0x00000027) -> 27, 00, 00, 00
        bytes[4] = 0x27.toByte()
        bytes[5] = 0x00.toByte()
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x00.toByte()
        // Signature = ".FIT"
        ".FIT".encodeToByteArray().copyInto(bytes, 8)

        // 2. Definition Record
        var offset = headerSize
        bytes[offset] = 0x40.toByte() // Definition header, local ID 0
        bytes[offset + 1] = 0 // Reserved
        bytes[offset + 2] = 0 // Little Endian
        // Global Message Number = 20 (0x0014) -> 14, 00
        bytes[offset + 3] = 0x14.toByte()
        bytes[offset + 4] = 0x00.toByte()
        bytes[offset + 5] = 2 // Field count

        // Field 1: timestamp (num 253, size 4, type uint32 0x86)
        bytes[offset + 6] = 253.toByte()
        bytes[offset + 7] = 4.toByte()
        bytes[offset + 8] = 0x86.toByte()

        // Field 2: distance (num 5, size 4, type uint32 0x86)
        bytes[offset + 9] = 5.toByte()
        bytes[offset + 10] = 4.toByte()
        bytes[offset + 11] = 0x86.toByte()

        offset += 12 // Definition record size is 12

        // Data Record 1: timestamp = 1000000000 (0x3B9ACA00), distance = 10000 (0x00002710)
        bytes[offset] = 0x00.toByte() // Data header, local ID 0
        // timestamp: 00, CA, 9A, 3B
        bytes[offset + 1] = 0x00.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 10, 27, 00, 00
        bytes[offset + 5] = 0x10.toByte()
        bytes[offset + 6] = 0x27.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // Data Record 2: timestamp = 1000000010 (0x3B9ACA0A), distance = 20000 (0x00004E20)
        bytes[offset] = 0x00.toByte()
        // timestamp: 0A, CA, 9A, 3B
        bytes[offset + 1] = 0x0A.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 20, 4E, 00, 00
        bytes[offset + 5] = 0x20.toByte()
        bytes[offset + 6] = 0x4E.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // Data Record 3: timestamp = 1000000020 (0x3B9ACA14), distance = 30000 (0x00007530)
        bytes[offset] = 0x00.toByte()
        // timestamp: 14, CA, 9A, 3B
        bytes[offset + 1] = 0x14.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 30, 75, 00, 00
        bytes[offset + 5] = 0x30.toByte()
        bytes[offset + 6] = 0x75.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // 3. Compute CRCs for initial file
        val headerCrc = Crc16.calculate(bytes, offset = 0, length = 12)
        FitParser.setUShort(bytes, 12, headerCrc, true)

        val fileCrc = Crc16.calculate(bytes, offset = 0, length = headerSize + recordsSize)
        FitParser.setUShort(bytes, headerSize + recordsSize, fileCrc, true)

        // 4. Parse the original FIT file
        val parser = FitParser(bytes)
        parser.parse()

        assertEquals(14, parser.headerSize)
        assertEquals(39, parser.recordsSize)
        assertEquals(4, parser.records.size) // 1 Definition + 3 Data

        // 5. Trim
        // FIT epoch = 631065600
        // Record 1 FIT ts: 1000000000 -> Unix: 1631065600
        // Record 2 FIT ts: 1000000010 -> Unix: 1631065610
        // Record 3 FIT ts: 1000000020 -> Unix: 1631065620
        // We trim from 1631065605 to 1631065625
        val trimmedBytes = parser.trim(1631065605L, 1631065625L)

        // Trimmed size should be: header (14) + new records (12 def + 9 * 2 data = 30) + crc (2) = 46
        assertEquals(46, trimmedBytes.size)

        // Parse trimmed bytes to check structure
        val trimmedParser = FitParser(trimmedBytes)
        trimmedParser.parse()

        assertEquals(30, trimmedParser.recordsSize)
        assertEquals(3, trimmedParser.records.size) // 1 Definition + 2 Data

        // Check new values
        val data1 = trimmedParser.records[1] as FitParser.FitRecord.Data
        assertEquals(1000000010L, data1.data.fields[253]?.value)
        assertEquals(0L, data1.data.fields[5]?.value) // 20000 - 20000 = 0

        val data2 = trimmedParser.records[2] as FitParser.FitRecord.Data
        assertEquals(1000000020L, data2.data.fields[253]?.value)
        assertEquals(10000L, data2.data.fields[5]?.value) // 30000 - 20000 = 10000

        // Verify CRCs are correct
        val newHeaderCrc = Crc16.calculate(trimmedBytes, offset = 0, length = 12)
        val parsedNewHeaderCrc = FitParser.getUShort(trimmedBytes, 12, true)
        assertEquals(newHeaderCrc, parsedNewHeaderCrc)

        val newFileCrc = Crc16.calculate(trimmedBytes, offset = 0, length = 44)
        val parsedNewFileCrc = FitParser.getUShort(trimmedBytes, 44, true)
        assertEquals(newFileCrc, parsedNewFileCrc)
    }

    @Test
    fun testLunchRideHeadingAnalysis() {
        var fitFile = java.io.File("Lunch_Ride.fit")
        if (!fitFile.exists()) {
            fitFile = java.io.File("../Lunch_Ride.fit")
        }
        if (!fitFile.exists()) {
            fitFile = java.io.File("../../Lunch_Ride.fit")
        }
        
        val fileBytes = try {
            fitFile.readBytes()
        } catch (e: Exception) {
            println("Skipping test: Lunch_Ride.fit not found in any expected directory (cwd=${java.io.File(".").absolutePath})")
            return
        }
        
        val parser = FitParser(fileBytes)
        parser.parse()
        val telemetry = parser.getTelemetry()
        assertTrue(telemetry.isNotEmpty(), "Telemetry should not be empty")
        
        // Simulate video start (same as standard run settings)
        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
        val videoStartInstant = java.time.Instant.parse("2026-06-21T02:09:49Z")
        val startFitTime = videoStartInstant.epochSecond - fitEpoch
        val videoDurationSeconds = 120.0 // Check first 2 minutes of ride
        val endFitTime = startFitTime + videoDurationSeconds
        
        val rangePoints = telemetry.filter { it.timestamp in startFitTime.toDouble()..endFitTime.toDouble() }
        println("INFO: Telemetry range points size: ${rangePoints.size}")
        
        // 1. Calculate headings
        val numSecs = videoDurationSeconds.toInt()
        val headings = DoubleArray(numSecs + 1) { -1.0 }
        
        fun getHeadingAtSeconds(sec: Double): Double? {
            val targetFitTime = startFitTime + sec
            val prevPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime - 4.0)) } ?: return null
            val nextPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime + 4.0)) } ?: return null
            
            val dLat = nextPoint.lat - prevPoint.lat
            val dLon = nextPoint.lon - prevPoint.lon
            val dist = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
            if (dist < 0.00005) return null
            
            val dLonRad = (nextPoint.lon - prevPoint.lon) * (Math.PI / 180.0)
            val lat1 = prevPoint.lat * (Math.PI / 180.0)
            val lat2 = nextPoint.lat * (Math.PI / 180.0)
            val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2)
            val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLonRad)
            val brng = kotlin.math.atan2(y, x) * 180.0 / Math.PI
            return (brng + 360.0) % 360.0
        }
        
        for (s in 0..numSecs) {
            headings[s] = getHeadingAtSeconds(s.toDouble()) ?: -1.0
        }
        
        fun headingDiff(h1: Double, h2: Double): Double {
            val diff = kotlin.math.abs(h1 - h2)
            return if (diff > 180.0) 360.0 - diff else diff
        }
        
        // 2. Identify turn events
        val queryOffsets = mutableListOf<Double>()
        queryOffsets.add(0.0)
        
        var baseHeading = -1.0
        var isTurning = false
        var lastQueryOffset = 0.0
        
        for (s in 0..numSecs) {
            if (headings[s] >= 0.0) {
                baseHeading = headings[s]
                break
            }
        }
        
        val maxStraightInterval = 30.0
        val detectedTurns = mutableListOf<Double>()
        
        for (s in 1..numSecs) {
            val currentHeading = headings[s]
            if (currentHeading < 0.0) continue
            if (baseHeading < 0.0) {
                baseHeading = currentHeading
                continue
            }
            
            val diff = headingDiff(currentHeading, baseHeading)
            if (!isTurning) {
                if (diff >= 25.0) {
                    isTurning = true
                    println("DEBUG: Entering turn at ${s}s, diff=${"%.1f".format(diff)} deg, heading=${"%.1f".format(currentHeading)} deg")
                } else {
                    if (s - lastQueryOffset >= maxStraightInterval) {
                        queryOffsets.add(s.toDouble())
                        lastQueryOffset = s.toDouble()
                    }
                }
            } else {
                val lookaheadStable = (1..3).all { offset ->
                    val nextH = headings.getOrNull(s + offset) ?: -1.0
                    nextH >= 0.0 && headingDiff(currentHeading, nextH) < 8.0
                }
                if (lookaheadStable) {
                    queryOffsets.add(s.toDouble())
                    detectedTurns.add(s.toDouble())
                    println("DEBUG: Stabilized/Exited turn at ${s}s, heading=${"%.1f".format(currentHeading)} deg (Previous stable: ${"%.1f".format(baseHeading)} deg)")
                    lastQueryOffset = s.toDouble()
                    baseHeading = currentHeading
                    isTurning = false
                }
            }
        }
        
        println("INFO: Final Query Offsets: $queryOffsets")
        println("INFO: Detected Turns (Intersection Exits): $detectedTurns")
        
        // Assertions
        assertTrue(queryOffsets.contains(0.0), "Should always query start point")
        assertTrue(detectedTurns.isNotEmpty(), "Should detect at least one turn event in the first 2 minutes")
        assertTrue(detectedTurns.any { it in 50.0..70.0 }, "Should detect the initial turn out of the residential street between 50s and 70s (actual corner at 61.0s)")
    }

    @Test
    fun testLunchRideHeadingAnalysisFull30Min() {
        var fitFile = java.io.File("Lunch_Ride.fit")
        if (!fitFile.exists()) fitFile = java.io.File("../Lunch_Ride.fit")
        if (!fitFile.exists()) fitFile = java.io.File("../../Lunch_Ride.fit")
        
        val fileBytes = try { fitFile.readBytes() } catch (e: Exception) { return }
        
        val parser = FitParser(fileBytes)
        parser.parse()
        val telemetry = parser.getTelemetry()
        
        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
        val videoStartInstant = java.time.Instant.parse("2026-06-21T02:09:49Z")
        val startFitTime = videoStartInstant.epochSecond - fitEpoch
        val videoDurationSeconds = 1799.0 // Full 29m 59s
        val endFitTime = startFitTime + videoDurationSeconds
        
        val rangePoints = telemetry.filter { it.timestamp in startFitTime.toDouble()..endFitTime.toDouble() }
        println("INFO: Full 30m Telemetry points: ${rangePoints.size}")
        
        // 1. Old Method (10s fixed poll)
        val old10sPolls = (videoDurationSeconds / 10.0).toInt() + 1
        
        // 2. Old Method (30s fixed poll)
        val old30sPolls = (videoDurationSeconds / 30.0).toInt() + 1
        
        // 3. New Method (Turn detection + 30s straight safeguard)
        val numSecs = videoDurationSeconds.toInt()
        val headings = DoubleArray(numSecs + 1) { -1.0 }
        
        fun getHeadingAtSeconds(sec: Double): Double? {
            val targetFitTime = startFitTime + sec
            val prevPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime - 4.0)) } ?: return null
            val nextPoint = rangePoints.minByOrNull { kotlin.math.abs(it.timestamp - (targetFitTime + 4.0)) } ?: return null
            
            val dLat = nextPoint.lat - prevPoint.lat
            val dLon = nextPoint.lon - prevPoint.lon
            val dist = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
            if (dist < 0.00005) return null
            
            val dLonRad = (nextPoint.lon - prevPoint.lon) * (Math.PI / 180.0)
            val lat1 = prevPoint.lat * (Math.PI / 180.0)
            val lat2 = nextPoint.lat * (Math.PI / 180.0)
            val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2)
            val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLonRad)
            val brng = kotlin.math.atan2(y, x) * 180.0 / Math.PI
            return (brng + 360.0) % 360.0
        }
        
        for (s in 0..numSecs) {
            headings[s] = getHeadingAtSeconds(s.toDouble()) ?: -1.0
        }
        
        fun headingDiff(h1: Double, h2: Double): Double {
            val diff = kotlin.math.abs(h1 - h2)
            return if (diff > 180.0) 360.0 - diff else diff
        }
        
        fun simulateNewMethod(maxStraightInterval: Double): List<Double> {
            val queryOffsets = mutableListOf<Double>()
            queryOffsets.add(0.0)
            
            var baseHeading = -1.0
            var isTurning = false
            var lastQueryOffset = 0.0
            
            for (s in 0..numSecs) {
                if (headings[s] >= 0.0) {
                    baseHeading = headings[s]
                    break
                }
            }
            
            for (s in 1..numSecs) {
                val currentHeading = headings[s]
                if (currentHeading < 0.0) continue
                if (baseHeading < 0.0) {
                    baseHeading = currentHeading
                    continue
                }
                
                val diff = headingDiff(currentHeading, baseHeading)
                if (!isTurning) {
                    if (diff >= 25.0) {
                        isTurning = true
                    } else {
                        if (s - lastQueryOffset >= maxStraightInterval) {
                            queryOffsets.add(s.toDouble())
                            lastQueryOffset = s.toDouble()
                        }
                    }
                } else {
                    val lookaheadStable = (1..3).all { offset ->
                        val nextH = headings.getOrNull(s + offset) ?: -1.0
                        nextH >= 0.0 && headingDiff(currentHeading, nextH) < 8.0
                    }
                    if (lookaheadStable) {
                        queryOffsets.add(s.toDouble())
                        lastQueryOffset = s.toDouble()
                        baseHeading = currentHeading
                        isTurning = false
                    }
                }
            }
            
            val cleanQueryOffsets = mutableListOf<Double>()
            for (offset in queryOffsets) {
                if (cleanQueryOffsets.isEmpty() || offset - cleanQueryOffsets.last() >= 3.0) {
                    cleanQueryOffsets.add(offset)
                }
            }
            return cleanQueryOffsets
        }
        
        val new30sSafeguard = simulateNewMethod(30.0)
        val new120sSafeguard = simulateNewMethod(120.0)
        
        println("INFO: Old 10s Poll Count: $old10sPolls")
        println("INFO: Old 30s Poll Count: $old30sPolls")
        println("INFO: New Method (30s safeguard) Count: ${new30sSafeguard.size}")
        println("INFO: New Method (120s safeguard) Count: ${new120sSafeguard.size}")
        println("INFO: New Method (120s safeguard) Timestamps: $new120sSafeguard")
        
        assertTrue(new120sSafeguard.size < old10sPolls, "New method should call API fewer times than old 10s polling")
    }

    @Test
    fun dumpRealTelemetryApiData() {
        var fitFile = java.io.File("Lunch_Ride.fit")
        if (!fitFile.exists()) fitFile = java.io.File("../Lunch_Ride.fit")
        if (!fitFile.exists()) fitFile = java.io.File("../../Lunch_Ride.fit")
        val fileBytes = try { fitFile.readBytes() } catch (e: Exception) { return }
        
        val parser = FitParser(fileBytes)
        parser.parse()
        val telemetry = parser.getTelemetry()
        
        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
        val videoStartInstant = java.time.Instant.parse("2026-06-21T02:09:49Z")
        val startFitTime = videoStartInstant.epochSecond - fitEpoch
        
        val targetOffsets = listOf(0.0, 61.0, 610.0, 880.0, 1415.0, 1594.0)
        
        val client = java.net.http.HttpClient.newHttpClient()
        val logFile = java.io.File("real_api_dumps.txt")
        logFile.writeText("=== REAL API DUMPS FOR LUNCH_RIDE.FIT ===\n\n")
        
        for (offset in targetOffsets) {
            val targetFitTime = startFitTime + offset
            val point = telemetry.minByOrNull { kotlin.math.abs(it.timestamp - targetFitTime) } ?: continue
            
            logFile.appendText("--- OFFSET: ${offset}s (lat=${point.lat}, lon=${point.lon}) ---\n")
            
            // 1. GSI Vector Tile
            val (tileX, tileY) = GsiRoadDetector.deg2tile(point.lat, point.lon, 16)
            val gsiUrl = "https://cyberjapandata.gsi.go.jp/xyz/experimental_rdcl/16/$tileX/$tileY.geojson"
            logFile.appendText("GSI URL: $gsiUrl\n")
            try {
                val req = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(gsiUrl)).header("User-Agent", "Mozilla/5.0").build()
                val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                logFile.appendText("GSI STATUS: ${resp.statusCode()}\n")
                if (resp.statusCode() == 200) {
                    val road = GsiRoadDetector.findClosestRoad(point.lat, point.lon, resp.body())
                    logFile.appendText("GSI CLOSEST ROAD: $road\n")
                } else {
                    logFile.appendText("GSI BODY: (Failed to fetch)\n")
                }
            } catch (e: Exception) {
                logFile.appendText("GSI ERROR: ${e.message}\n")
            }
            
            // 2. OSM Nominatim
            val osmUrl = "https://nominatim.openstreetmap.org/reverse?lat=${point.lat}&lon=${point.lon}&format=json&accept-language=ja&addressdetails=1"
            logFile.appendText("OSM URL: $osmUrl\n")
            Thread.sleep(1000)
            try {
                val req = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(osmUrl)).header("User-Agent", "FitTrimmerApp/1.0 (yuuji@kamura.jp)").build()
                val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                logFile.appendText("OSM STATUS: ${resp.statusCode()}\n")
                if (resp.statusCode() == 200) {
                    logFile.appendText("OSM BODY: ${resp.body()}\n")
                }
            } catch (e: Exception) {
                logFile.appendText("OSM ERROR: ${e.message}\n")
            }
            logFile.appendText("\n==========================================\n\n")
        }
        println("SUCCESS: Dumped real API data to ${logFile.absolutePath}")
    }

    @Test
    fun testParseTruncatedFitData() {
        val headerSize = 14
        val bytes = ByteArray(headerSize + 5) // Truncated, missing data records
        bytes[0] = headerSize.toByte()
        bytes[1] = 32
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        // recordsSize = 1000 (indicates more data than available)
        bytes[4] = 0xE8.toByte()
        bytes[5] = 0x03.toByte()
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x00.toByte()
        ".FIT".encodeToByteArray().copyInto(bytes, 8)

        val parser = FitParser(bytes)
        var thrown = false
        try {
            parser.parse()
        } catch (e: IllegalArgumentException) {
            thrown = true
        } catch (e: IllegalStateException) {
            thrown = true
        } catch (e: Exception) {
            println("Caught unexpected exception: ${e::class.simpleName}: ${e.message}")
        }
        assertTrue(thrown, "Should throw IllegalArgumentException or IllegalStateException when parsing truncated/corrupted FIT data")
    }

    @Test
    fun testParseRealFitFile() {
        val file = java.io.File("F:\\Insta360\\fit\\Afternoon_Ride_鞠智城跡.fit")
        if (!file.exists()) {
            println("Skipping testParseRealFitFile because file does not exist on this machine: ${file.absolutePath}")
            return
        }
        val bytes = file.readBytes()
        val parser = FitParser(bytes)
        parser.parse()
        val telemetry = parser.getTelemetry()
        println("Loaded real FIT file: ${file.absolutePath}")
        println("Total parsed telemetry points: ${telemetry.size}")
        
        // Assert basic telemetry structure
        assertTrue(telemetry.isNotEmpty(), "Telemetry should not be empty")
        
        // Check distance data
        val hasDistanceData = telemetry.any { it.distance > 0.0 }
        println("FIT file has distance data: $hasDistanceData")
        
        if (hasDistanceData) {
            val startDist = telemetry.first().distance
            val endDist = telemetry.last().distance
            println("Start distance: $startDist m, End distance: $endDist m")
            assertTrue(endDist >= startDist, "End distance should be greater than or equal to start distance")
        }
        
        // Let's test fallback distance accumulation on this real data
        val startPoint = telemetry.first()
        val lastPoint = telemetry.last()
        
        // 1. Accumulate using speed
        var accumulatedDist = 0.0
        for (i in 1 until telemetry.size) {
            val dt = telemetry[i].timestamp - telemetry[i-1].timestamp
            if (dt in 0.0..5.0) {
                val avgSpeedMps = ((telemetry[i].speed + telemetry[i-1].speed) / 2.0) / 3.6
                accumulatedDist += avgSpeedMps * dt
            }
        }
        println("Accumulated distance from speed: $accumulatedDist meters")
        
        if (hasDistanceData) {
            val actualDiff = lastPoint.distance - startPoint.distance
            println("Actual distance diff: $actualDiff meters")
            // The accumulated speed distance and actual distance should be extremely close (usually within 5-10% depending on FIT precision)
            val percentageDiff = kotlin.math.abs(accumulatedDist - actualDiff) / actualDiff
            println("Percentage difference: ${percentageDiff * 100.0}%")
            assertTrue(percentageDiff < 0.2, "Accumulated speed distance is too far from actual FIT distance ($accumulatedDist vs $actualDiff)")
        }
    }
}


