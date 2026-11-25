package dev.leandro.quicklydrop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P2pConfigTest {

    @Test
    fun `group always carries ssid and passphrase`() {
        val config = P2pConfig.group("DIRECT-ED-ABCD", "12345678", 5745)

        assertEquals("DIRECT-ED-ABCD", config.networkName)
        assertEquals("12345678", config.passphrase)
        assertEquals(5745, config.groupOwnerBand)
    }

    @Test
    fun `without frequency it still carries credentials`() {
        val config = P2pConfig.group("DIRECT-ED-ABCD", "12345678")

        assertNotNull(config.networkName)
        assertNotNull(config.passphrase)
    }
}
