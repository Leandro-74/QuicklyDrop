package dev.leandro.quicklydrop.lab.spike

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
    fun `without arming there is no payload`() {
        val config = SpikeStore.config(context)

        assertNull(config.payload)
        assertTrue(config.fgsOnTap)
        assertTrue(config.groupOnTap)
    }

    @Test
    fun `arming persists payload and options`() {
        SpikeStore.arm(context, "test\n123", fgsOnTap = false, groupOnTap = true)

        val config = SpikeStore.config(context)
        assertEquals("test\n123", config.payload)
        assertFalse(config.fgsOnTap)
        assertTrue(config.groupOnTap)
    }

    @Test
    fun `disarming removes the payload and keeps options`() {
        SpikeStore.arm(context, "test", fgsOnTap = false, groupOnTap = false)

        SpikeStore.disarm(context)

        val config = SpikeStore.config(context)
        assertNull(config.payload)
        assertFalse(config.fgsOnTap)
        assertFalse(config.groupOnTap)
    }

    @Test
    fun `arming again replaces the payload`() {
        SpikeStore.arm(context, "first", fgsOnTap = true, groupOnTap = true)
        SpikeStore.arm(context, "second", fgsOnTap = true, groupOnTap = false)

        assertEquals("second", SpikeStore.config(context).payload)
    }
}
