package dev.leandro.quicklydrop.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/**
 * Local hotspot + `WifiNetworkSpecifier`, with TCP delegated to [TcpLink].
 *
 * **The receiver hosts the hotspot.** It seems counterintuitive, but that is what gives
 * speed: the `WifiNetworkSpecifier` client stays in power saving and receives slowly
 * (measured 2.7 MB/s with Rx at 17 Mbps), while it transmits well (229 Mbps). Leaving the
 * hotspot on the receiving side, the client only sends, and the same pair of devices
 * jumped from 2.7 to 12.7 MB/s.
 */
class HotspotLink(
    private val context: Context,
    private val onStatus: (LabStatus) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val tcp = TcpLink(onStatus)

    @Volatile
    private var finished = false

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ------------------------------------------------------------- receiver

    /** Starts the hotspot and hands the credentials to whoever will publish them (BLE, NFC). */
    fun host(onCredentials: (ssid: String, pass: String) -> Unit) {
        onStatus(LabStatus(Phase.SEARCHING, "starting local hotspot"))

        val wifi = context.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            fail("WifiManager unavailable")
            return
        }

        wifi.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (finished) {
                        reservation.close()
                        return
                    }
                    hotspot = reservation
                    val config = reservation.wifiConfiguration
                    val ssid = config?.SSID
                    val pass = config?.preSharedKey
                    if (ssid == null || pass == null) {
                        fail("hotspot without credentials")
                        return
                    }
                    LabLog.add("hotspot ready ($ssid / ***)")
                    onCredentials(ssid, pass)
                    tcp.serve()
                }

                override fun onFailed(reason: Int) {
                    fail("hotspot failed (reason=$reason)")
                }
            },
            handler,
        )
    }

    // ------------------------------------------------------------- sender

    fun join(ssid: String, pass: String) {
        onStatus(LabStatus(Phase.CONNECTING, "joining hotspot $ssid"))

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            fail("ConnectivityManager unavailable")
            return
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()

        /**
         * Without `removeCapability(INTERNET)` the network stops being "local-only". Android
         * marks local networks as secondary and the radio enters power saving — measured at
         * 17 Mbps Rx versus 229 Mbps Tx, with RSSI -10. Since the sockets are explicitly
         * bound to this network, nothing leaks to the internet.
         */
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@HotspotLink.network = network
                val gateway = gatewayOf(cm.getLinkProperties(network))
                if (gateway == null) {
                    fail("hotspot without gateway")
                    return
                }
                LabLog.add("Wi-Fi: on hotspot, gateway=${gateway.hostAddress}")
                onStatus(LabStatus(Phase.CONNECTED, "on hotspot", gateway.hostAddress))
                acquireWifiLock()
                tcp.send(gateway.hostAddress) { socket -> network.bindSocket(socket) }
            }

            override fun onUnavailable() {
                fail("hotspot dialog declined")
            }
        }

        networkCallback = callback
        cm.requestNetwork(request, callback, 120_000)
    }

    // -------------------------------------------------------------- common

    private fun gatewayOf(props: LinkProperties?): InetAddress? {
        val route = props?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
        return route?.gateway ?: props?.dhcpServerAddress
    }

    /**
     * A WNS network is treated as "secondary" and the radio enters power saving —
     * measured at 0.8 MB/s versus 12 MB/s on the same network connected normally. The
     * low-latency lock takes the radio out of that mode for the duration of the transfer.
     */
    private fun acquireWifiLock() {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "QuicklyDrop:transfer")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onSuccess { LabLog.add("Wi-Fi: low-latency lock active") }
                    .onFailure { LabLog.add("Wi-Fi: lock failed — ${it.javaClass.simpleName}: ${it.message}") }
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { runCatching { it.release() } }
        wifiLock = null
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true

        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { callback -> cm?.let { runCatching { it.unregisterNetworkCallback(callback) } } }
        networkCallback = null
        network = null
        releaseWifiLock()

        runCatching { hotspot?.close() }
        hotspot = null

        tcp.stop()
    }
}
