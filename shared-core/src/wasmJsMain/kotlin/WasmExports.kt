import fit.FitParser
import mp4.Mp4Parser
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Float64Array
import org.khronos.webgl.get
import org.khronos.webgl.set

@OptIn(ExperimentalJsExport::class)
@JsExport
fun scanMp4Metadata(bytes: Int8Array): Float64Array? {
    val parser = Mp4Parser()
    val kBytes = bytes.toByteArray()
    val meta = parser.parse(kBytes) ?: return null
    val array = Float64Array(3)
    array[0] = meta.creationTimeSeconds.toDouble()
    array[1] = meta.timescale.toDouble()
    array[2] = meta.duration.toDouble()
    return array
}

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
