package dev.leandro.quicklydrop.core.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApduHandlerTest {

    private val payload = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `select returns success`() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), ApduHandler.handle(ApduHandler.SELECT_APDU, payload))
    }

    @Test
    fun `select with a different aid is rejected`() {
        val wrong = ApduHandler.SELECT_APDU.copyOf()
        wrong[wrong.size - 3] = (wrong[wrong.size - 3].toInt() xor 1).toByte()

        assertFalse(ApduHandler.isSelect(wrong))
        assertArrayEquals(byteArrayOf(0x6D, 0x00), ApduHandler.handle(wrong, payload))
    }

    @Test
    fun `get data returns the payload and the status word`() {
        val response = ApduHandler.handle(ApduHandler.GET_DATA_APDU, payload)

        assertArrayEquals(payload + byteArrayOf(0x90.toByte(), 0x00), response)
    }

    @Test
    fun `get data without an armed payload is not found`() {
        assertArrayEquals(
            byteArrayOf(0x6A, 0x82.toByte()),
            ApduHandler.handle(ApduHandler.GET_DATA_APDU, null),
        )
        assertArrayEquals(
            byteArrayOf(0x6A, 0x82.toByte()),
            ApduHandler.handle(ApduHandler.GET_DATA_APDU, ByteArray(0)),
        )
    }

    @Test
    fun `unknown instructions are rejected`() {
        assertArrayEquals(
            byteArrayOf(0x6D, 0x00),
            ApduHandler.handle(byteArrayOf(0x00, 0xB0.toByte()), payload),
        )
        assertArrayEquals(byteArrayOf(0x6D, 0x00), ApduHandler.handle(null, payload))
        assertArrayEquals(byteArrayOf(0x6D, 0x00), ApduHandler.handle(byteArrayOf(0x00), payload))
    }

    @Test
    fun `get data detection ignores select`() {
        assertTrue(ApduHandler.isGetData(ApduHandler.GET_DATA_APDU))
        assertFalse(ApduHandler.isGetData(ApduHandler.SELECT_APDU))
        assertFalse(ApduHandler.isGetData(null))
    }
}
