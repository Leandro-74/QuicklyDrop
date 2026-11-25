package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta

/** Result of a sender or receiver session. */
sealed interface TransferResult {

    data class Success(val files: Int, val bytes: Long) : TransferResult

    data class Failure(
        val failure: TransferFailure,
        val message: String,
        val cause: Throwable? = null,
    ) : TransferResult
}

/** Stable failure categories used by the UI and the service. */
enum class TransferFailure {
    CANCELLED,
    TIMEOUT,
    REJECTED,
    NO_SPACE,
    FILE_TOO_LARGE,
    HASH_MISMATCH,
    WRITE_ERROR,
    PROTOCOL,
    IO,
    INTERNAL,
}

/** Snapshot of a session in progress, safe to copy to the UI. */
data class TransferProgress(
    val fileIndex: Int,
    val filesTotal: Int,
    val file: FileMeta,
    val fileBytes: Long,
    val totalBytes: Long,
    val transferredBytes: Long,
) {
    val fileFraction: Float
        get() = fraction(fileBytes, file.size)

    val totalFraction: Float
        get() = fraction(transferredBytes, totalBytes)

    private fun fraction(value: Long, total: Long): Float {
        if (total <= 0) return 1f
        return (value.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    }
}
