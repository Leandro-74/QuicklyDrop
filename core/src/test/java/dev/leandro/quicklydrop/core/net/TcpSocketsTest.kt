package dev.leandro.quicklydrop.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TcpSocketsTest {

    @Test
    fun `server accepts a client and exchanges bytes`() {
        val server = TcpSockets.openServer(port = 0)
        val accepted = AtomicReference<java.net.Socket>()
        val ready = CountDownLatch(1)

        thread {
            val socket = TcpSockets.accept(server)
            accepted.set(socket)
            ready.countDown()
            val buffer = ByteArray(4)
            var read = 0
            while (read < buffer.size) {
                val count = socket.getInputStream().read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            socket.getOutputStream().write(buffer)
            socket.getOutputStream().flush()
            socket.close()
        }

        val client = TcpSockets.connect("127.0.0.1", server.localPort)
        assertTrue("server did not accept", ready.await(5, TimeUnit.SECONDS))
        client.getOutputStream().write("ping".toByteArray())
        client.getOutputStream().flush()

        val response = ByteArray(4)
        var read = 0
        while (read < response.size) {
            val count = client.getInputStream().read(response, read, response.size - read)
            if (count < 0) break
            read += count
        }

        assertArrayEquals("ping".toByteArray(), response)

        client.close()
        accepted.get().close()
        server.close()
    }

    @Test
    fun `accept times out when no client connects`() {
        val server = TcpSockets.openServer(port = 0, acceptTimeoutMs = 200)

        val error = runCatching { TcpSockets.accept(server) }.exceptionOrNull()

        assertTrue("expected a timeout, got $error", error is SocketTimeoutException)
        server.close()
    }

    @Test
    fun `connect fails on a closed port`() {
        val port = TcpSockets.openServer(port = 0).use { it.localPort }

        val error = runCatching { TcpSockets.connect("127.0.0.1", port, timeoutMs = 500) }

        assertTrue("expected a connection failure", error.isFailure)
    }

    @Test
    fun `rejects invalid ports`() {
        val error = runCatching { TcpSockets.openServer(port = 70_000) }
        assertTrue(error.isFailure)
    }
}
