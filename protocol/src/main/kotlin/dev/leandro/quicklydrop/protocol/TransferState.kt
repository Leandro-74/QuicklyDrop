package dev.leandro.quicklydrop.protocol

/** Per-file transfer states. A session creates one machine for each file. */
enum class TransferState {
    IDLE,
    OFFER_SENT,
    ACCEPTED,
    SENDING,
    AWAITING_ACK,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Validates the transition sequence of a single file.
 *
 * The state is intentionally small: the session owns the loop over files and decides
 * whether a failure aborts the whole session.
 */
class TransferStateMachine {

    var state: TransferState = TransferState.IDLE
        private set

    val isTerminal: Boolean
        get() = state == TransferState.COMPLETED ||
            state == TransferState.FAILED ||
            state == TransferState.CANCELLED

    fun offerSent() = transition(TransferState.IDLE, TransferState.OFFER_SENT)

    fun accepted() = transition(TransferState.OFFER_SENT, TransferState.ACCEPTED)

    fun sending() = transition(TransferState.ACCEPTED, TransferState.SENDING)

    fun awaitingAck() = transition(TransferState.SENDING, TransferState.AWAITING_ACK)

    fun completed() = transition(TransferState.AWAITING_ACK, TransferState.COMPLETED)

    fun rejected() = transition(TransferState.OFFER_SENT, TransferState.FAILED)

    fun failed() {
        if (!isTerminal) state = TransferState.FAILED
    }

    fun cancelled() {
        if (!isTerminal) state = TransferState.CANCELLED
    }

    private fun transition(expected: TransferState, next: TransferState) {
        check(state == expected) { "invalid transition: $state -> $next" }
        state = next
    }
}
