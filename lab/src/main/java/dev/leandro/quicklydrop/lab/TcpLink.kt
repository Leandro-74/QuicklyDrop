package dev.leandro.quicklydrop.lab

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The 16 MB TCP transfer, the same for any transport underneath (local hotspot,
 * Wi-Fi Direct). The receiver calls [serve]; the sender calls [send].
 *
 * The active socket is kept so that [stop] can interrupt a transfer in progress:
 * closing the `ServerSocket` releases the `accept`, but not the already accepted socket.
 */
class TcpLink(
    private val onStatus: (LabStatus) -> Unit,
    private val ackMs: Int = ACK_MS,
    private val idleMs: Int = IDLE_MS,
) {

    companion object {
        const val PORT = 8992
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val ACCEPT_MS = 300_000

        /** After connecting, how long to wait for data before considering the session dead. */
        private const val IDLE_MS = 30_000

        /** How long the sender waits for the receiver's final ACK. */
        private const val ACK_MS = 10_000

        /** Large window: with the default (64 KB) the specifier RTT limits it to ~3.7 MB/s. */
        private const val SOCKET_BUFFER = 4 * 1024 * 1024
    }

    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var startedAt = System.currentTimeMillis()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var active: Socket? = null

    @Volatile
    private var finished = false

    /** Starts the server and waits for someone to send. */
    fun serve() = serve(PORT)

    fun serve(port: Int, onReady: () -> Unit = {}) {
        startedAt = System.currentTimeMillis()
        pool.execute {
            try {
                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.receiveBufferSize = SOCKET_BUFFER
                socketServer.bind(InetSocketAddress(port))
                socketServer.soTimeout = ACCEPT_MS
                server = socketServer
                onReady()

                LabLog.add("TCP: server on port $port")
                val socket = socketServer.accept()
                active = socket
                tune(socket)

                if (finished) {
                    closeQuietly(socket)
                    return@execute
                }

                val setupMs = System.currentTimeMillis() - startedAt
                val peer = socket.inetAddress?.hostAddress
                LabLog.add("TCP: connected in ${setupMs}ms with $peer")
                onStatus(LabStatus(Phase.CONNECTED, "connected in ${setupMs}ms", peer, setupMs = setupMs))

                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "receiving 16 MB", peer, setupMs = setupMs))
                receive(socket, peer, setupMs)
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            } finally {
                active = null
                runCatching { server?.close() }
                server = null
            }
        }
    }

    /** Connects to the host and pushes the 16 MB. [prepare] tunes the socket before connect. */
    fun send(host: String, prepare: (Socket) -> Unit = {}) = send(host, PORT, prepare)

    fun send(host: String, port: Int, prepare: (Socket) -> Unit = {}) {
        startedAt = System.currentTimeMillis()
        val socket = Socket()
        active = socket
        pool.execute {
            try {
                runCatching { socket.sendBufferSize = SOCKET_BUFFER }
                runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
                runCatching { socket.tcpNoDelay = true }
                prepare(socket)
                socket.connect(InetSocketAddress(host, port), 10_000)

                if (finished) return@execute

                val setupMs = System.currentTimeMillis() - startedAt
                LabLog.add("TCP: connected in ${setupMs}ms with $host")
                onStatus(LabStatus(Phase.CONNECTED, "connected in ${setupMs}ms", host, setupMs = setupMs))

                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "sending 16 MB", host, setupMs = setupMs))

                val out = DataOutputStream(socket.getOutputStream())
                val buffer = ByteArray(CHUNK)
                var sent = 0L
                val start = System.nanoTime()

                while (sent < PAYLOAD) {
                    if (finished) return@execute
                    val size = minOf(CHUNK.toLong(), PAYLOAD - sent).toInt()
                    out.write(buffer, 0, size)
                    sent += size
                }
                out.flush()

                // Only finishes when the receiver confirms that the 16 MB were drained. Without
                // this, the socket buffer makes the sender look faster than the network.
                socket.soTimeout = ackMs
                val ack = try {
                    socket.getInputStream().read()
                } catch (e: SocketTimeoutException) {
                    fail("TCP: receiver did not acknowledge within ${ackMs / 1000}s")
                    return@execute
                }
                check(ack >= 0) { "receiver did not acknowledge" }

                val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
                val mbps = (PAYLOAD / 1024.0 / 1024.0) / seconds
                LabLog.add("TCP: sent 16 MB in %.2f s = %.1f MB/s".format(seconds, mbps))
                onStatus(
                    LabStatus(
                        Phase.CONNECTED,
                        "sent 16 MB",
                        host,
                        mbps = mbps,
                        setupMs = setupMs,
                    ),
                )
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            } finally {
                active = null
                closeQuietly(socket)
            }
        }
    }

    private fun receive(socket: Socket, peer: String?, setupMs: Long) {
        try {
            socket.soTimeout = idleMs
            val input = DataInputStream(socket.getInputStream())
            val buffer = ByteArray(CHUNK)
            var received = 0L
            val start = System.nanoTime()
            var lastLog = start

            // Rate per second: shows whether the TCP window is growing or hitting a ceiling.
            while (received < PAYLOAD) {
                if (finished) return
                val want = minOf(buffer.size.toLong(), PAYLOAD - received).toInt()
                val read = try {
                    input.read(buffer, 0, want)
                } catch (e: SocketTimeoutException) {
                    fail("TCP: no data for ${idleMs / 1000}s")
                    return
                }
                if (read <= 0) break
                received += read
                val now = System.nanoTime()
                if (now - lastLog >= 1_000_000_000L) {
                    lastLog = now
                    LabLog.add("TCP: t=${(now - start) / 1_000_000_000.0}s ${received / 1024 / 1024} MB")
                }
            }

            // Confirms to the sender that everything arrived; it waits for this byte.
            if (received == PAYLOAD) {
                socket.getOutputStream().write(1)
                socket.getOutputStream().flush()
            }

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (received / 1024.0 / 1024.0) / seconds
            LabLog.add("TCP: received %.0f MB in %.2f s = %.1f MB/s".format(received / 1024.0 / 1024.0, seconds, mbps))
            onStatus(
                LabStatus(
                    Phase.CONNECTED,
                    "received ${received / 1024 / 1024} MB",
                    peer,
                    mbps = mbps,
                    setupMs = setupMs,
                ),
            )
        } catch (e: Exception) {
            if (!finished) fail("TCP: ${e.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    /** Large window in both directions and no Nagle: sending becomes a continuous stream. */
    private fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
        runCatching { socket.tcpNoDelay = true }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        runCatching { active?.close() }
        active = null
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }
}
