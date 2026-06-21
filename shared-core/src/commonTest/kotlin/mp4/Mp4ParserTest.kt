package mp4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

class Mp4ParserTest {
    @Test
    fun testParseMvhdVersion0() {
        val mvhdBuffer = Buffer().apply {
            writeInt(100) // size (100 bytes total)
            write("mvhd".encodeToByteArray()) // type
            writeByte(0) // version
            write(byteArrayOf(0, 0, 0)) // flags
            writeInt(1234567890) // creationTime
            write(byteArrayOf(0, 0, 0, 0)) // modificationTime
            writeInt(1000) // timescale
            writeInt(60000) // duration
            write(ByteArray(72)) // reserved remaining
        }

        val moovBuffer = Buffer().apply {
            writeInt(108) // total size: 8 + 100
            write("moov".encodeToByteArray()) // type
            write(mvhdBuffer.readByteArray()) // children
        }

        val bytes = moovBuffer.readByteArray()
        val parser = Mp4Parser()
        val metadata = parser.parse(bytes)

        assertNotNull(metadata)
        assertEquals(1234567890L, metadata.creationTimeSeconds)
        assertEquals(1000L, metadata.timescale)
        assertEquals(60000L, metadata.duration)
    }
}
