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
            println("First timestamp: ${first.fields[253]?.value}")
            println("Last timestamp: ${last.fields[253]?.value}")
            
            // Check for speed/power fields
            val sample = record20s[record20s.size / 2].data
            println("Sample Fields:")
            sample.fields.forEach { (num, field) ->
                println("  Field $num: value=${field.value}")
            }
        } else {
            println("No msg 20 records found!")
            val msgCounts = parser.records.filterIsInstance<FitParser.FitRecord.Data>()
                .groupingBy { it.globalMessageNumber }
                .eachCount()
            println("Message counts by global ID: $msgCounts")
        }
        
        assertTrue(record20s.isNotEmpty(), "Should have found data points")
    }
}
