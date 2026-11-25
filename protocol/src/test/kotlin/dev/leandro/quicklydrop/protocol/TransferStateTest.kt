package dev.leandro.quicklydrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {

    @Test
    fun `valid sequence reaches completed`() {
        val machine = TransferStateMachine()

        machine.offerSent()
        machine.accepted()
        machine.sending()
        machine.awaitingAck()
        machine.completed()

        assertEquals(TransferState.COMPLETED, machine.state)
        assertTrue(machine.isTerminal)
    }

    @Test
    fun `rejects a transition out of order`() {
        val machine = TransferStateMachine()

        assertThrows(IllegalStateException::class.java) { machine.accepted() }
        assertEquals(TransferState.IDLE, machine.state)
    }

    @Test
    fun `rejects a completion without an ack`() {
        val machine = TransferStateMachine()
        machine.offerSent()
        machine.accepted()
        machine.sending()

        assertThrows(IllegalStateException::class.java) { machine.completed() }
        assertEquals(TransferState.SENDING, machine.state)
    }

    @Test
    fun `cancel is terminal`() {
        val machine = TransferStateMachine()
        machine.offerSent()
        machine.accepted()
        machine.sending()
        machine.cancelled()

        assertEquals(TransferState.CANCELLED, machine.state)
        assertTrue(machine.isTerminal)
        assertFalse(runCatching { machine.cancelled() }.isFailure)
    }

    @Test
    fun `failure is terminal and does not overwrite`() {
        val machine = TransferStateMachine()
        machine.offerSent()
        machine.accepted()
        machine.failed()
        machine.cancelled()

        assertEquals(TransferState.FAILED, machine.state)
        assertTrue(machine.isTerminal)
    }

    @Test
    fun `rejection ends a pending offer`() {
        val machine = TransferStateMachine()
        machine.offerSent()
        machine.rejected()

        assertEquals(TransferState.FAILED, machine.state)
    }
}
