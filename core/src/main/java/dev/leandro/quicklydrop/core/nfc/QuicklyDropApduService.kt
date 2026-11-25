package dev.leandro.quicklydrop.core.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * HCE card registered by the product app.
 *
 * It answers SELECT and GET DATA. GET DATA publishes [NfcBeacon.payload] and notifies
 * the receive session that the phone was tapped.
 */
class QuicklyDropApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val payload = NfcBeacon.payload
        val response = ApduHandler.handle(commandApdu, payload)
        if (payload != null && ApduHandler.isGetData(commandApdu)) {
            NfcBeacon.onRead?.invoke()
        }
        return response
    }

    override fun onDeactivated(reason: Int) = Unit
}
