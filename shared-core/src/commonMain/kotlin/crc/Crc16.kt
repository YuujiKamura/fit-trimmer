package crc

object Crc16 {
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    fun calculate(bytes: ByteArray, initialCrc: Int = 0): Int {
        var crc = initialCrc
        for (b in bytes) {
            val byteVal = b.toInt() and 0xFF
            // First nibble
            var temp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor temp xor CRC_TABLE[byteVal and 0xF]
            // Second nibble
            temp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor temp xor CRC_TABLE[(byteVal ushr 4) and 0xF]
        }
        return crc
    }
}
