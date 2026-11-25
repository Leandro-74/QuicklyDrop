package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.Frame
import dev.leandro.quicklydrop.protocol.FrameChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class QueueChannel(
    private val outbound: BlockingQueue<Frame>,
    private val inbound: BlockingQueue<Frame>,
) : FrameChannel {

    override fun send(frame: Frame) {
        if (!outbound.offer(frame, TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("peer queue is full")
        }
    }

    override fun receive(): Frame? =
        inbound.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    companion object {
        fun pair(): Pair<QueueChannel, QueueChannel> {
            val firstToSecond = LinkedBlockingQueue<Frame>()
            val secondToFirst = LinkedBlockingQueue<Frame>()
            return QueueChannel(firstToSecond, secondToFirst) to
                QueueChannel(secondToFirst, firstToSecond)
        }

        private const val TIMEOUT_SECONDS = 10L
    }
}

internal class TestFileSource(
    override val meta: FileMeta,
    private val bytes: ByteArray,
) : FileSource {
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

internal class TestFileSink : FileSink {

    private val buffers = mutableMapOf<String, ByteArrayOutputStream>()
    val files = mutableMapOf<String, ByteArray>()

    override fun open(meta: FileMeta): FileSink.Sink {
        val buffer = ByteArrayOutputStream()
        buffers[meta.name] = buffer
        return object : FileSink.Sink {
            override val output: OutputStream get() = buffer

            override fun commit() {
                files[meta.name] = buffer.toByteArray()
            }

            override fun abort() {
                buffers.remove(meta.name)
            }

            override fun close() = Unit
        }
    }
}

internal class CorruptEndChannel(private val delegate: FrameChannel) : FrameChannel {

    override fun send(frame: Frame) {
        if (frame is Frame.End) {
            val corrupted = frame.sha256.copyOf()
            corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
            delegate.send(Frame.End(frame.fileIndex, corrupted))
        } else {
            delegate.send(frame)
        }
    }

    override fun receive(): Frame? = delegate.receive()
}

internal class RecordingListener : TransferListener {
    val progresses = mutableListOf<TransferProgress>()
    val started = mutableListOf<Int>()
    val completed = mutableListOf<Int>()

    override fun onFileStarted(index: Int, file: FileMeta) {
        started += index
    }

    override fun onProgress(progress: TransferProgress) {
        progresses += progress
    }

    override fun onFileCompleted(index: Int, file: FileMeta) {
        completed += index
    }
}
