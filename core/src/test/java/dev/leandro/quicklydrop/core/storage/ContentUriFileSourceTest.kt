package dev.leandro.quicklydrop.core.storage

import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentUriFileSourceTest {

    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://test/file/42")

    @Test
    fun `reads the name size and bytes`() {
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any>("../../report.pdf", 5L)))
        }
        val shadow = Shadows.shadowOf(resolver)
        shadow.setCursor(uri, cursor)
        shadow.registerInputStreamSupplier(uri) {
            ByteArrayInputStream("hello".toByteArray())
        }

        val source = ContentUriFileSource(resolver, uri)

        assertEquals(".._.._report.pdf", source.meta.name)
        assertEquals(5L, source.meta.size)
        assertEquals("application/octet-stream", source.meta.mime)
        assertArrayEquals("hello".toByteArray(), source.open().readBytes())
    }

    @Test
    fun `rejects a file with unknown size`() {
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>("report.pdf", null)))
        }
        Shadows.shadowOf(resolver).setCursor(uri, cursor)

        assertThrows(IllegalArgumentException::class.java) {
            ContentUriFileSource(resolver, uri)
        }
    }
}
