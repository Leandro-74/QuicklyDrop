package dev.leandro.quicklydrop.lab

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * QuicklyDrop emulated card. It is not NDEF: it is a custom AID with two commands —
 * SELECT and GET DATA — enough to deliver the hotspot SSID and passphrase in one tap.
 */
class QuicklyDropApduService : HostApduService() {

    companion object {
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)

        /** Filled by NFC mode when the hotspot is up. */
        @Volatile
        var payload: ByteArray? = null

        /** Notifies the app that someone read the card. */
        @Volatile
        var onRead: (() -> Unit)? = null
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 2) return UNKNOWN

        val ins = commandApdu[1]
        return when {
            commandApdu[0] == 0x00.toByte() && ins == 0xA4.toByte() -> OK

            commandApdu[0] == 0x80.toByte() && ins == 0xCA.toByte() -> {
                val data = payload ?: return NOT_FOUND
                onRead?.invoke()
                data + OK
            }

            else -> UNKNOWN
        }
    }

    override fun onDeactivated(reason: Int) = Unit
}
