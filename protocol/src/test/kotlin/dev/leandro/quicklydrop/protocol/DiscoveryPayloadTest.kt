package dev.leandro.quicklydrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPayloadTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun sample(
        name: String = "S21",
        ssid: String = "QuicklyDrop_1234",
        pass: String = "12345678",
        frequency: Int = 5745,
        transport: Transport = Transport.P2P,
    ) = DiscoveryPayload(name, ssid, pass, frequency, key, transport)

    @Test
    fun `round trip preserves the fields`() {
        val original = sample()
        val decoded = DiscoveryPayload.decode(original.encode())

        assertEquals(original.name, decoded.name)
        assertEquals(original.ssid, decoded.ssid)
        assertEquals(original.pass, decoded.pass)
        assertEquals(original.frequencyMhz, decoded.frequencyMhz)
        assertEquals(original.transport, decoded.transport)
        assertArrayEquals(key, decoded.noisePublicKey)
    }

    @Test
    fun `accepts a newline in the name and the ssid`() {
        val original = sample(name = "S21\nA", ssid = "Easy\nDrop")
        val decoded = DiscoveryPayload.decode(original.encode())

        assertEquals("S21\nA", decoded.name)
        assertEquals("Easy\nDrop", decoded.ssid)
    }

    @Test
    fun `accepts an open network without a password`() {
        val original = sample(ssid = "Open", pass = "")
        val decoded = DiscoveryPayload.decode(original.encode())

        assertEquals("Open", decoded.ssid)
        assertEquals("", decoded.pass)
    }

    @Test
    fun `accepts a payload without credentials`() {
        val original = sample(ssid = "", pass = "")
        val decoded = DiscoveryPayload.decode(original.encode())

        assertEquals("", decoded.ssid)
        assertEquals("", decoded.pass)
    }

    @Test
    fun `flags encode transport and capabilities`() {
        val p2p = sample(transport = Transport.P2P).encode()
        val hotspot = sample(transport = Transport.HOTSPOT).encode()
        val lan = sample(transport = Transport.LAN).encode()

        assertEquals(0x0D, p2p[1].toInt() and 0x0F)
        assertEquals(0x0E, hotspot[1].toInt() and 0x0F)
        assertEquals(0x0F, lan[1].toInt() and 0x0F)
        assertEquals(0x00, p2p[1].toInt() and 0xF0)

        val semCredenciais = sample(ssid = "", pass = "").encode()
        assertEquals(0x09, semCredenciais[1].toInt() and 0x0F)
    }

    @Test
    fun `maximum payload fits the short apdu limit`() {
        val original = sample(
            name = "n".repeat(DiscoveryPayload.MAX_NAME),
            ssid = "s".repeat(DiscoveryPayload.MAX_SSID),
            pass = "p".repeat(DiscoveryPayload.MAX_PASS),
        )
        val encoded = original.encode()

        assertTrue(encoded.size <= DiscoveryPayload.MAX_SIZE)
        assertTrue(encoded.size <= 255)
        assertEquals(original, DiscoveryPayload.decode(encoded))
    }

    @Test
    fun `rejects an unknown version`() {
        val bytes = sample().encode()
        bytes[0] = 2

        val error = assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun `rejects invalid utf8`() {
        val bytes = sample(name = "A").encode()
        bytes[2] = 1
        bytes[3] = 0xFF.toByte()

        assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
    }

    @Test
    fun `rejects a truncated payload`() {
        val bytes = sample().encode().copyOf(sample().encode().size - 1)

        assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
    }

    @Test
    fun `rejects extra bytes`() {
        val bytes = sample().encode() + byteArrayOf(0)

        assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
    }

    @Test
    fun `rejects missing credentials when the flag is set`() {
        val bytes = sample(ssid = "", pass = "").encode()
        bytes[1] = (bytes[1].toInt() or 0x04).toByte()

        assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
    }

    @Test
    fun `rejects unexpected credentials when the flag is clear`() {
        val bytes = sample().encode()
        bytes[1] = (bytes[1].toInt() and 0xFB).toByte()

        assertThrows(ProtocolException::class.java) {
            DiscoveryPayload.decode(bytes)
        }
    }

    @Test
    fun `rejects a noise key of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryPayload("S21", "ssid", "pass", 5745, ByteArray(31))
        }
    }

    @Test
    fun `rejects an empty name`() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(name = "")
        }
    }

    @Test
    fun `rejects a password without an ssid`() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(ssid = "", pass = "12345678")
        }
    }

    @Test
    fun `does not expose the password in toString`() {
        val text = sample().toString()

        assertTrue(text.contains("pass=***"))
        assertTrue(!text.contains("12345678"))
    }
}
