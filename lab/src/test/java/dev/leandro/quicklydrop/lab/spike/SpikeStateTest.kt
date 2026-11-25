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
    fun `describes process activity and screen`() {
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
    fun `describes screen off locked and fgs active`() {
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
    fun `classifies fgs blocked in background`() {
        val result = SpikeFailure.foregroundService(
            "android.app.ForegroundServiceStartNotAllowedException",
        )

        assertEquals("BLOCKED (FGS in background)", result)
    }

    @Test
    fun `classifies permission failure and generic error`() {
        assertTrue(
            SpikeFailure.foregroundService("java.lang.SecurityException")
                .startsWith("BLOCKED"),
        )
        assertTrue(
            SpikeFailure.foregroundService("java.lang.RuntimeException")
                .startsWith("ERROR"),
        )
    }

    @Test
    fun `translates wifi direct reasons`() {
        assertEquals("P2P_UNSUPPORTED", SpikeFailure.p2p(1))
        assertEquals("BUSY", SpikeFailure.p2p(2))
        assertEquals("ERROR", SpikeFailure.p2p(0))
        assertEquals("code 7", SpikeFailure.p2p(7))
    }
}
