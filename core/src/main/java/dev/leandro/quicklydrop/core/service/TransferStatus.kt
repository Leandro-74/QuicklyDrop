package dev.leandro.quicklydrop.core.service

import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable state used by the app UI. */
object TransferStatus {

    private val _state = MutableStateFlow<TransferStatusState>(TransferStatusState.Idle)
    val state: StateFlow<TransferStatusState> = _state.asStateFlow()

    fun update(state: TransferStatusState) {
        _state.value = state
    }

    fun reset() {
        _state.value = TransferStatusState.Idle
    }
}

sealed interface TransferStatusState {

    data object Idle : TransferStatusState

    data class Armed(val displayName: String) : TransferStatusState

    data class Active(val progress: TransferProgress) : TransferStatusState

    data class Finished(val result: TransferResult) : TransferStatusState
}
