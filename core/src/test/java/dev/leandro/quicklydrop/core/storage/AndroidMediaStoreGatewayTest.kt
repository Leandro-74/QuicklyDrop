package dev.leandro.quicklydrop.core.storage

import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidMediaStoreGatewayTest {

    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val shadow = Shadows.shadowOf(resolver)
    private val gateway = AndroidMediaStoreGateway(resolver) { 1234L }

    @Test
    fun `returns injected free space`() {
        assertEquals(1234L, gateway.availableBytes())
    }

    @Test
    fun `checks existence with a query`() {
        val found = RoboCursor().apply {
            setColumnNames(listOf(MediaStore.Downloads._ID))
            setResults(arrayOf(arrayOf<Any>(1L)))
        }
        shadow.setCursor(MediaStore.Downloads.EXTERNAL_CONTENT_URI, found)

        assertTrue(gateway.exists("a.txt", "Download/QuicklyDrop/"))
    }

    @Test
    fun `inserts a pending download`() {
        val uri = gateway.insert("a.txt", "text/plain", "Download/QuicklyDrop/")
        val statement = shadow.insertStatements.last()
        val values = statement.contentValues

        assertNotNull(uri)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, statement.uri)
        assertEquals("a.txt", values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("text/plain", values.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download/QuicklyDrop/", values.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertTrue(values.getAsLong(MediaStore.MediaColumns.DATE_EXPIRES) > 0)
    }

    @Test
    fun `publishes a pending download`() {
        val uri = gateway.insert("a.txt", "text/plain", "Download/QuicklyDrop/")

        gateway.publish(uri)

        val update = shadow.updateStatements.last()
        assertEquals(0, update.contentValues.getAsInteger(MediaStore.Downloads.IS_PENDING))
    }

    @Test
    fun `opens the registered output stream`() {
        val uri = gateway.insert("a.txt", "text/plain", "Download/QuicklyDrop/")
        val output = ByteArrayOutputStream()
        shadow.registerOutputStreamSupplier(uri) { output }

        assertSame(output, gateway.openOutput(uri))
    }

    @Test
    fun `deletes a pending download`() {
        val uri = gateway.insert("a.txt", "text/plain", "Download/QuicklyDrop/")

        gateway.delete(uri)

        assertTrue(uri in shadow.deletedUris)
    }
}
