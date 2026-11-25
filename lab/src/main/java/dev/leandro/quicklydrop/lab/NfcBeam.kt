package dev.leandro.quicklydrop.lab

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper

/**
 * The tap itself: one side presents a card (HCE) with a text, the other reads it in
 * reader mode.
 *
 * It knows nothing about hotspot or Wi-Fi Direct — it only delivers the string. That way
 * the same tap works for any transport.
 */
class NfcBeam(private val activity: Activity) {

    companion object {
        /** Lab AID: F0 + "QDROL". Kept distinct from the product AID. */
        private val SELECT = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x06,
            0xF0.toByte(), 0x51, 0x44, 0x52, 0x4F, 0x4C, 0x00,
        )
        private val GET_DATA = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    private val nfc = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var finished = false

    private var readerCallback: NfcAdapter.ReaderCallback? = null

    /** Returns the reason NFC cannot be used, or null if everything is fine. */
    fun problem(): String? = when {
        nfc == null -> "this device has no NFC"
        !nfc.isEnabled -> "NFC turned off"
        else -> null
    }

    /** Gets ready to be read. [onRead] reports that someone tapped. */
    fun present(payload: String, onRead: () -> Unit) {
        QuicklyDropApduService.payload = payload.toByteArray()
        QuicklyDropApduService.onRead = { handler.post { if (!finished) onRead() } }
        LabLog.add("NFC: card ready")
    }

    /** Enables reader mode and delivers the text read. */
    fun read(onPayload: (String) -> Unit, onError: (String) -> Unit) {
        val callback = NfcAdapter.ReaderCallback { tag -> onTag(tag, onPayload, onError) }
        readerCallback = callback

        handler.post {
            runCatching {
                nfc.enableReaderMode(activity, callback, READER_FLAGS, null)
            }.onFailure { onError("reader mode failed: ${it.message}") }
            LabLog.add("NFC: reading, touch the other device")
        }
    }

    private fun onTag(tag: Tag, onPayload: (String) -> Unit, onError: (String) -> Unit) {
        if (finished) return

        val iso = IsoDep.get(tag)
        if (iso == null) {
            LabLog.add("NFC: tag without IsoDep, ignoring")
            return
        }

        try {
            iso.connect()
            iso.timeout = 5_000

            val selected = iso.transceive(SELECT)
            if (selected.size < 2 || selected.last() != 0x00.toByte()) {
                onError("NFC: SELECT rejected")
                return
            }

            val response = iso.transceive(GET_DATA)
            if (response.size < 3) {
                onError("NFC: short response")
                return
            }

            val payload = String(response.copyOfRange(0, response.size - 2))
            LabLog.add("NFC: received (${payload.lineSequence().firstOrNull()})")
            stopReader()
            onPayload(payload)
        } catch (e: Exception) {
            if (!finished) onError("NFC: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun stopReader() {
        val callback = readerCallback ?: return
        readerCallback = null
        runCatching { nfc?.disableReaderMode(activity) }
        LabLog.add("NFC: read finished")
    }

    fun stop() {
        finished = true
        stopReader()
        QuicklyDropApduService.payload = null
        QuicklyDropApduService.onRead = null
    }
}
