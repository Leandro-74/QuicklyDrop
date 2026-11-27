package dev.leandro.quicklydrop.core.p2p

import android.net.wifi.WifiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FakeWifiP2pShadow::class])
class AndroidP2pTransportTest {

    private val context = RuntimeEnvironment.getApplication()

    private val transport = AndroidP2pTransport(
        context,
        operationTimeoutMs = 1_000,
        groupTimeoutMs = 1_000,
    )

    @Before
    fun setUp() {
        FakeWifiP2pShadow.reset()
        wifi().setWifiEnabled(true)
    }

    @After
    fun tearDown() {
        transport.close()
        wifi().setWifiEnabled(true)
    }

    @Test
    fun `waits for group removal before creating a new group`() {
        FakeWifiP2pShadow.groupPresent = true
        FakeWifiP2pShadow.groupInfoLagAfterRemove = 2
        // The first create lands while the framework is still removing the old group.
        FakeWifiP2pShadow.createGroupBusy = 1

        transport.removeGroup()
        val frequency = transport.createGroup("DIRECT-QD-AAAA", "password", 5745)

        assertEquals(5745, frequency)
        assertEquals(1, FakeWifiP2pShadow.removeGroupCalls)
        assertEquals(2, FakeWifiP2pShadow.createGroupCalls)
    }

    @Test
    fun `does not remove a group that does not exist`() {
        val frequency = transport.createGroup("DIRECT-QD-AAAA", "password", 5745)

        assertEquals(5745, frequency)
        assertEquals(0, FakeWifiP2pShadow.removeGroupCalls)
        assertEquals(1, FakeWifiP2pShadow.createGroupCalls)
    }

    @Test
    fun `reports wi-fi off instead of busy`() {
        wifi().setWifiEnabled(false)

        val error = assertThrows(P2pException::class.java) {
            transport.createGroup("DIRECT-QD-AAAA", "password", 5745)
        }

        assertEquals("Wi-Fi is off; turn it on to use Wi-Fi Direct", error.message)
        assertEquals(0, FakeWifiP2pShadow.createGroupCalls)
    }

    @Suppress("DEPRECATION")
    private fun wifi() = context.getSystemService(WifiManager::class.java)
}
