package dev.leandro.quicklydrop.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class DisplayNameTest {

    @Test
    fun `blocks path separators and control characters`() {
        assertEquals(".._.._etc_passwd", DisplayName.sanitize("../../etc/passwd"))
        assertEquals("foo_bar_baz", DisplayName.sanitize("foo/bar\\baz"))
        assertEquals("a_b_c", DisplayName.sanitize("a\u0000b\u001Fc"))
    }

    @Test
    fun `uses a fallback for empty and dot names`() {
        assertEquals("file", DisplayName.sanitize(""))
        assertEquals("file", DisplayName.sanitize("   "))
        assertEquals("file", DisplayName.sanitize("."))
        assertEquals("file", DisplayName.sanitize(".."))
    }

    @Test
    fun `strips bidirectional controls`() {
        assertEquals("a_b.pdf", DisplayName.sanitize("a\u202Eb.pdf"))
    }

    @Test
    fun `keeps common names intact`() {
        assertEquals("photo.jpg", DisplayName.sanitize("photo.jpg"))
        assertEquals(".bashrc", DisplayName.sanitize(".bashrc"))
        assertEquals("my report.pdf", DisplayName.sanitize("my report.pdf"))
    }

    @Test
    fun `limits long names and preserves the extension`() {
        val result = DisplayName.sanitize("a".repeat(300) + ".jpg")

        assertTrue(result.endsWith(".jpg"))
        assertTrue(result.toByteArray(StandardCharsets.UTF_8).size <= DisplayName.MAX_BYTES)
    }

    @Test
    fun `does not split multi-byte characters`() {
        val result = DisplayName.sanitize("é".repeat(300) + ".txt")

        assertTrue(result.endsWith(".txt"))
        assertTrue(result.toByteArray(StandardCharsets.UTF_8).size <= DisplayName.MAX_BYTES)
        assertTrue(result.dropLast(4).all { it == 'é' })
    }

    @Test
    fun `resolves collisions with a numeric suffix`() {
        val existing = setOf("a.txt", "a (1).txt")

        assertEquals("a (2).txt", DisplayName.unique("a.txt") { it in existing })
    }

    @Test
    fun `returns the original name when it is free`() {
        assertEquals("a.txt", DisplayName.unique("a.txt") { false })
    }
}
