package dev.leandro.quicklydrop.core.storage

import dev.leandro.quicklydrop.core.transfer.FileSinkException
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.protocol.FileMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreFileSinkTest {

    private val meta = FileMeta("a.txt", 5, "text/plain")

    @Test
    fun `writes and publishes a file`() {
        val gateway = FakeMediaStoreGateway()
        val sink = MediaStoreFileSink(gateway)
        val target = sink.open(meta)

        target.output.write("hello".toByteArray())
        target.commit()
        target.close()

        val uri = gateway.outputs.keys.single()
        assertEquals("a.txt", gateway.names[uri])
        assertTrue(uri in gateway.published)
        assertTrue(gateway.deleted.isEmpty())
        assertArrayEquals("hello".toByteArray(), gateway.outputs[uri]!!.toByteArray())
    }

    @Test
    fun `de-duplicates the name before insert`() {
        val gateway = FakeMediaStoreGateway().apply { existing += "a.txt" }
        val sink = MediaStoreFileSink(gateway)

        sink.open(meta).close()

        assertEquals("a (1).txt", gateway.names.values.single())
    }

    @Test
    fun `rejects a file larger than the limit`() {
        val gateway = FakeMediaStoreGateway()
        val sink = MediaStoreFileSink(gateway, maxFileSize = 4)

        val error = assertThrows(FileSinkException::class.java) { sink.open(meta) }

        assertEquals(TransferFailure.FILE_TOO_LARGE, error.failure)
    }

    @Test
    fun `rejects when free space is insufficient`() {
        val gateway = FakeMediaStoreGateway(available = 4)
        val sink = MediaStoreFileSink(gateway)

        val error = assertThrows(FileSinkException::class.java) { sink.open(meta) }

        assertEquals(TransferFailure.NO_SPACE, error.failure)
    }

    @Test
    fun `abort deletes the pending file`() {
        val gateway = FakeMediaStoreGateway()
        val sink = MediaStoreFileSink(gateway)
        val target = sink.open(meta)
        val uri = gateway.outputs.keys.single()

        target.output.write("hel".toByteArray())
        target.abort()

        assertTrue(uri in gateway.deleted)
        assertTrue(gateway.published.isEmpty())
    }

    @Test
    fun `close without commit deletes the pending file`() {
        val gateway = FakeMediaStoreGateway()
        val sink = MediaStoreFileSink(gateway)
        val target = sink.open(meta)
        val uri = gateway.outputs.keys.single()

        target.close()

        assertTrue(uri in gateway.deleted)
    }

    @Test
    fun `insert failure is reported as a write error`() {
        val gateway = FakeMediaStoreGateway().apply { failInsert = true }
        val sink = MediaStoreFileSink(gateway)

        val error = assertThrows(FileSinkException::class.java) { sink.open(meta) }

        assertEquals(TransferFailure.WRITE_ERROR, error.failure)
    }
}
