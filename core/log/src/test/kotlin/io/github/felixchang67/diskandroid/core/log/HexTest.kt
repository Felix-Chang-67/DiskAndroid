package io.github.felixchang67.diskandroid.core.log

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HexTest {
    @Test
    fun encodeWithSeparator() {
        assertEquals("55 53 42 43", Hex.encode(byteArrayOf(0x55, 0x53, 0x42, 0x43)))
        assertEquals("00FF7F80", Hex.compact(byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte())))
    }

    @Test
    fun encodeRange() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals("02:03:04", Hex.encode(bytes, offset = 1, length = 3, separator = ":"))
    }

    @Test
    fun fixedWidthHex() {
        assertEquals("0411", Hex.hex(0x411, 4))
        assertEquals("0A", Hex.byte(10))
        assertEquals("FFFF", Hex.word(-1))
    }

    @Test
    fun dumpShowsOffsetsHexAndAscii() {
        val bytes = "USBC".toByteArray() + byteArrayOf(0x01, 0x00, 0x00, 0x00) + ByteArray(10) { 0x41 }
        val lines = Hex.dump(bytes).lines()
        assertEquals(2, lines.size)
        assertEquals("0000: 55 53 42 43 01 00 00 00 41 41 41 41 41 41 41 41  |USBC....AAAAAAAA|", lines[0])
        assertEquals("0010: 41 41                                            |AA|", lines[1])
    }

    @Test
    fun decodeIgnoresWhitespace() {
        assertContentEquals(byteArrayOf(0x12, 0xAB.toByte(), 0x00), Hex.decode("12 ab\n00"))
    }
}
