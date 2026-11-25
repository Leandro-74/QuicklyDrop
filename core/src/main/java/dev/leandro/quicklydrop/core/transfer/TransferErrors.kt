package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.ErrorCode

internal fun TransferFailure.toErrorCode(): ErrorCode = when (this) {
    TransferFailure.CANCELLED -> ErrorCode.CANCELLED
    TransferFailure.TIMEOUT -> ErrorCode.TIMEOUT
    TransferFailure.NO_SPACE -> ErrorCode.NO_SPACE
    TransferFailure.FILE_TOO_LARGE -> ErrorCode.FILE_TOO_LARGE
    TransferFailure.HASH_MISMATCH -> ErrorCode.HASH_MISMATCH
    TransferFailure.WRITE_ERROR -> ErrorCode.WRITE_ERROR
    TransferFailure.REJECTED -> ErrorCode.INVALID_FRAME
    TransferFailure.PROTOCOL -> ErrorCode.INVALID_FRAME
    TransferFailure.IO -> ErrorCode.INTERNAL
    TransferFailure.INTERNAL -> ErrorCode.INTERNAL
}

internal fun ErrorCode.toFailure(): TransferFailure = when (this) {
    ErrorCode.INVALID_VERSION -> TransferFailure.PROTOCOL
    ErrorCode.INVALID_FRAME -> TransferFailure.PROTOCOL
    ErrorCode.UNEXPECTED_FRAME -> TransferFailure.PROTOCOL
    ErrorCode.FILE_TOO_LARGE -> TransferFailure.FILE_TOO_LARGE
    ErrorCode.NO_SPACE -> TransferFailure.NO_SPACE
    ErrorCode.HASH_MISMATCH -> TransferFailure.HASH_MISMATCH
    ErrorCode.WRITE_ERROR -> TransferFailure.WRITE_ERROR
    ErrorCode.CANCELLED -> TransferFailure.CANCELLED
    ErrorCode.TIMEOUT -> TransferFailure.TIMEOUT
    ErrorCode.INTERNAL -> TransferFailure.INTERNAL
}
