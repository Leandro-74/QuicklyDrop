package dev.leandro.quicklydrop.core.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import dev.leandro.quicklydrop.protocol.DiscoveryPayload

/**
 * Reader mode used by the sender.
 *
 * The callback is invoked on the main thread. [start] must be called again after
 * [stop] if the user wants to tap another receiver.
 */
class NfcReader(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: NfcAdapter.ReaderCallback? = null

    @Volatile
    private var active = false

    fun problem(): String? = when {
        adapter == null -> "This device does not support NFC"
        !adapter.isEnabled -> "NFC is turned off"
        else -> null
    }

    fun start(
        onPayload: (DiscoveryPayload) -> Unit,
        onError: (String) -> Unit,
    ) {
        val problem = problem()
        if (problem != null) {
            onError(problem)
            return
        }

        active = true
        val reader = NfcAdapter.ReaderCallback { tag -> onTag(tag, onPayload, onError) }
        callback = reader
        runCatching {
            adapter?.enableReaderMode(activity, reader, READER_FLAGS, null)
        }.onFailure {
            active = false
            callback = null
            onError("Failed to enable reader mode: ${it.message}")
        }
    }

    fun stop() {
        active = false
        if (callback == null) return
        callback = null
        runCatching { adapter?.disableReaderMode(activity) }
    }

    private fun onTag(
        tag: Tag,
        onPayload: (DiscoveryPayload) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!active) return
        val iso = IsoDep.get(tag) ?: return

        try {
            iso.connect()
            iso.timeout = 5_000

            val selected = iso.transceive(ApduHandler.SELECT_APDU)
            if (selected.size < 2 || selected.last() != 0x00.toByte()) {
                fail(onError, "SELECT was rejected")
                return
            }

            val response = iso.transceive(ApduHandler.GET_DATA_APDU)
            if (response.size < 3) {
                fail(onError, "short NFC response")
                return
            }

            val payload = DiscoveryPayload.decode(response.copyOfRange(0, response.size - 2))
            stop()
            handler.post { onPayload(payload) }
        } catch (e: Exception) {
            if (active) fail(onError, "NFC read failed: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun fail(onError: (String) -> Unit, message: String) {
        stop()
        handler.post { onError(message) }
    }

    private companion object {
        const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
