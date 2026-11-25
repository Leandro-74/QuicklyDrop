package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.core.net.TcpSockets
import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.NoiseHandshake
import dev.leandro.quicklydrop.protocol.NoiseStream
import dev.leandro.quicklydrop.protocol.SecureChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransferLoopbackTest {

    @Test
    fun `transfers a file over tcp with noise`() {
        val server = TcpSockets.openServer(port = 0, acceptTimeoutMs = 10_000)
        val responder = NoiseHandshake.responder()
        val sink = TestFileSink()
        val receiverResult = AtomicReference<TransferResult>()
        val receiverError = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        val receiverThread = thread(name = "receiver") {
            try {
                TcpSockets.accept(server).use { socket ->
                    NoiseStream.read(responder, socket.getInputStream())
                    NoiseStream.write(responder, socket.getOutputStream())
                    val channel = SecureChannel(
                        input = socket.getInputStream(),
                        output = socket.getOutputStream(),
                        transport = responder.split(),
                    )
                    receiverResult.set(TransferReceiver(channel, sink).receive())
                }
            } catch (t: Throwable) {
                receiverError.set(t)
            } finally {
                done.countDown()
            }
        }

        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val senderResult = TcpSockets.connect("127.0.0.1", server.localPort).use { socket ->
            val initiator = NoiseHandshake.initiator(responder.localPublicKey())
            NoiseStream.write(initiator, socket.getOutputStream())
            NoiseStream.read(initiator, socket.getInputStream())
            val channel = SecureChannel(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                transport = initiator.split(),
            )
            TransferSender(channel).send(
                listOf(
                    TestFileSource(
                        FileMeta("payload.bin", payload.size.toLong(), "application/octet-stream"),
                        payload,
                    ),
                ),
            )
        }

        assertTrue("receiver did not finish", done.await(15, TimeUnit.SECONDS))
        assertNull("receiver failed: ${receiverError.get()}", receiverError.get())
        assertTrue("sender failed: $senderResult", senderResult is TransferResult.Success)
        assertTrue(
            "receiver failed: ${receiverResult.get()}",
            receiverResult.get() is TransferResult.Success,
        )
        assertArrayEquals(payload, sink.files["payload.bin"])

        server.close()
        receiverThread.join(5_000)
    }
}
