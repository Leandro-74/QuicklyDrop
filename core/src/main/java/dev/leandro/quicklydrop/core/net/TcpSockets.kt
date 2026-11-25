package dev.leandro.quicklydrop.core.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP socket setup shared by the product transports.
 *
 * The buffer sizes are the same ones validated in the lab: the default 64 KB window
 * limits throughput on Wi-Fi Direct because of the round-trip time.
 */
object TcpSockets {

    /** Fixed port used by the receiver. The NFC payload does not carry a port. */
    const val PORT = 8992

    const val ACCEPT_TIMEOUT_MS = 300_000
    const val CONNECT_TIMEOUT_MS = 10_000

    /** Large window in both directions; validated at 4 MB in the lab. */
    const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024

    fun openServer(
        port: Int = PORT,
        acceptTimeoutMs: Int = ACCEPT_TIMEOUT_MS,
    ): ServerSocket {
        require(port in 0..65535) { "invalid port: $port" }
        require(acceptTimeoutMs > 0) { "accept timeout must be positive" }

        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.receiveBufferSize = SOCKET_BUFFER_BYTES
            server.bind(InetSocketAddress(port))
            server.soTimeout = acceptTimeoutMs
            return server
        } catch (e: Exception) {
            runCatching { server.close() }
            throw e
        }
    }

    fun accept(server: ServerSocket): Socket {
        val socket = server.accept()
        tune(socket)
        return socket
    }

    fun connect(
        host: String,
        port: Int = PORT,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): Socket {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 0..65535) { "invalid port: $port" }
        require(timeoutMs > 0) { "connect timeout must be positive" }

        val socket = Socket()
        try {
            tune(socket)
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER_BYTES }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER_BYTES }
        runCatching { socket.tcpNoDelay = true }
    }
}
