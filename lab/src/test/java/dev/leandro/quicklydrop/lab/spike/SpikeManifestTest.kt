package dev.leandro.quicklydrop.lab.spike

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import dev.leandro.quicklydrop.lab.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpikeManifestTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `spike hce cards are declared`() {
        val packageManager = context.packageManager

        listOf(SpikeApduService::class.java, SpikeScreenOnApduService::class.java).forEach { service ->
            val info = packageManager.getServiceInfo(ComponentName(context, service), 0)
            assertNotNull("service $service missing", info)
        }
    }

    @Test
    fun `foreground service uses connectedDevice`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, SpikeForegroundService::class.java),
            0,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
    }

    @Test
    fun `nfc transaction receiver is declared`() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, SpikeReceiver::class.java),
            PackageManager.GET_META_DATA,
        )

        assertNotNull(info)
    }

    @Test
    fun `permissive card does not require screen on`() {
        val attributes = hceAttributes(R.xml.apduservice_spike)

        assertEquals("false", attributes["requireDeviceScreenOn"])
        assertEquals("false", attributes["requireDeviceUnlock"])
        assertEquals(listOf("F05144524F51"), attributes["aids"])
    }

    @Test
    fun `strict card requires screen on`() {
        val attributes = hceAttributes(R.xml.apduservice_spike_screen_on)

        assertEquals("true", attributes["requireDeviceScreenOn"])
        assertEquals(listOf("F05144524F52"), attributes["aids"])
    }

    /** Reads the HCE XML and returns the attributes that matter for the spike. */
    private fun hceAttributes(resource: Int): Map<String, Any> {
        val parser: XmlResourceParser = context.resources.getXml(resource)
        val aids = mutableListOf<String>()
        var screenOn: String? = null
        var unlock: String? = null

        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType != XmlResourceParser.START_TAG) continue

            when (parser.name) {
                "host-apdu-service" -> {
                    screenOn = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android",
                        "requireDeviceScreenOn",
                    )
                    unlock = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android",
                        "requireDeviceUnlock",
                    )
                }

                "aid-filter" -> parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android",
                    "name",
                )?.let { aids += it }
            }
        }
        parser.close()

        assertTrue("no AID found", aids.isNotEmpty())
        return mapOf(
            "requireDeviceScreenOn" to (screenOn ?: ""),
            "requireDeviceUnlock" to (unlock ?: ""),
            "aids" to aids,
        )
    }
}
