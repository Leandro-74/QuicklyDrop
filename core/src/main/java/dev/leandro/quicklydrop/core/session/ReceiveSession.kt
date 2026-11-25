package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.net.TcpSockets
import dev.leandro.quicklydrop.core.nfc.NfcBeacon
import dev.leandro.quicklydrop.core.p2p.P2pTransport
import dev.leandro.quicklydrop.core.transfer.FileSink
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferListener
import dev.leandro.quicklydrop.core.transfer.TransferReceiver
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import dev.leandro.quicklydrop.protocol.NoiseHandshake
import dev.leandro.quicklydrop.protocol.NoiseStream
import dev.leandro.quicklydrop.protocol.SecureChannel
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arms a Wi-Fi Direct group, publishes the NFC payload and receives one session.
 *
 * [run] blocks until the session ends. [cancel] is safe from another thread and closes
 * the listening socket to interrupt an accept.
 */
class ReceiveSession(
    private val p2p: P2pTransport,
    private val sink: FileSink,
    private val displayName: String,
    private val listener: Listener = Listener.NONE,
    private val config: SessionConfig = SessionConfig(),
    private val publish: (DiscoveryPayload) -> Unit = { NfcBeacon.arm(it) },
    private val clear: () -> Unit = { NfcBeacon.disarm() },
    private val random: SecureRandom = SecureRandom(),
) : Closeable {

    interface Listener : TransferListener {
        fun onArmed(payload: DiscoveryPayload) {}
        fun onCompleted(result: TransferResult) {}

        companion object {
            val NONE: Listener = object : Listener {}
        }
    }

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    internal var localPort: Int = config.port
        private set

    init {
        require(displayName.isNotBlank()) { "displayName is blank" }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { server?.close() }
        runCatching { socket?.close() }
        runCatching { p2p.cancel() }
    }

    fun run(): TransferResult {
        if (cancelled.get()) {
            return TransferResult.Failure(TransferFailure.CANCELLED, "cancelled")
        }

        var handshake: NoiseHandshake? = null
        var secure: SecureChannel? = null
        val result = try {
            p2p.removeGroup()
            ensureActive()

            val ssid = "DIRECT-QD-${token(4)}"
            val pass = token(12)
            val frequency = p2p.createGroup(ssid, pass, config.groupFrequencyMhz)
            ensureActive()

            val listening = TcpSockets.openServer(config.port, config.acceptTimeoutMs)
            server = listening
            localPort = listening.localPort
            ensureActive()

            val activeHandshake = NoiseHandshake.responder()
            handshake = activeHandshake
            val payload = DiscoveryPayload(
                name = displayName,
                ssid = ssid,
                pass = pass,
                frequencyMhz = frequency,
                noisePublicKey = activeHandshake.localPublicKey(),
            )
            publish(payload)
            listener.onArmed(payload)

            val accepted = TcpSockets.accept(listening)
            socket = accepted
            clear()
            ensureActive()

            accepted.soTimeout = config.handshakeTimeoutMs
            NoiseStream.read(activeHandshake, accepted.getInputStream())
            NoiseStream.write(activeHandshake, accepted.getOutputStream())
            ensureActive()

            accepted.soTimeout = config.idleTimeoutMs
            val channel = SecureChannel(
                input = accepted.getInputStream(),
                output = accepted.getOutputStream(),
                transport = activeHandshake.split(),
            )
            secure = channel
            TransferReceiver(channel, sink, listener).receive { cancelled.get() }
        } catch (e: SocketTimeoutException) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.TIMEOUT, "no sender connected", e)
            }
        } catch (e: IOException) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.IO, e.message ?: "receive failed", e)
            }
        } catch (e: Exception) {
            if (cancelled.get()) {
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                TransferResult.Failure(TransferFailure.IO, e.message ?: "receive failed", e)
            }
        } finally {
            runCatching { secure?.destroy() }
            runCatching { handshake?.destroy() }
            runCatching { clear() }
            closeQuietly()
            runCatching { p2p.close() }
        }

        runCatching { listener.onCompleted(result) }
        return result
    }

    override fun close() = cancel()

    private fun ensureActive() {
        if (cancelled.get()) throw CancellationException("cancelled")
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        runCatching { server?.close() }
        server = null
    }

    private fun token(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString {
            repeat(length) { append(chars[random.nextInt(chars.length)]) }
        }
    }
}
