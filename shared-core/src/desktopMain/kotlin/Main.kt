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

    println("Reading FIT file: ${fitFile.absolutePath} (${fitFile.length()} bytes)")
    val bytes = fitFile.readBytes()
    
    val parser = FitParser(bytes)
    try {
        println("Starting parse...")
        parser.parse()
        println("Parse completed successfully.")
        
        var msg20Count = 0
        var tsFoundCount = 0
        val telemetryList = mutableListOf<List<Double>>()

        for (r in parser.records) {
            if (r is FitParser.FitRecord.Data && r.globalMessageNumber == 20) {
                msg20Count++
                val tsField = r.data.fields[253]
                if (tsField?.value != null) {
                    tsFoundCount++
                    val ts = tsField.value.toDouble()
                    val rawSpeed = r.data.fields[73]?.value ?: r.data.fields[6]?.value
                    val speedVal = if (rawSpeed != null) (rawSpeed.toDouble() / 1000.0) * 3.6 else 0.0
                    val powerVal = r.data.fields[7]?.value?.toDouble() ?: 0.0
                    val cadenceVal = r.data.fields[4]?.value?.toDouble() ?: 0.0
                    val hrVal = r.data.fields[3]?.value?.toDouble() ?: 0.0
                    val rawElev = r.data.fields[78]?.value ?: r.data.fields[2]?.value
                    val elevVal = if (rawElev != null) (rawElev.toDouble() / 5.0) - 500.0 else 0.0
                    val rawGrade = r.data.fields[9]?.value
                    val gradeVal = if (rawGrade != null) rawGrade.toDouble() / 100.0 else 0.0
                    
                    telemetryList.add(listOf(ts, speedVal, powerVal, cadenceVal, hrVal, elevVal, gradeVal))
                }
            }
        }

        println("FIT Stats: Total Records=${parser.records.size}, Msg20=$msg20Count, TsFound=$tsFoundCount")
        
        if (telemetryList.isNotEmpty()) {
            val first = telemetryList.first()
            val last = telemetryList.last()
            println("First data: TS=${first[0]}, Speed=${first[1]}, Power=${first[2]}")
            println("Last data:  TS=${last[0]}, Speed=${last[1]}, Power=${last[2]}")
        } else {
            println("ERROR: No telemetry data extracted!")
        }

    } catch (e: Exception) {
        println("CRITICAL ERROR during parse:")
        e.printStackTrace()
    }
}
