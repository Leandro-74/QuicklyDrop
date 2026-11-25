package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikePayloadTest {

    @Test
    fun `response carries payload and ok status`() {
        val response = SpikePayload.response("hi")

        assertArrayEquals("hi".toByteArray() + byteArrayOf(0x90.toByte(), 0x00), response)
    }

    @Test
    fun `null payload responds not found`() {
        assertArrayEquals(SpikePayload.notFound, SpikePayload.response(null))
        assertEquals("empty payload", SpikePayload.validate(null))
    }

    @Test
    fun `empty payload is rejected`() {
        assertFalse(SpikePayload.isValid(""))
        assertFalse(SpikePayload.isValid("   "))
    }

    @Test
    fun `limit counts bytes and not characters`() {
        // 126 two-byte characters = 252 bytes + 2 status = 254, it fits.
        val doisBytes = "ç".repeat(126)
        assertNull(SpikePayload.validate(doisBytes))

        // 127 two-byte characters = 254 + 2 = 256, it does not fit.
        val excedente = "ç".repeat(127)
        assertTrue(SpikePayload.validate(excedente)!!.contains("does not fit"))
    }

    @Test
    fun `payload at the exact limit fits`() {
        val texto = "a".repeat(SpikePayload.MAX_APDU - 2)

        assertNull(SpikePayload.validate(texto))
        assertEquals(SpikePayload.MAX_APDU, SpikePayload.response(texto).size)
    }

    @Test
    fun `payload above the limit responds not found`() {
        val texto = "a".repeat(SpikePayload.MAX_APDU - 1)

        assertArrayEquals(SpikePayload.notFound, SpikePayload.response(texto))
    }

    @Test
    fun `invalid payload does not expose data`() {
        val response = SpikePayload.response(null)

        assertEquals(2, response.size)
        assertEquals(0x6A.toByte(), response[0])
        assertEquals(0x82.toByte(), response[1])
    }
}
