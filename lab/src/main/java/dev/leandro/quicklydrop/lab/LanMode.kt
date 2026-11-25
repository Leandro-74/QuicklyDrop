package dev.leandro.quicklydrop.lab

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Simplest mode: both devices are already on the same Wi-Fi network.
 * Discovery over mDNS, direct TCP transport. No system dialogs.
 *
 * The transfer is the same [TcpLink] as P2P and hotspot: ACK, cancellation and the same
 * measurement. That way the numbers from the three paths are comparable.
 */
class LanMode(
    private val context: Context,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        const val SERVICE_TYPE = "_quicklydrop._tcp"
        const val PORT = 8991
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val tcp = TcpLink(onStatus)

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    @Volatile
    private var finished = false

    fun receiver() {
        onStatus(LabStatus(Phase.SEARCHING, "announcing on LAN"))
        tcp.serve(PORT) { if (!finished) registerService() }
    }

    fun sender() {
        onStatus(LabStatus(Phase.SEARCHING, "searching on LAN"))
        discover()
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "QuicklyDrop-${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                LabLog.add("mDNS: announced as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                LabLog.add("mDNS: registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                LabLog.add("mDNS: discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("quicklydrop")) return
                LabLog.add("mDNS: found ${info.serviceName}")
                onStatus(LabStatus(Phase.FOUND, "found ${info.serviceName}"))
                resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LabLog.add("mDNS: discovery failed ($errorCode)")
                onStatus(LabStatus(Phase.ERROR, "mDNS: error $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LabLog.add("mDNS: resolve failed ($errorCode)")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = if (Build.VERSION.SDK_INT >= 34) {
                    serviceInfo.hostAddresses.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host
                }

                val host = address?.hostAddress
                if (host == null) {
                    LabLog.add("mDNS: resolved without address")
                    return
                }

                LabLog.add("mDNS: ${serviceInfo.serviceName} at $host:${serviceInfo.port}")
                onStatus(LabStatus(Phase.CONNECTING, "connecting to $host"))
                tcp.send(host, serviceInfo.port)
            }
        }

        nsd.resolveService(info, listener)
    }

    fun stop() {
        finished = true

        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null

        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null

        tcp.stop()
        LabLog.add("LAN: stopped")
    }
}
