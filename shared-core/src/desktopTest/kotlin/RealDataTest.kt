package fit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RealDataTest {
    @Test
    fun testWithLunchRide() {
        val fitFilePath = "H:\\マイドライブ\\20260621\\Lunch_Ride.fit"
        val fitFile = File(fitFilePath)
        
        if (!fitFile.exists()) {
            println("SKIP: Test file not found at $fitFilePath")
            return
        }

        println("--- Real Data Debug: ${fitFile.name} ---")
        val bytes = fitFile.readBytes()
        val parser = FitParser(bytes)
        
        parser.parse()
        
        val record20s = parser.records.filterIsInstance<FitParser.FitRecord.Data>()
            .filter { it.globalMessageNumber == 20 }

        println("Total Records: ${parser.records.size}")
        println("Data Records (msg 20): ${record20s.size}")

        if (record20s.isNotEmpty()) {
            val first = record20s.first().data
            val last = record20s.last().data
            println("First timestamp: ${first.fields[253]?.value} (${first.fields[253]?.value?.javaClass})")
            println("Last timestamp: ${last.fields[253]?.value}")
        }
    }

    @Test
    fun testWithAfternoonRide() {
        val fitFilePath = "H:\\マイドライブ\\20260614\\Afternoon_Ride_鞠智城跡.fit"
        val fitFile = File(fitFilePath)
        
        if (!fitFile.exists()) {
            println("SKIP: Test file not found at $fitFilePath")
            return
        }

        println("--- Real Data Debug: ${fitFile.name} ---")
        val bytes = fitFile.readBytes()
        val parser = FitParser(bytes)
        parser.parse()
        
        val record20s = parser.records.filterIsInstance<FitParser.FitRecord.Data>()
            .filter { it.globalMessageNumber == 20 }

        println("Total Records: ${parser.records.size}")
        println("Data Records (msg 20): ${record20s.size}")

        if (record20s.isNotEmpty()) {
            val first = record20s.first().data
            val last = record20s.last().data
            val firstTs = first.fields[253]?.value
            val lastTs = last.fields[253]?.value
            println("First timestamp: $firstTs (${firstTs?.javaClass})")
            println("Last timestamp: $lastTs")

            val fitEpochSec = 631065600L
            val firstUtc = java.time.Instant.ofEpochSecond(firstTs as Long + fitEpochSec)
            val lastUtc = java.time.Instant.ofEpochSecond(lastTs as Long + fitEpochSec)
            println("First UTC: $firstUtc")
            println("Last UTC: $lastUtc")
            
            // Video start was: 2026-06-14T08:02:06Z
            // With offset -20s: 2026-06-14T08:01:46Z
            // Trim start: 100s -> 2026-06-14T08:03:26Z
            // Let's check where 2026-06-14T08:02:06Z is relative to FIT file
            val videoStartUtcSeconds = java.time.Instant.parse("2026-06-14T08:02:06Z").epochSecond
            val videoStartFit = videoStartUtcSeconds - fitEpochSec
            val videoEndFit = (videoStartUtcSeconds + 5) - fitEpochSec // 5 seconds
            
            println("videoStartFit: $videoStartFit")
            println("videoEndFit: $videoEndFit")
            
            val matchCount = record20s.count { it.data.fields[253]?.value in videoStartFit..videoEndFit }
            println("Matching points for [0, 5]: $matchCount")

            val trimStartFit = videoStartFit + 100
            val trimEndFit = videoStartFit + 110
            val trimMatchCount = record20s.count { it.data.fields[253]?.value in trimStartFit..trimEndFit }
            println("Matching points for [100, 110]: $trimMatchCount")
        }
    }
}
