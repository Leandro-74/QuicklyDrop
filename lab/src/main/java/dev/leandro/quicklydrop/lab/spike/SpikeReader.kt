package dev.leandro.quicklydrop.lab.spike

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep

/**
 * Spike sender side: reads the receiver's card and records the payload.
 *
 * The AID is chosen by parameter to exercise both cards (permissive and
 * `requireDeviceScreenOn`).
 */
class SpikeReader(private val activity: Activity) {

    companion object {
        private val GET_DATA = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)

        private const val FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    private val nfc = NfcAdapter.getDefaultAdapter(activity)
    private var callback: NfcAdapter.ReaderCallback? = null

    fun start(
        screenOn: Boolean,
        onPayload: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val adapter = nfc
        if (adapter == null) {
            onError("this device has no NFC")
            return
        }
        if (!adapter.isEnabled) {
            onError("NFC turned off")
            return
        }

        val aid = if (screenOn) SpikeApduBase.AID_SCREEN_ON else SpikeApduBase.AID_PERMISSIVE
        val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x06) + aid + byteArrayOf(0x00)
        val cb = NfcAdapter.ReaderCallback { tag -> readTag(tag, select, onPayload, onError) }
        callback = cb

        runCatching {
            adapter.enableReaderMode(activity, cb, FLAGS, null)
        }.onFailure { onError("reader mode failed: ${it.message}") }

        SpikeLog.event(activity, "reader.start", "aid=${aid.toHex()}")
    }

    private fun readTag(
        tag: Tag,
        select: ByteArray,
        onPayload: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            onError("tag without IsoDep")
            return
        }

        try {
            iso.connect()
            iso.timeout = 5_000

            val selected = iso.transceive(select)
            if (selected.size < 2 || selected.last() != 0x00.toByte()) {
                onError("SELECT rejected")
                return
            }

            val response = iso.transceive(GET_DATA)
            if (response.size < 3) {
                onError("short response")
                return
            }

            val payload = String(response.copyOfRange(0, response.size - 2), Charsets.UTF_8)
            SpikeLog.event(activity, "reader.payload", "bytes=${response.size - 2} ${payload.take(40)}")
            onPayload(payload)
            activity.runOnUiThread { stop() }
        } catch (e: Exception) {
            onError("NFC: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    fun stop() {
        if (callback == null) return
        callback = null
        runCatching { nfc?.disableReaderMode(activity) }
        SpikeLog.event(activity, "reader.stop")
    }
}
