import fit.FitParser
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Float64Array
import org.khronos.webgl.get
import org.khronos.webgl.set

@OptIn(ExperimentalJsExport::class)
@JsExport
fun trimFit(bytes: Int8Array, startUtc: Double, endUtc: Double): Int8Array {
    val kBytes = bytes.toByteArray()
    val parser = FitParser(kBytes)
    parser.parse()
    val trimmed = parser.trim(startUtc.toLong(), endUtc.toLong())
    return trimmed.toInt8Array()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun getFitTimestamps(bytes: Int8Array): Float64Array? {
    val kBytes = bytes.toByteArray()
    val parser = FitParser(kBytes)
    try {
        parser.parse()
    } catch (e: Exception) {
        return null
    }

    val telemetry = parser.getTelemetry()
    if (telemetry.isEmpty()) return null

    val array = Float64Array(telemetry.size)
    for (i in telemetry.indices) {
        array[i] = telemetry[i].timestamp
    }
    return array
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun getFitTelemetry(bytes: Int8Array): Float64Array? {
    val kBytes = bytes.toByteArray()
    val parser = FitParser(kBytes)
    try {
        parser.parse()
    } catch (e: Exception) {
        println("FIT Error: ${e.message}")
        return null
    }

    val telemetry = parser.getTelemetry()
    if (telemetry.isEmpty()) {
        println("FIT Error: No telemetry extracted via common parser.")
        return null
    }

    // Flatten data for JS: [ts, speed, power, cadence, hr, elev, grade, ...]
    val result = Float64Array(telemetry.size * 7)
    for (i in telemetry.indices) {
        val p = telemetry[i]
        val offset = i * 7
        result[offset] = p.timestamp
        result[offset + 1] = p.speed
        result[offset + 2] = p.power
        result[offset + 3] = p.cadence
        result[offset + 4] = p.heartRate
        result[offset + 5] = p.elevation
        result[offset + 6] = p.grade
    }

    println("FIT Success: Extracted ${telemetry.size} points via common core.")
    return result
}

private fun Int8Array.toByteArray(): ByteArray {
    val size = this.length
    val bytes = ByteArray(size)
    for (i in 0 until size) {
        bytes[i] = this[i]
    }
    return bytes
}

private fun ByteArray.toInt8Array(): Int8Array {
    val array = Int8Array(this.size)
    for (i in this.indices) {
        array[i] = this[i]
    }
    return array
}
