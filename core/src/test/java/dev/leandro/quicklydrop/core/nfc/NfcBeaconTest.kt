package dev.leandro.quicklydrop.core.nfc

import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NfcBeaconTest {

    private fun payload(): DiscoveryPayload =
        DiscoveryPayload(
            name = "Receiver",
            ssid = "DIRECT-QD-TEST",
            pass = "12345678",
            frequencyMhz = 5745,
            noisePublicKey = ByteArray(32) { 7 },
        )

    @Test
    fun `arm publishes the encoded payload`() {
        val discovery = payload()

        NfcBeacon.arm(discovery)

        assertArrayEquals(discovery.encode(), NfcBeacon.payload)
        NfcBeacon.disarm()
        assertNull(NfcBeacon.payload)
    }

    @Test
    fun `arm replaces the read callback`() {
        var calls = 0
        NfcBeacon.arm(payload()) { calls++ }

        assertNotNull(NfcBeacon.onRead)
        NfcBeacon.onRead?.invoke()
        assertEquals(1, calls)

        NfcBeacon.disarm()
        assertNull(NfcBeacon.onRead)
    }
}
