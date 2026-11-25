package dev.leandro.quicklydrop.protocol

/** Metadata of an offered file. */
data class FileMeta(
    val name: String,
    val size: Long,
    val mime: String = "",
) {
    init {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.isNotEmpty()) { "empty name" }
        require(nameBytes.size <= 255) { "name longer than 255 bytes" }
        require(size >= 0) { "negative size" }
        require(mime.toByteArray(Charsets.UTF_8).size <= 127) { "MIME longer than 127 bytes" }
    }
}

/** Messages exchanged after the Noise handshake. */
sealed interface Frame {

    data class Offer(val files: List<FileMeta>) : Frame

    data class Accept(val fileIndex: Int, val startOffset: Long = 0) : Frame

    data class Reject(val reason: RejectReason, val message: String = "") : Frame

    data class Data(
        val fileIndex: Int,
        val offset: Long,
        val bytes: ByteArray,
    ) : Frame {
        override fun equals(other: Any?): Boolean =
            other is Data &&
                fileIndex == other.fileIndex &&
                offset == other.offset &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int {
            var result = fileIndex
            result = 31 * result + offset.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    data class End(val fileIndex: Int, val sha256: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            other is End && fileIndex == other.fileIndex && sha256.contentEquals(other.sha256)

        override fun hashCode(): Int = 31 * fileIndex + sha256.contentHashCode()
    }

    data class Ack(val fileIndex: Int, val status: AckStatus) : Frame

    data class Cancel(val reason: CancelReason) : Frame

    data class Error(val code: ErrorCode, val message: String = "") : Frame
}

enum class RejectReason(val code: Int) {
    USER(1),
    UNSUPPORTED(2),
    BUSY(3),
    NO_SPACE(4),
    INVALID(5);

    companion object {
        fun fromCode(code: Int): RejectReason =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown rejection reason: $code")
    }
}

enum class AckStatus(val code: Int) {
    OK(0),
    HASH_MISMATCH(1),
    WRITE_ERROR(2),
    CANCELLED(3);

    companion object {
        fun fromCode(code: Int): AckStatus =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown ack status: $code")
    }
}

enum class CancelReason(val code: Int) {
    USER(1),
    TIMEOUT(2),
    ERROR(3);

    companion object {
        fun fromCode(code: Int): CancelReason =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown cancellation reason: $code")
    }
}

enum class ErrorCode(val code: Int) {
    INVALID_VERSION(0x0001),
    INVALID_FRAME(0x0002),
    UNEXPECTED_FRAME(0x0003),
    FILE_TOO_LARGE(0x0004),
    NO_SPACE(0x0005),
    HASH_MISMATCH(0x0006),
    WRITE_ERROR(0x0007),
    CANCELLED(0x0008),
    TIMEOUT(0x0009),
    INTERNAL(0x000A);

    companion object {
        fun fromCode(code: Int): ErrorCode =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown error code: $code")
    }
}
