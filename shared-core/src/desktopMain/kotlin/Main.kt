import fit.FitParser
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: FitTrimmerCLI <path_to_fit>")
        return
    }

    val fitFile = File(args[0])
    if (!fitFile.exists()) {
        println("File not found: ${args[0]}")
        return
    }

    println("Reading FIT file: ${fitFile.absolutePath}")
    val bytes = fitFile.readBytes()
    val parser = FitParser(bytes)
    
    try {
        println("Parsing via Common Core...")
        parser.parse()
        
        val telemetry = parser.getTelemetry()
        println("--- Parse Results ---")
        println("Total Records: ${parser.records.size}")
        println("Telemetry Points: ${telemetry.size}")
        
        if (telemetry.isNotEmpty()) {
            val first = telemetry.first()
            val last = telemetry.last()
            println("First: TS=${first.timestamp}, Speed=${first.speed}, Power=${first.power}")
            println("Last:  TS=${last.timestamp}, Speed=${last.speed}, Power=${last.power}")
            println("SUCCESS: Common core logic is compatible with this file.")
        } else {
            println("ERROR: No telemetry found. Possible field mapping issue.")
        }

    } catch (e: Exception) {
        println("CRITICAL ERROR:")
        e.printStackTrace()
    }
}
