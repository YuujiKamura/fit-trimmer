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

    val timestamps = mutableListOf<Double>()
    for (r in parser.records) {
        if (r is FitParser.FitRecord.Data && r.globalMessageNumber == 20) {
            val tsField = r.data.fields[253]
            if (tsField?.value != null) {
                timestamps.add(tsField.value.toDouble())
            }
        }
    }

    if (timestamps.isEmpty()) return null

    val array = Float64Array(timestamps.size)
    for (i in timestamps.indices) {
        array[i] = timestamps[i]
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
        println("FIT Parse Exception: ${e.message}")
        return null
    }

    val list = mutableListOf<Double>()
    for (r in parser.records) {
        if (r is FitParser.FitRecord.Data && r.globalMessageNumber == 20) {
            val tsField = r.data.fields[253]
            if (tsField?.value != null) {
                val ts = tsField.value.toDouble()
                
                val rawSpeed = r.data.fields[6]?.value
                val speedVal = if (rawSpeed != null) (rawSpeed.toDouble() / 1000.0) * 3.6 else 0.0
                
                val powerVal = r.data.fields[7]?.value?.toDouble() ?: 0.0
                val cadenceVal = r.data.fields[4]?.value?.toDouble() ?: 0.0
                val hrVal = r.data.fields[3]?.value?.toDouble() ?: 0.0
                
                val rawElev = r.data.fields[2]?.value
                val elevVal = if (rawElev != null) (rawElev.toDouble() / 5.0) - 500.0 else 0.0
                
                val rawGrade = r.data.fields[9]?.value
                val gradeVal = if (rawGrade != null) rawGrade.toDouble() / 100.0 else 0.0
                
                list.add(ts)
                list.add(speedVal)
                list.add(powerVal)
                list.add(cadenceVal)
                list.add(hrVal)
                list.add(elevVal)
                list.add(gradeVal)
            }
        }
    }

    if (list.isEmpty()) return null

    val array = Float64Array(list.size)
    for (i in list.indices) {
        array[i] = list[i]
    }
    return array
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
