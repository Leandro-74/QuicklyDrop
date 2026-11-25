package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.net.TcpSockets
import dev.leandro.quicklydrop.core.p2p.P2pTransport
import dev.leandro.quicklydrop.core.transfer.FileSource
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferListener
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.core.transfer.TransferSender
import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import dev.leandro.quicklydrop.protocol.NoiseHandshake
import dev.leandro.quicklydrop.protocol.NoiseStream
import dev.leandro.quicklydrop.protocol.SecureChannel
import dev.leandro.quicklydrop.protocol.Transport
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joins the receiver's group and sends files over a Noise NK session.
 *
 * [run] blocks until the transfer ends. [cancel] closes the socket to interrupt a
 * blocked read or write.
 */
class SendSession(
    private val p2p: P2pTransport,
    private val files: List<FileSource>,
    private val listener: TransferListener = TransferListener.NONE,
    private val config: SessionConfig = SessionConfig(),
) : Closeable {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null

    fun cancel() {
        cancelled.set(true)
        runCatching { socket?.close() }
        runCatching { p2p.cancel() }
    }

    fun run(payload: DiscoveryPayload): TransferResult {
        if (payload.transport != Transport.P2P) {
            return TransferResult.Failure(
                TransferFailure.PROTOCOL,
                "unsupported transport: ${payload.transport}",
            )
        }
        if (payload.ssid.isEmpty() || payload.pass.isEmpty()) {
            return TransferResult.Failure(
                TransferFailure.PROTOCOL,
                "missing P2P credentials",
            )
        }
        if (cancelled.get()) {
            return TransferResult.Failure(TransferFailure.CANCELLED, "cancelled")
        }

        var handshake: NoiseHandshake? = null
        var secure: SecureChannel? = null
        val result = try {
            p2p.removeGroup()
            ensureActive()

            val host = p2p.connect(payload.ssid, payload.pass, payload.frequencyMhz)
            ensureActive()

            val connected = TcpSockets.connect(host, config.port, config.handshakeTimeoutMs)
            socket = connected
            ensureActive()
            connected.soTimeout = config.handshakeTimeoutMs

            val activeHandshake = NoiseHandshake.initiator(payload.noisePublicKey)
            handshake = activeHandshake
            NoiseStream.write(activeHandshake, connected.getOutputStream())
            NoiseStream.read(activeHandshake, connected.getInputStream())
            ensureActive()

            connected.soTimeout = config.idleTimeoutMs
            val channel = SecureChannel(
                input = connected.getInputStream(),
                output = connected.getOutputStream(),
                transport = activeHandshake.split(),
            )
            secure = channel
            TransferSender(channel, listener).send(files) { cancelled.get() }
        } catch (e: SocketTimeoutException) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.TIMEOUT, "connection timed out", e)
            }
        } catch (e: IOException) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.IO, e.message ?: "send failed", e)
            }
        } catch (e: Exception) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.IO, e.message ?: "send failed", e)
            }
        } finally {
            runCatching { secure?.destroy() }
            runCatching { handshake?.destroy() }
            runCatching { socket?.close() }
            socket = null
            runCatching { p2p.close() }
        }

        return result
    }

    override fun close() = cancel()

    private fun ensureActive() {
        if (cancelled.get()) throw CancellationException("cancelled")
    }
}
