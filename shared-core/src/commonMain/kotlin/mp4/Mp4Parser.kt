package mp4

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readUInt

class Mp4Parser {
    data class Metadata(
        val creationTimeSeconds: Long,
        val timescale: Long,
        val duration: Long
    )

    fun parse(bytes: ByteArray): Metadata? {
        val buffer = Buffer().apply { write(bytes) }
        return scanAtoms(buffer, bytes.size.toLong())
    }

    private fun scanAtoms(buffer: Buffer, limit: Long): Metadata? {
        var bytesRead = 0L
        while (bytesRead < limit - 8) {
            if (buffer.size < 8) break
            val size = buffer.readUInt().toLong()
            val typeBytes = buffer.readByteArray(4)
            val type = typeBytes.decodeToString()
            bytesRead += 8

            if (size <= 0) break

            val bodySize = size - 8

            if (type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl") {
                val beforeSubSize = buffer.size
                val meta = scanAtoms(buffer, bodySize)
                if (meta != null) return meta
                val afterSubSize = buffer.size
                val consumed = beforeSubSize - afterSubSize
                val remaining = bodySize - consumed
                if (remaining > 0) {
                    buffer.skip(remaining)
                }
                bytesRead += bodySize
            } else if (type == "mvhd") {
                if (buffer.size < bodySize) break
                val version = buffer.readByte().toInt() and 0xFF
                buffer.skip(3) // Flags

                val creationTime: Long
                val timescale: Long
                val duration: Long
                
                val beforeRead = buffer.size
                if (version == 1) {
                    buffer.skip(4) // Skip creation_time high
                    creationTime = buffer.readUInt().toLong()
                    buffer.skip(8) // Skip modification_time
                    timescale = buffer.readUInt().toLong()
                    buffer.skip(4) // Skip duration high
                    duration = buffer.readUInt().toLong()
                } else {
                    creationTime = buffer.readUInt().toLong()
                    buffer.skip(4) // Skip modification_time
                    timescale = buffer.readUInt().toLong()
                    duration = buffer.readUInt().toLong()
                }
                val afterRead = buffer.size
                val readBytes = beforeRead - afterRead
                val remaining = bodySize - (1 + 3 + readBytes)
                if (remaining > 0) {
                    buffer.skip(remaining)
                }
                return Metadata(creationTime, timescale, duration)
            } else {
                if (buffer.size < bodySize) break
                buffer.skip(bodySize)
                bytesRead += bodySize
            }
        }
        return null
    }
}
