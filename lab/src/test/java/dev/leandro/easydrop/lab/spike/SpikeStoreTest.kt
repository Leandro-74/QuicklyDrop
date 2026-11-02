package dev.leandro.easydrop.lab.spike

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpikeStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `sem armar nao ha payload`() {
        val config = SpikeStore.config(context)

        assertNull(config.payload)
        assertTrue(config.fgsOnTap)
        assertTrue(config.groupOnTap)
    }

    @Test
    fun `armar persiste payload e opcoes`() {
        SpikeStore.arm(context, "teste\n123", fgsOnTap = false, groupOnTap = true)

        val config = SpikeStore.config(context)
        assertEquals("teste\n123", config.payload)
        assertFalse(config.fgsOnTap)
        assertTrue(config.groupOnTap)
    }

    @Test
    fun `desarmar remove o payload e mantem opcoes`() {
        SpikeStore.arm(context, "teste", fgsOnTap = false, groupOnTap = false)

        SpikeStore.disarm(context)

        val config = SpikeStore.config(context)
        assertNull(config.payload)
        assertFalse(config.fgsOnTap)
        assertFalse(config.groupOnTap)
    }

    @Test
    fun `armar de novo substitui o payload`() {
        SpikeStore.arm(context, "primeiro", fgsOnTap = true, groupOnTap = true)
        SpikeStore.arm(context, "segundo", fgsOnTap = true, groupOnTap = false)

        assertEquals("segundo", SpikeStore.config(context).payload)
    }
}
