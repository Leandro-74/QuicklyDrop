package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.Frame
import dev.leandro.quicklydrop.protocol.FrameChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransferEngineTest {

    @Test
    fun `transfers multiple files and reports progress`() {
        val (senderChannel, receiverChannel) = QueueChannel.pair()
        val content = ByteArray(70_000) { (it % 251).toByte() }
        val files = listOf(
            TestFileSource(FileMeta("a.txt", 5, "text/plain"), "hello".toByteArray()),
            TestFileSource(FileMeta("empty.bin", 0, "application/octet-stream"), ByteArray(0)),
            TestFileSource(
                FileMeta("large.bin", content.size.toLong(), "application/octet-stream"),
                content,
            ),
        )
        val sink = TestFileSink()
        val receiverResult = AtomicReference<TransferResult>()
        val receiverThread = thread {
            receiverResult.set(TransferReceiver(receiverChannel, sink).receive())
        }
        val listener = RecordingListener()

        val senderResult = TransferSender(senderChannel, listener).send(files)
        receiverThread.join(15_000)

        assertTrue("sender failed: $senderResult", senderResult is TransferResult.Success)
        senderResult as TransferResult.Success
        assertEquals(3, senderResult.files)
        assertEquals(70_005, senderResult.bytes)

        assertTrue("receiver failed: ${receiverResult.get()}", receiverResult.get() is TransferResult.Success)
        assertArrayEquals("hello".toByteArray(), sink.files["a.txt"])
        assertArrayEquals(ByteArray(0), sink.files["empty.bin"])
        assertArrayEquals(content, sink.files["large.bin"])

        assertEquals(listOf(0, 1, 2), listener.started)
        assertEquals(listOf(0, 1, 2), listener.completed)
        assertTrue("no progress was reported", listener.progresses.isNotEmpty())
        assertEquals(1f, listener.progresses.last().totalFraction, 0.0001f)
    }

    @Test
    fun `hash mismatch aborts the file and fails both sides`() {
        val (senderChannel, receiverChannel) = QueueChannel.pair()
        val sink = TestFileSink()
        val receiverResult = AtomicReference<TransferResult>()
        val receiverThread = thread {
            receiverResult.set(TransferReceiver(receiverChannel, sink).receive())
        }

        val senderResult = TransferSender(CorruptEndChannel(senderChannel)).send(
            listOf(
                TestFileSource(
                    FileMeta("a.bin", 10, "application/octet-stream"),
                    ByteArray(10) { 7 },
                ),
            ),
        )
        receiverThread.join(15_000)

        assertFailure(senderResult, TransferFailure.HASH_MISMATCH)
        assertFailure(receiverResult.get(), TransferFailure.HASH_MISMATCH)
        assertTrue("partial file was committed", sink.files.isEmpty())
    }

    @Test
    fun `cancellation stops the sender and aborts the receiver`() {
        val (senderChannel, receiverChannel) = QueueChannel.pair()
        val sink = TestFileSink()
        val receiverResult = AtomicReference<TransferResult>()
        val receiverThread = thread {
            receiverResult.set(TransferReceiver(receiverChannel, sink).receive())
        }

        val cancel = AtomicBoolean(false)
        val listener = object : TransferListener {
            override fun onProgress(progress: TransferProgress) {
                cancel.set(true)
            }
        }
        val payload = ByteArray(2 * 32 * 1024 + 1)
        val senderResult = TransferSender(senderChannel, listener).send(
            files = listOf(
                TestFileSource(
                    FileMeta("big.bin", payload.size.toLong(), "application/octet-stream"),
                    payload,
                ),
            ),
            cancelled = { cancel.get() },
        )
        receiverThread.join(15_000)

        assertFailure(senderResult, TransferFailure.CANCELLED)
        assertFailure(receiverResult.get(), TransferFailure.CANCELLED)
        assertTrue("partial file was committed", sink.files.isEmpty())
    }

    @Test
    fun `sink failure is reported to the sender`() {
        val (senderChannel, receiverChannel) = QueueChannel.pair()
        val sink = object : FileSink {
            override fun open(meta: FileMeta): FileSink.Sink =
                throw FileSinkException(TransferFailure.NO_SPACE, "disk full")
        }
        val receiverResult = AtomicReference<TransferResult>()
        val receiverThread = thread {
            receiverResult.set(TransferReceiver(receiverChannel, sink).receive())
        }

        val senderResult = TransferSender(senderChannel).send(
            listOf(TestFileSource(FileMeta("a.bin", 1, "application/octet-stream"), byteArrayOf(1))),
        )
        receiverThread.join(15_000)

        assertFailure(senderResult, TransferFailure.NO_SPACE)
        assertFailure(receiverResult.get(), TransferFailure.NO_SPACE)
    }

    @Test
    fun `sender maps a read timeout`() {
        val channel = object : FrameChannel {
            override fun send(frame: Frame) = Unit
            override fun receive(): Frame? = throw SocketTimeoutException("timeout")
        }

        val result = TransferSender(channel).send(
            listOf(TestFileSource(FileMeta("a.bin", 1, "application/octet-stream"), byteArrayOf(1))),
        )

        assertFailure(result, TransferFailure.TIMEOUT)
    }

    @Test
    fun `receiver rejects an unexpected first frame`() {
        val channel = object : FrameChannel {
            override fun send(frame: Frame) = Unit
            override fun receive(): Frame? = Frame.Accept(0)
        }

        val result = TransferReceiver(channel, TestFileSink()).receive()

        assertFailure(result, TransferFailure.PROTOCOL)
    }

    private fun assertFailure(result: TransferResult, expected: TransferFailure) {
        assertTrue("expected $expected, got $result", result is TransferResult.Failure)
        assertEquals(expected, (result as TransferResult.Failure).failure)
    }
}
