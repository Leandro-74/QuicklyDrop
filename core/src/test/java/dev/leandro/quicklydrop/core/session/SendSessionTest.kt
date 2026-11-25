package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.transfer.TestFileSink
import dev.leandro.quicklydrop.core.transfer.TestFileSource
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferReceiver
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.NoiseHandshake
import dev.leandro.quicklydrop.protocol.NoiseStream
import dev.leandro.quicklydrop.protocol.SecureChannel
import dev.leandro.quicklydrop.protocol.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SendSessionTest {

    @Test
    fun `joins the group and sends a file`() {
        val server = ServerSocket(0)
        val responder = NoiseHandshake.responder()
        val sink = TestFileSink()
        val receiverResult = AtomicReference<TransferResult>()
        val receiverError = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        val receiverThread = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
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

        val bytes = "payload".toByteArray()
        val payload = DiscoveryPayload(
            name = "Receiver",
            ssid = "DIRECT-QD-TEST",
            pass = "12345678",
            frequencyMhz = 5745,
            noisePublicKey = responder.localPublicKey(),
        )
        val p2p = FakeP2pTransport()
        val session = SendSession(
            p2p = p2p,
            files = listOf(TestFileSource(FileMeta("a.txt", bytes.size.toLong(), "text/plain"), bytes)),
            config = SessionConfig(
                port = server.localPort,
                handshakeTimeoutMs = 5_000,
                idleTimeoutMs = 5_000,
            ),
        )

        val result = session.run(payload)

        assertTrue("receiver did not finish", done.await(10, TimeUnit.SECONDS))
        assertNull("receiver failed: ${receiverError.get()}", receiverError.get())
        assertTrue("sender failed: $result", result is TransferResult.Success)
        assertTrue(
            "receiver failed: ${receiverResult.get()}",
            receiverResult.get() is TransferResult.Success,
        )
        assertArrayEquals(bytes, sink.files["a.txt"])
        assertEquals(payload.ssid, p2p.connectedSsid)
        assertTrue(p2p.closed)

        server.close()
        receiverThread.join(5_000)
    }

    @Test
    fun `rejects a non p2p payload`() {
        val payload = DiscoveryPayload(
            name = "Receiver",
            ssid = "",
            pass = "",
            frequencyMhz = 0,
            noisePublicKey = ByteArray(32),
            transport = Transport.HOTSPOT,
        )

        val result = SendSession(
            p2p = FakeP2pTransport(),
            files = emptyList(),
        ).run(payload)

        assertTrue("expected failure, got $result", result is TransferResult.Failure)
        assertEquals(TransferFailure.PROTOCOL, (result as TransferResult.Failure).failure)
    }
}
