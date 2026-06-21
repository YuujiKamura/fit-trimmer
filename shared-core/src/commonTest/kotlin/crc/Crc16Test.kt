package crc

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc16Test {
    @Test
    fun testSimpleCrc() {
        val data = "123456789".encodeToByteArray()
        val result = Crc16.calculate(data)
        assertEquals(47933, result)
    }
}
