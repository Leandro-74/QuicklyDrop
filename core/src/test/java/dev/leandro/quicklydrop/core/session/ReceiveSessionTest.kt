package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.net.TcpSockets
import dev.leandro.quicklydrop.core.transfer.TestFileSink
import dev.leandro.quicklydrop.core.transfer.TestFileSource
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.core.transfer.TransferSender
import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.NoiseHandshake
import dev.leandro.quicklydrop.protocol.NoiseStream
import dev.leandro.quicklydrop.protocol.SecureChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReceiveSessionTest {

    private val config = SessionConfig(
        port = 0,
        acceptTimeoutMs = 10_000,
        handshakeTimeoutMs = 5_000,
        idleTimeoutMs = 5_000,
    )

    @Test
    fun `arms nfc and receives a file`() {
        val p2p = FakeP2pTransport()
        val sink = TestFileSink()
        val armed = CountDownLatch(1)
        val published = AtomicReference<DiscoveryPayload>()
        val result = AtomicReference<TransferResult>()

        val listener = object : ReceiveSession.Listener {
            override fun onArmed(payload: DiscoveryPayload) {
                published.set(payload)
                armed.countDown()
            }
        }
        val session = ReceiveSession(
            p2p = p2p,
            sink = sink,
            displayName = "Receiver",
            listener = listener,
            config = config,
            publish = {},
            clear = {},
        )

        val receiverThread = thread { result.set(session.run()) }
        assertTrue("session was not armed", armed.await(5, TimeUnit.SECONDS))

        val payload = published.get()!!
        assertNotNull(p2p.createdSsid)
        assertEquals(payload.ssid, p2p.createdSsid)
        assertEquals(payload.pass, p2p.createdPass)
        assertEquals(5745, payload.frequencyMhz)
        assertEquals(32, payload.noisePublicKey.size)

        val bytes = "hello".toByteArray()
        val senderResult = TcpSockets.connect("127.0.0.1", session.localPort, 5_000).use { socket ->
            socket.soTimeout = 5_000
            val initiator = NoiseHandshake.initiator(payload.noisePublicKey)
            NoiseStream.write(initiator, socket.getOutputStream())
            NoiseStream.read(initiator, socket.getInputStream())
            val channel = SecureChannel(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                transport = initiator.split(),
            )
            TransferSender(channel).send(
                listOf(TestFileSource(FileMeta("a.txt", 5, "text/plain"), bytes)),
            )
        }

        receiverThread.join(10_000)
        assertTrue("sender failed: $senderResult", senderResult is TransferResult.Success)
        assertTrue("receiver failed: ${result.get()}", result.get() is TransferResult.Success)
        assertArrayEquals(bytes, sink.files["a.txt"])
        assertTrue(p2p.closed)
        assertTrue(p2p.removeCalls >= 1)
    }

    @Test
    fun `times out when nobody connects`() {
        val session = ReceiveSession(
            p2p = FakeP2pTransport(),
            sink = TestFileSink(),
            displayName = "Receiver",
            listener = ReceiveSession.Listener.NONE,
            config = SessionConfig(port = 0, acceptTimeoutMs = 200),
            publish = {},
            clear = {},
        )

        val result = session.run()

        assertTrue("expected timeout, got $result", result is TransferResult.Failure)
        assertEquals(TransferFailure.TIMEOUT, (result as TransferResult.Failure).failure)
    }

    @Test
    fun `cancel interrupts an armed session`() {
        val armed = CountDownLatch(1)
        val result = AtomicReference<TransferResult>()
        val p2p = FakeP2pTransport()
        val session = ReceiveSession(
            p2p = p2p,
            sink = TestFileSink(),
            displayName = "Receiver",
            listener = object : ReceiveSession.Listener {
                override fun onArmed(payload: DiscoveryPayload) {
                    armed.countDown()
                }
            },
            config = config,
            publish = {},
            clear = {},
        )

        val receiverThread = thread { result.set(session.run()) }
        assertTrue(armed.await(5, TimeUnit.SECONDS))
        session.cancel()
        receiverThread.join(5_000)

        assertTrue("expected cancellation, got ${result.get()}", result.get() is TransferResult.Failure)
        assertEquals(
            TransferFailure.CANCELLED,
            (result.get() as TransferResult.Failure).failure,
        )
        assertEquals(1, p2p.cancelCalls)
    }
}
