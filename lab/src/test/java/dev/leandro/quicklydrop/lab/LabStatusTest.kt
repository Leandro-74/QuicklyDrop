package dev.leandro.quicklydrop.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class LabStatusTest {

    @Test
    fun `mode and role switch works while stopped`() {
        val status = LabStatus(phase = Phase.IDLE, mode = Mode.LAN, role = Role.SENDER)

        assertEquals(Mode.P2P, status.choose(mode = Mode.P2P).mode)
        assertEquals(Role.RECEIVER, status.choose(role = Role.RECEIVER).role)
    }

    @Test
    fun `switch is ignored during search`() {
        val status = LabStatus(phase = Phase.SEARCHING, mode = Mode.LAN, role = Role.SENDER)

        assertEquals(status, status.choose(mode = Mode.P2P, role = Role.RECEIVER))
    }

    @Test
    fun `switch after error returns to idle`() {
        val status = LabStatus(phase = Phase.ERROR, detail = "failed", mode = Mode.LAN, role = Role.SENDER)

        val chosen = status.choose(mode = Mode.P2P)

        assertEquals(Phase.IDLE, chosen.phase)
        assertEquals(Mode.P2P, chosen.mode)
        assertEquals(Role.SENDER, chosen.role)
    }
}
