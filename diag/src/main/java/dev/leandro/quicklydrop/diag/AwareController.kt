package dev.leandro.quicklydrop.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Exercises the complete Wi-Fi Aware path: attach, publish/subscribe, data path and
 * throughput. It is the test that decides whether QuicklyDrop can use NAN as its main transport.
 *
 * Flow between two devices:
 *   1. Both tap "Attach".
 *   2. One taps "Publish", the other "Subscribe".
 *   3. The publisher appears in the subscriber's list. It taps "Connect".
 *   4. The subscriber brings up the server, notifies the publisher, and the publisher sends PAYLOAD_BYTES.
 *   5. Both sides print the measured throughput.
 */
class AwareController(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        const val SERVICE_NAME = "QuicklyDropDiag"
        const val PSK = "quicklydrop-diag-psk"
        const val PORT = 8988
        const val PAYLOAD_BYTES = 32L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val NETWORK_TIMEOUT_MS = 30_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val manager = context.getSystemService(WifiAwareManager::class.java)

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peer: PeerHandle? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var pendingAction: (() -> Unit)? = null
    private var attaching = false

    // ---------------------------------------------------------------- attach

    fun attach() {
        if (session != null) {
            log("Aware: already attached to the service")
            return
        }
        if (attaching) {
            log("Aware: attach already in progress")
            return
        }
        if (manager == null) {
            log("Aware: service unavailable on this device (getSystemService = null)")
            return
        }
        if (!manager.isAvailable) {
            log("Aware: isAvailable = false — device does not support NAN or is turned off")
            return
        }

        attaching = true
        log("Aware: attach started")

        manager.attach(object : AttachCallback() {
            override fun onAttached(awareSession: WifiAwareSession) {
                attaching = false
                session = awareSession
                log("Aware: attach OK")
                pendingAction?.invoke()
                pendingAction = null
            }

            override fun onAttachFailed() {
                attaching = false
                log("Aware: attach FAILED (onAttachFailed)")
                pendingAction = null
            }
        }, handler)
    }

    private fun withSession(action: () -> Unit) {
        if (session != null) {
            action()
        } else {
            pendingAction = action
            attach()
        }
    }

    // --------------------------------------------------------------- publish

    fun publish() = withSession {
        val awareSession = session ?: return@withSession
        if (publishSession != null) {
            log("Aware: publish already active")
            return@withSession
        }

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(deviceLabel().toByteArray())
            .build()

        awareSession.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(discoverySession: PublishDiscoverySession) {
                publishSession = discoverySession
                log("Aware: publish started as '${deviceLabel()}'")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val text = String(message)
                log("Aware: message received: $text")
                if (text == "GO") {
                    peer = peerHandle
                    startClient(peerHandle)
                }
            }

            override fun onSessionTerminated() {
                log("Aware: publish stopped")
                publishSession = null
            }
        }, handler)
    }

    // ------------------------------------------------------------- subscribe

    fun subscribe() = withSession {
        val awareSession = session ?: return@withSession
        if (subscribeSession != null) {
            log("Aware: subscribe already active")
            return@withSession
        }

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        awareSession.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(discoverySession: SubscribeDiscoverySession) {
                subscribeSession = discoverySession
                log("Aware: subscribe started, looking for '$SERVICE_NAME'")
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?,
            ) {
                peer = peerHandle
                val name = serviceSpecificInfo?.let { String(it) } ?: "no name"
                log("Aware: peer discovered ($name) — tap Connect")
            }

            override fun onSessionTerminated() {
                log("Aware: subscribe stopped")
                subscribeSession = null
            }
        }, handler)
    }

    // --------------------------------------------------------------- connect

    /** Subscriber side: brings up the server and notifies the publisher to start sending. */
    fun connect() {
        val discovery = subscribeSession
        val peerHandle = peer
        if (discovery == null || peerHandle == null) {
            log("Aware: connect after discovering a peer (subscribe + Publish on the other device)")
            return
        }

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peerHandle)
            .setPskPassphrase(PSK)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        log("Aware: requesting data path (server)")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Aware: data path available (server)")
                executor.execute {
                    try {
                        val server = openServerSocket(PORT)
                        log("Aware: server listening on port $PORT")
                        discovery.sendMessage(peerHandle, 0, "GO".toByteArray())
                        log("Aware: 'GO' sent to publisher")
                        server.soTimeout = NETWORK_TIMEOUT_MS
                        val socket = server.accept()
                        log("Aware: client connected from ${socket.inetAddress?.hostAddress}")
                        receiveAndMeasure(socket)
                    } catch (e: Exception) {
                        log("Aware: server error — ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            override fun onUnavailable() {
                log("Aware: data path unavailable ($NETWORK_TIMEOUT_MS ms timeout)")
            }

            override fun onLost(network: Network) {
                log("Aware: data path lost")
            }
        }

        networkCallback = callback
        connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
    }

    /** Publisher side: joins the network and sends the payload. */
    private fun startClient(peerHandle: PeerHandle) {
        val discovery = publishSession ?: return

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peerHandle)
            .setPskPassphrase(PSK)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        log("Aware: requesting data path (client)")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Aware: data path available (client)")
                val info = connectivity.getNetworkCapabilities(network)?.transportInfo as? WifiAwareNetworkInfo
                val address: InetAddress? = info?.peerIpv6Addr
                if (address == null) {
                    log("Aware: peerIpv6Addr null — cannot connect")
                    return
                }
                executor.execute {
                    try {
                        val socket = network.socketFactory.createSocket(address, PORT)
                        socket.soTimeout = NETWORK_TIMEOUT_MS
                        log("Aware: connected to server ${address.hostAddress}")
                        sendAndMeasure(socket)
                    } catch (e: Exception) {
                        log("Aware: client error — ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            override fun onUnavailable() {
                log("Aware: data path unavailable on client (timeout)")
            }
        }

        networkCallback = callback
        connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
    }

    // ----------------------------------------------------------- throughput

    private fun sendAndMeasure(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            val start = System.nanoTime()

            while (sent < PAYLOAD_BYTES) {
                val size = minOf(CHUNK.toLong(), PAYLOAD_BYTES - sent).toInt()
                out.write(buffer, 0, size)
                sent += size
            }
            out.flush()

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (sent / 1024.0 / 1024.0) / seconds
            log("Aware: sent ${sent / 1024 / 1024} MB in %.2f s = %.1f MB/s".format(seconds, mbps))
        } catch (e: Exception) {
            log("Aware: send failed — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun receiveAndMeasure(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(CHUNK)
            var received = 0L
            val start = System.nanoTime()

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                received += read
            }

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (received / 1024.0 / 1024.0) / seconds
            log("Aware: received ${received / 1024 / 1024} MB in %.2f s = %.1f MB/s".format(seconds, mbps))
        } catch (e: Exception) {
            log("Aware: receive failed — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ stop

    fun stop() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null

        runCatching { publishSession?.close() }
        publishSession = null
        runCatching { subscribeSession?.close() }
        subscribeSession = null
        runCatching { session?.close() }
        session = null

        peer = null
        pendingAction = null
        attaching = false
        log("Aware: everything stopped")
    }

    /**
     * Android does not expose a ServerSocket bound to a Network (SocketFactory has no
     * createServerSocket). The Aware data path is an isolated interface protected by PSK,
     * so a plain ServerSocket on the agreed port is enough for the diagnostic.
     */
    private fun openServerSocket(port: Int): ServerSocket = ServerSocket(port)

    private fun deviceLabel(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
}
