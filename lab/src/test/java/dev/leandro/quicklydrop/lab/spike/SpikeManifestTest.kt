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
    fun `cartoes hce do spike estao declarados`() {
        val packageManager = context.packageManager

        listOf(SpikeApduService::class.java, SpikeScreenOnApduService::class.java).forEach { service ->
            val info = packageManager.getServiceInfo(ComponentName(context, service), 0)
            assertNotNull("serviço $service ausente", info)
        }
    }

    @Test
    fun `servico em primeiro plano usa connectedDevice`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, SpikeForegroundService::class.java),
            0,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
    }

    @Test
    fun `receiver de transacao nfc esta declarado`() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, SpikeReceiver::class.java),
            PackageManager.GET_META_DATA,
        )

        assertNotNull(info)
    }

    @Test
    fun `cartao permissivo nao exige tela ligada`() {
        val attributes = hceAttributes(R.xml.apduservice_spike)

        assertEquals("false", attributes["requireDeviceScreenOn"])
        assertEquals("false", attributes["requireDeviceUnlock"])
        assertEquals(listOf("F05144524F51"), attributes["aids"])
    }

    @Test
    fun `cartao estrito exige tela ligada`() {
        val attributes = hceAttributes(R.xml.apduservice_spike_screen_on)

        assertEquals("true", attributes["requireDeviceScreenOn"])
        assertEquals(listOf("F05144524F52"), attributes["aids"])
    }

    /** Lê o XML do HCE e devolve os atributos que importam para o spike. */
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

        assertTrue("nenhum AID encontrado", aids.isNotEmpty())
        return mapOf(
            "requireDeviceScreenOn" to (screenOn ?: ""),
            "requireDeviceUnlock" to (unlock ?: ""),
            "aids" to aids,
        )
    }
}
