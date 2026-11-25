package dev.leandro.quicklydrop.diag

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Tests the transports that were left after the Edge 50 Ultra turned out to have no Wi-Fi Aware:
 * local hotspot (LocalOnlyHotspot) and BLE (advertise + scan).
 */
class RadioTests(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        val EASY_DROP_UUID: UUID = UUID.fromString("6d2f8a41-3c7e-4b95-a1d6-8f0e2c4b7a93")
        private const val SCAN_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    // -------------------------------------------------------------- hotspot

    fun startHotspot() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            log("Hotspot: WifiManager unavailable")
            return
        }
        if (hotspot != null) {
            log("Hotspot: already active")
            return
        }

        log("Hotspot: starting LocalOnlyHotspot")
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspot = reservation
                        val config = reservation.wifiConfiguration
                        log("Hotspot: ACTIVE ssid='${config?.SSID}' password='${config?.preSharedKey}'")
                    }

                    override fun onStopped() {
                        log("Hotspot: stopped")
                        hotspot = null
                    }

                    override fun onFailed(reason: Int) {
                        log("Hotspot: FAILED reason=$reason")
                        hotspot = null
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            log("Hotspot: exception ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stopHotspot() {
        hotspot?.close()
        hotspot = null
        log("Hotspot: stop requested")
    }

    // ------------------------------------------------------------ join (WNS)

    /**
     * Path the real app uses to join the other device's hotspot.
     * Unlike `cmd wifi connect-network`, here Android shows a system dialog
     * asking for confirmation — it is the product's real UX.
     */
    fun join(ssid: String, passphrase: String) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm == null) {
            log("Join: ConnectivityManager unavailable")
            return
        }

        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        log("Join: requesting '$ssid' (the system should show the dialog)")

        cm.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val props = cm.getLinkProperties(network)
                val ips = props?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }
                log("Join: CONNECTED — local ips: $ips")
            }

            override fun onUnavailable() {
                log("Join: unavailable (dialog declined or timeout)")
            }

            override fun onLost(network: android.net.Network) {
                log("Join: network lost")
            }
        }, 60_000)
    }

    // ------------------------------------------------------------------ BLE

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun startBleAdvertise() {
        val bt = adapter()
        if (bt == null) {
            log("BLE: adapter null")
            return
        }
        if (!bt.isEnabled) {
            log("BLE: adapter off")
            return
        }

        val adv = bt.bluetoothLeAdvertiser
        if (adv == null) {
            log("BLE: advertise not supported")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(EASY_DROP_UUID))
            .build()

        // A 128-bit UUID already takes up 18 of the packet's 31 bytes; the service data goes
        // in the scan response, which has its own space.
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(EASY_DROP_UUID), byteArrayOf(1, 0, 0, 1))
            .build()

        log("BLE: starting advertise")
        adv.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("BLE: advertise ACTIVE (tx=${settingsInEffect.txPowerLevel})")
            }

            override fun onStartFailure(errorCode: Int) {
                log("BLE: advertise FAILED ($errorCode)")
            }
        })
    }

    fun stopBleAdvertise() {
        advertiser?.stopAdvertising(null)
        advertiser = null
        log("BLE: advertise stopped")
    }

    fun startBleScan() {
        val bt = adapter()
        if (bt == null || !bt.isEnabled) {
            log("BLE: adapter unavailable/off")
            return
        }
        val scan = bt.bluetoothLeScanner
        if (scan == null) {
            log("BLE: scanner unavailable")
            return
        }
        scanner = scan

        val seen = LinkedHashMap<String, Int>()
        var quicklyDropHits = 0
        var bestQuicklyDrop = Int.MIN_VALUE

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val record = result.scanRecord
                val uuids = record?.serviceUuids?.map { it.uuid } ?: emptyList()
                val ours = record?.getServiceData(ParcelUuid(EASY_DROP_UUID))

                if (uuids.contains(EASY_DROP_UUID)) {
                    quicklyDropHits++
                    bestQuicklyDrop = maxOf(bestQuicklyDrop, result.rssi)
                    log(
                        "BLE: EASYDROP at $address rssi=${result.rssi} " +
                            "data=${ours?.joinToString(",") ?: "no data"}",
                    )
                }

                if (seen.put(address, result.rssi) == null && seen.size <= 20) {
                    val name = result.device.name ?: "?"
                    log("BLE: $name $address rssi=${result.rssi} uuids=${uuids.size}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("BLE: scan FAILED ($errorCode)")
            }
        }

        log("BLE: scanning for ${SCAN_MS / 1000}s")
        scan.startScan(callback)

        handler.postDelayed({
            scan.stopScan(callback)
            val strongest = seen.maxByOrNull { it.value }
            log(
                "BLE: scan finished — ${seen.size} devices, " +
                    "$quicklyDropHits QuicklyDrop advertisements, best rssi=$bestQuicklyDrop, " +
                    "strongest=${strongest?.key} (${strongest?.value} dBm)",
            )
        }, SCAN_MS)
    }
}
