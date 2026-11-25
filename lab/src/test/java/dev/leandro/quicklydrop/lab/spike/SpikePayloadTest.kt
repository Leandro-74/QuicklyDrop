package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikePayloadTest {

    @Test
    fun `resposta carrega payload e status ok`() {
        val response = SpikePayload.response("ola")

        assertArrayEquals("ola".toByteArray() + byteArrayOf(0x90.toByte(), 0x00), response)
    }

    @Test
    fun `payload nulo responde nao encontrado`() {
        assertArrayEquals(SpikePayload.notFound, SpikePayload.response(null))
        assertEquals("payload vazio", SpikePayload.validate(null))
    }

    @Test
    fun `payload vazio e rejeitado`() {
        assertFalse(SpikePayload.isValid(""))
        assertFalse(SpikePayload.isValid("   "))
    }

    @Test
    fun `limite conta bytes e nao caracteres`() {
        // 126 caracteres de dois bytes = 252 bytes + 2 de status = 254, cabe.
        val doisBytes = "ç".repeat(126)
        assertNull(SpikePayload.validate(doisBytes))

        // 127 caracteres de dois bytes = 254 + 2 = 256, não cabe.
        val excedente = "ç".repeat(127)
        assertTrue(SpikePayload.validate(excedente)!!.contains("não cabe"))
    }

    @Test
    fun `payload no limite exato cabe`() {
        val texto = "a".repeat(SpikePayload.MAX_APDU - 2)

        assertNull(SpikePayload.validate(texto))
        assertEquals(SpikePayload.MAX_APDU, SpikePayload.response(texto).size)
    }

    @Test
    fun `payload acima do limite responde nao encontrado`() {
        val texto = "a".repeat(SpikePayload.MAX_APDU - 1)

        assertArrayEquals(SpikePayload.notFound, SpikePayload.response(texto))
    }

    @Test
    fun `payload invalido nao expoe dados`() {
        val response = SpikePayload.response(null)

        assertEquals(2, response.size)
        assertEquals(0x6A.toByte(), response[0])
        assertEquals(0x82.toByte(), response[1])
    }
}
