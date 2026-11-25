package dev.leandro.quicklydrop.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFormatTest {

    @Test
    fun `formats bytes kilobytes megabytes and gigabytes`() {
        assertEquals("512 B", TransferFormat.bytes(512))
        assertEquals("1.0 KB", TransferFormat.bytes(1024))
        assertEquals("1.5 KB", TransferFormat.bytes(1536))
        assertEquals("1.0 MB", TransferFormat.bytes(1024L * 1024))
        assertEquals("2.5 MB", TransferFormat.bytes(5L * 1024 * 1024 / 2))
        assertEquals("1.0 GB", TransferFormat.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `clamps negative values`() {
        assertEquals("0 B", TransferFormat.bytes(-10))
    }
}
