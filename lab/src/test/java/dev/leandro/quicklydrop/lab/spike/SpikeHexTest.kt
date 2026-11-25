package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeHexTest {

    @Test
    fun `converts bytes to uppercase hex`() {
        assertEquals("F0007F", byteArrayOf(0xF0.toByte(), 0x00, 0x7F).toHex())
    }

    @Test
    fun `empty array becomes empty string`() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `preserves leading zero`() {
        assertEquals("000A0B", byteArrayOf(0x00, 0x0A, 0x0B).toHex())
    }
}
