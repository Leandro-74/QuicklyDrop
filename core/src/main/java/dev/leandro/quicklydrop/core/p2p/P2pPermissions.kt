package dev.leandro.quicklydrop.core.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Permissions required by Wi-Fi Direct and the transfer notification.
 *
 * API 33+ uses nearby Wi-Fi devices; older versions still require fine location for
 * peer discovery and group creation. Bluetooth is not part of the product.
 */
object P2pPermissions {

    fun required(sdk: Int): List<String> = buildList {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun wifiPermission(sdk: Int): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasWifiPermission(context: Context): Boolean =
        context.checkSelfPermission(wifiPermission(Build.VERSION.SDK_INT)) ==
            PackageManager.PERMISSION_GRANTED
}
