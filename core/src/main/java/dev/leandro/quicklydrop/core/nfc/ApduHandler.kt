package dev.leandro.quicklydrop.core.nfc

/**
 * Pure APDU handling for the HCE card.
 *
 * The Android framework routes only the registered AID, but the handler still validates
 * SELECT so a wrong AID cannot read the payload.
 */
object ApduHandler {

    val AID: ByteArray = byteArrayOf(
        0xF0.toByte(), 0x51, 0x44, 0x52, 0x4F, 0x50,
    )

    /** SELECT by AID with the trailing Le byte. */
    val SELECT_APDU: ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte(),
    ) + AID + byteArrayOf(0x00)

    val GET_DATA_APDU: ByteArray = byteArrayOf(
        0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00,
    )

    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    private val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)
    private const val AID_OFFSET = 5

    fun handle(command: ByteArray?, payload: ByteArray?): ByteArray {
        if (command == null || command.size < 2) return UNKNOWN

        return when {
            isSelect(command) -> OK
            isGetData(command) -> {
                if (payload == null || payload.isEmpty()) NOT_FOUND else payload + OK
            }

            else -> UNKNOWN
        }
    }

    fun isSelect(command: ByteArray?): Boolean {
        if (command == null || command.size < AID_OFFSET + AID.size) return false
        if (command[0] != 0x00.toByte() || command[1] != 0xA4.toByte()) return false
        return command.copyOfRange(AID_OFFSET, AID_OFFSET + AID.size).contentEquals(AID)
    }

    fun isGetData(command: ByteArray?): Boolean =
        command != null &&
            command.size >= 2 &&
            command[0] == 0x80.toByte() &&
            command[1] == 0xCA.toByte()
}
