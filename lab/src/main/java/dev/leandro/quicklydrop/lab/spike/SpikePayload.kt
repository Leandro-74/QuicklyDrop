package dev.leandro.quicklydrop.lab.spike

/**
 * Payload publicado pelo cartão HCE do spike.
 *
 * O payload é texto UTF-8 seguido do status word `9000`. O limite de APDU curta é 255
 * bytes; acima disso o cartão responde `6A82` e o evento é registrado como inválido.
 */
object SpikePayload {

    const val MAX_APDU = 255
    const val STATUS_OK_HI = 0x90
    const val STATUS_OK_LO = 0x00
    const val STATUS_NOT_FOUND_HI = 0x6A
    const val STATUS_NOT_FOUND_LO = 0x82

    val notFound: ByteArray = byteArrayOf(STATUS_NOT_FOUND_HI.toByte(), STATUS_NOT_FOUND_LO.toByte())

    /** Devolve o motivo de rejeição, ou `null` quando o payload cabe na APDU. */
    fun validate(text: String?): String? {
        if (text.isNullOrBlank()) return "payload vazio"
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size + 2 > MAX_APDU) return "payload de $size bytes não cabe em APDU curta"
        return null
    }

    /** Payload + `9000`, ou `6A82` quando não há payload válido. */
    fun response(text: String?): ByteArray {
        if (validate(text) != null) return notFound
        val data = text!!.toByteArray(Charsets.UTF_8)
        return data + byteArrayOf(STATUS_OK_HI.toByte(), STATUS_OK_LO.toByte())
    }

    fun isValid(text: String?): Boolean = validate(text) == null
}
