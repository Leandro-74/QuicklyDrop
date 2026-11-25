package dev.leandro.quicklydrop.app

import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.FileMeta

/** State of the sender flow. */
sealed interface SendState {

    data object Idle : SendState

    data object Loading : SendState

    data class Ready(
        val files: List<FileMeta>,
        val scanning: Boolean = false,
    ) : SendState

    data class Sending(
        val files: List<FileMeta>,
        val progress: TransferProgress? = null,
    ) : SendState

    data class Finished(val result: TransferResult) : SendState

    data class Error(val message: String) : SendState
}
