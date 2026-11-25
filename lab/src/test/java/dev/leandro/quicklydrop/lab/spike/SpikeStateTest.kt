package dev.leandro.quicklydrop.lab.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeStateTest {

    private fun snapshot(
        pid: Int = 123,
        processAgeMs: Long = 456,
        activityVisible: Boolean = false,
        fgsRunning: Boolean = false,
        interactive: Boolean = true,
        keyguardLocked: Boolean = false,
        secureNfc: Boolean? = null,
    ) = SpikeSnapshot(
        pid = pid,
        processAgeMs = processAgeMs,
        activityVisible = activityVisible,
        fgsRunning = fgsRunning,
        interactive = interactive,
        keyguardLocked = keyguardLocked,
        secureNfc = secureNfc,
    )

    @Test
    fun `descreve processo atividade e tela`() {
        val text = SpikeState.describe(snapshot())

        assertTrue(text.contains("pid=123"))
        assertTrue(text.contains("age=456ms"))
        assertTrue(text.contains("act=off"))
        assertTrue(text.contains("fgs=off"))
        assertTrue(text.contains("screen=on"))
        assertTrue(text.contains("lock=off"))
        assertTrue(text.contains("secureNfc=?"))
    }

    @Test
    fun `descreve tela apagada travada e fgs ativo`() {
        val text = SpikeState.describe(
            snapshot(
                activityVisible = true,
                fgsRunning = true,
                interactive = false,
                keyguardLocked = true,
                secureNfc = true,
            ),
        )

        assertTrue(text.contains("act=on"))
        assertTrue(text.contains("fgs=on"))
        assertTrue(text.contains("screen=off"))
        assertTrue(text.contains("lock=on"))
        assertTrue(text.contains("secureNfc=on"))
    }

    @Test
    fun `classifica bloqueio de fgs em background`() {
        val result = SpikeFailure.foregroundService(
            "android.app.ForegroundServiceStartNotAllowedException",
        )

        assertEquals("BLOQUEADO (FGS em background)", result)
    }

    @Test
    fun `classifica falha de permissao e erro generico`() {
        assertTrue(
            SpikeFailure.foregroundService("java.lang.SecurityException")
                .startsWith("BLOQUEADO"),
        )
        assertTrue(
            SpikeFailure.foregroundService("java.lang.RuntimeException")
                .startsWith("ERRO"),
        )
    }

    @Test
    fun `traduz motivos do wifi direct`() {
        assertEquals("P2P_UNSUPPORTED", SpikeFailure.p2p(1))
        assertEquals("BUSY", SpikeFailure.p2p(2))
        assertEquals("ERROR", SpikeFailure.p2p(0))
        assertEquals("código 7", SpikeFailure.p2p(7))
    }
}
