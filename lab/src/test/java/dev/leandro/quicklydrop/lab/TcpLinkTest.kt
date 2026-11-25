package dev.leandro.quicklydrop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TcpLinkTest {

    private class Harness(ackMs: Int = 10_000, idleMs: Int = 30_000) {
        val statuses = CopyOnWriteArrayList<LabStatus>()
        val done = CountDownLatch(1)
        val error = CountDownLatch(1)
        val link = TcpLink(
            onStatus = { status ->
                statuses += status
                if (status.mbps != null) done.countDown()
                if (status.phase == Phase.ERROR) error.countDown()
            },
            ackMs = ackMs,
            idleMs = idleMs,
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `transfers 16 MB and keeps setup separate from total`() {
        val port = freePort()
        val receiver = Harness()
        val sender = Harness()

        receiver.link.serve(port)
        Thread.sleep(200)
        sender.link.send("127.0.0.1", port)

        assertTrue("receiver did not finish", receiver.done.await(20, TimeUnit.SECONDS))
        assertTrue("sender did not finish", sender.done.await(20, TimeUnit.SECONDS))

        val receiverConnected = receiver.statuses.first { it.phase == Phase.CONNECTED && it.mbps == null }
        val receiverFinal = receiver.statuses.last { it.mbps != null }
        val senderConnected = sender.statuses.first { it.phase == Phase.CONNECTED && it.mbps == null }
        val senderFinal = sender.statuses.last { it.mbps != null }

        assertEquals(receiverConnected.setupMs, receiverFinal.setupMs)
        assertEquals(senderConnected.setupMs, senderFinal.setupMs)
        assertTrue(receiverFinal.mbps!! > 1.0)
        assertTrue(senderFinal.mbps!! > 1.0)

        receiver.link.stop()
        sender.link.stop()
    }

    @Test
    fun `stop closes the sender active socket`() {
        val server = ServerSocket(0)
        val accepted = AtomicReference<Socket>()
        val eof = CountDownLatch(1)

        thread {
            val socket = server.accept()
            accepted.set(socket)
            try {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (socket.getInputStream().read(buffer) < 0) break
                }
            } catch (_: Exception) {
                // socket closed by stop
            } finally {
                eof.countDown()
            }
        }

        val sender = Harness(ackMs = 30_000)
        sender.link.send("127.0.0.1", server.localPort)
        repeat(500) {
            if (accepted.get() != null) return@repeat
            Thread.sleep(10)
        }
        assertTrue("sender did not connect", accepted.get() != null)
        Thread.sleep(200)
        sender.link.stop()

        assertTrue("sender socket remained open", eof.await(5, TimeUnit.SECONDS))
        server.close()
    }

    @Test
    fun `sender fails when the receiver does not acknowledge`() {
        val server = ServerSocket(0)

        thread {
            val socket = server.accept()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < TcpLink.PAYLOAD) {
                val read = socket.getInputStream().read(buffer)
                if (read < 0) break
                total += read
            }
            // Does not acknowledge: keeps reading until the sender closes.
            runCatching { socket.getInputStream().read() }
            runCatching { socket.close() }
        }

        val sender = Harness(ackMs = 300)
        sender.link.send("127.0.0.1", server.localPort)

        assertTrue("sender did not report an error", sender.error.await(10, TimeUnit.SECONDS))
        val detail = sender.statuses.last { it.phase == Phase.ERROR }.detail
        assertTrue("unexpected message: $detail", detail.contains("did not acknowledge"))

        sender.link.stop()
        server.close()
    }

    @Test
    fun `receiver fails when the sender stops sending`() {
        val port = freePort()
        val receiver = Harness(idleMs = 300)

        receiver.link.serve(port)
        Thread.sleep(200)
        val client = Socket("127.0.0.1", port)

        assertTrue("receiver did not report an error", receiver.error.await(5, TimeUnit.SECONDS))
        val detail = receiver.statuses.last { it.phase == Phase.ERROR }.detail
        assertTrue("unexpected message: $detail", detail.contains("no data"))

        client.close()
        receiver.link.stop()
    }
}
