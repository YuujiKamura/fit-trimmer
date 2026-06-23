package mp4

class Mp4Parser {
    data class Metadata(
        val creationTimeSeconds: Long,
        val timescale: Long,
        val duration: Long
    )

    fun parse(bytes: ByteArray): Metadata? {
        // Direct byte scan for 'mvhd' to avoid memory copy and handle fragmented/trailer moov
        for (i in 0 until bytes.size - 32) {
            // 'mvhd' signature
            if (bytes[i] == 109.toByte() && bytes[i+1] == 118.toByte() && 
                bytes[i+2] == 104.toByte() && bytes[i+3] == 100.toByte()) {
                
                val version = bytes[i + 4].toInt() and 0xFF
                var offset = i + 8 // skip type and version/flags
                
                return if (version == 1) {
                    offset += 8 // skip creation (high)
                    val creation = getUInt(bytes, offset)
                    offset += 12 // skip modification (8) + timescale (4)
                    val timescale = getUInt(bytes, offset - 4)
                    offset += 4 // skip duration (high)
                    val duration = getUInt(bytes, offset)
                    Metadata(creation, timescale, duration)
                } else {
                    val creation = getUInt(bytes, offset)
                    offset += 8 // skip modification
                    val timescale = getUInt(bytes, offset)
                    offset += 4
                    val duration = getUInt(bytes, offset)
                    Metadata(creation, timescale, duration)
                }
            }
        }
        return null
    }

    private fun getUInt(b: ByteArray, o: Int): Long {
        if (o + 3 >= b.size) return 0L
        return ((b[o].toLong() and 0xFF) shl 24) or
               ((b[o+1].toLong() and 0xFF) shl 16) or
               ((b[o+2].toLong() and 0xFF) shl 8) or
               (b[o+3].toLong() and 0xFF)
    }
}
