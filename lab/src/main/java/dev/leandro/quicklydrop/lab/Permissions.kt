package dev.leandro.quicklydrop.lab

import android.Manifest
import android.os.Build

/**
 * Permissions the lab needs to request, by Android version.
 *
 * On API 33+ Wi-Fi uses `NEARBY_WIFI_DEVICES`; requesting location as well only adds an
 * extra dialog. Below that (and on Android 12), location is still required.
 */
internal fun requiredPermissions(sdk: Int): List<String> = buildList {
    if (sdk < Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (sdk >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
