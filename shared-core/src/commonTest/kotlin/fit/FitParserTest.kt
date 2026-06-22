package fit

import crc.Crc16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitParserTest {

    @Test
    fun testParseAndTrim() {
        val headerSize = 14
        val recordsSize = 39
        val totalSize = headerSize + recordsSize + 2
        val bytes = ByteArray(totalSize)

        // 1. Construct Header
        bytes[0] = headerSize.toByte()
        bytes[1] = 32 // Protocol Version
        // Profile Version = 2012 (0x07DC) -> DC, 07
        bytes[2] = 0xDC.toByte()
        bytes[3] = 0x07.toByte()
        // Records Size = 39 (0x00000027) -> 27, 00, 00, 00
        bytes[4] = 0x27.toByte()
        bytes[5] = 0x00.toByte()
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x00.toByte()
        // Signature = ".FIT"
        ".FIT".encodeToByteArray().copyInto(bytes, 8)

        // 2. Definition Record
        var offset = headerSize
        bytes[offset] = 0x40.toByte() // Definition header, local ID 0
        bytes[offset + 1] = 0 // Reserved
        bytes[offset + 2] = 0 // Little Endian
        // Global Message Number = 20 (0x0014) -> 14, 00
        bytes[offset + 3] = 0x14.toByte()
        bytes[offset + 4] = 0x00.toByte()
        bytes[offset + 5] = 2 // Field count

        // Field 1: timestamp (num 253, size 4, type uint32 0x86)
        bytes[offset + 6] = 253.toByte()
        bytes[offset + 7] = 4.toByte()
        bytes[offset + 8] = 0x86.toByte()

        // Field 2: distance (num 5, size 4, type uint32 0x86)
        bytes[offset + 9] = 5.toByte()
        bytes[offset + 10] = 4.toByte()
        bytes[offset + 11] = 0x86.toByte()

        offset += 12 // Definition record size is 12

        // Data Record 1: timestamp = 1000000000 (0x3B9ACA00), distance = 10000 (0x00002710)
        bytes[offset] = 0x00.toByte() // Data header, local ID 0
        // timestamp: 00, CA, 9A, 3B
        bytes[offset + 1] = 0x00.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 10, 27, 00, 00
        bytes[offset + 5] = 0x10.toByte()
        bytes[offset + 6] = 0x27.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // Data Record 2: timestamp = 1000000010 (0x3B9ACA0A), distance = 20000 (0x00004E20)
        bytes[offset] = 0x00.toByte()
        // timestamp: 0A, CA, 9A, 3B
        bytes[offset + 1] = 0x0A.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 20, 4E, 00, 00
        bytes[offset + 5] = 0x20.toByte()
        bytes[offset + 6] = 0x4E.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // Data Record 3: timestamp = 1000000020 (0x3B9ACA14), distance = 30000 (0x00007530)
        bytes[offset] = 0x00.toByte()
        // timestamp: 14, CA, 9A, 3B
        bytes[offset + 1] = 0x14.toByte()
        bytes[offset + 2] = 0xCA.toByte()
        bytes[offset + 3] = 0x9A.toByte()
        bytes[offset + 4] = 0x3B.toByte()
        // distance: 30, 75, 00, 00
        bytes[offset + 5] = 0x30.toByte()
        bytes[offset + 6] = 0x75.toByte()
        bytes[offset + 7] = 0x00.toByte()
        bytes[offset + 8] = 0x00.toByte()

        offset += 9

        // 3. Compute CRCs for initial file
        val headerCrc = Crc16.calculate(bytes, offset = 0, length = 12)
        FitParser.setUShort(bytes, 12, headerCrc, true)

        val fileCrc = Crc16.calculate(bytes, offset = 0, length = headerSize + recordsSize)
        FitParser.setUShort(bytes, headerSize + recordsSize, fileCrc, true)

        // 4. Parse the original FIT file
        val parser = FitParser(bytes)
        parser.parse()

        assertEquals(14, parser.headerSize)
        assertEquals(39, parser.recordsSize)
        assertEquals(4, parser.records.size) // 1 Definition + 3 Data

        // 5. Trim
        // FIT epoch = 631065600
        // Record 1 FIT ts: 1000000000 -> Unix: 1631065600
        // Record 2 FIT ts: 1000000010 -> Unix: 1631065610
        // Record 3 FIT ts: 1000000020 -> Unix: 1631065620
        // We trim from 1631065605 to 1631065625
        val trimmedBytes = parser.trim(1631065605L, 1631065625L)

        // Trimmed size should be: header (14) + new records (12 def + 9 * 2 data = 30) + crc (2) = 46
        assertEquals(46, trimmedBytes.size)

        // Parse trimmed bytes to check structure
        val trimmedParser = FitParser(trimmedBytes)
        trimmedParser.parse()

        assertEquals(30, trimmedParser.recordsSize)
        assertEquals(3, trimmedParser.records.size) // 1 Definition + 2 Data

        // Check new values
        val data1 = trimmedParser.records[1] as FitParser.FitRecord.Data
        assertEquals(1000000010L, data1.data.fields[253]?.value)
        assertEquals(0L, data1.data.fields[5]?.value) // 20000 - 20000 = 0

        val data2 = trimmedParser.records[2] as FitParser.FitRecord.Data
        assertEquals(1000000020L, data2.data.fields[253]?.value)
        assertEquals(10000L, data2.data.fields[5]?.value) // 30000 - 20000 = 10000

        // Verify CRCs are correct
        val newHeaderCrc = Crc16.calculate(trimmedBytes, offset = 0, length = 12)
        val parsedNewHeaderCrc = FitParser.getUShort(trimmedBytes, 12, true)
        assertEquals(newHeaderCrc, parsedNewHeaderCrc)

        val newFileCrc = Crc16.calculate(trimmedBytes, offset = 0, length = 44)
        val parsedNewFileCrc = FitParser.getUShort(trimmedBytes, 44, true)
        assertEquals(newFileCrc, parsedNewFileCrc)
    }
}
