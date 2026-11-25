package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeHexTest {

    @Test
    fun `converte bytes para hex maiusculo`() {
        assertEquals("F0007F", byteArrayOf(0xF0.toByte(), 0x00, 0x7F).toHex())
    }

    @Test
    fun `array vazio vira string vazia`() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `preserva zero a esquerda`() {
        assertEquals("000A0B", byteArrayOf(0x00, 0x0A, 0x0B).toHex())
    }
}
