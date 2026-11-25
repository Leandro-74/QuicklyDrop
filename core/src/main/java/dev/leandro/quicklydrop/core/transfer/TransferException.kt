package dev.leandro.quicklydrop.core.transfer

/** Internal control-flow exception mapped to [TransferResult.Failure]. */
internal class TransferException(
    val failure: TransferFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
