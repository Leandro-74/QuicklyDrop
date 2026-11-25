package dev.leandro.quicklydrop.lab.spike

/**
 * Payload published by the spike HCE card.
 *
 * The payload is UTF-8 text followed by the `9000` status word. The short APDU limit is
 * 255 bytes; above that the card responds `6A82` and the event is recorded as invalid.
 */
object SpikePayload {

    const val MAX_APDU = 255
    const val STATUS_OK_HI = 0x90
    const val STATUS_OK_LO = 0x00
    const val STATUS_NOT_FOUND_HI = 0x6A
    const val STATUS_NOT_FOUND_LO = 0x82

    val notFound: ByteArray = byteArrayOf(STATUS_NOT_FOUND_HI.toByte(), STATUS_NOT_FOUND_LO.toByte())

    /** Returns the rejection reason, or `null` when the payload fits in the APDU. */
    fun validate(text: String?): String? {
        if (text.isNullOrBlank()) return "empty payload"
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size + 2 > MAX_APDU) return "payload of $size bytes does not fit in a short APDU"
        return null
    }

    /** Payload + `9000`, or `6A82` when there is no valid payload. */
    fun response(text: String?): ByteArray {
        if (validate(text) != null) return notFound
        val data = text!!.toByteArray(Charsets.UTF_8)
        return data + byteArrayOf(STATUS_OK_HI.toByte(), STATUS_OK_LO.toByte())
    }

    fun isValid(text: String?): Boolean = validate(text) == null
}
