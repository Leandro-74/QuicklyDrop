package dev.leandro.quicklydrop.core.service

import dev.leandro.quicklydrop.core.transfer.TransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStatusTest {

    @Test
    fun `exposes armed active and finished states`() {
        TransferStatus.update(TransferStatusState.Armed("Pixel"))
        assertEquals(TransferStatusState.Armed("Pixel"), TransferStatus.state.value)

        TransferStatus.update(TransferStatusState.Finished(TransferResult.Success(1, 10)))
        assertTrue(TransferStatus.state.value is TransferStatusState.Finished)

        TransferStatus.reset()
        assertEquals(TransferStatusState.Idle, TransferStatus.state.value)
    }
}
