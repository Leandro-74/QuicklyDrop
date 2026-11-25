package dev.leandro.quicklydrop.diag

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import java.io.File

data class Cap(
    val label: String,
    val ok: Boolean,
    val detail: String,
)

/**
 * Collects what the device actually offers. It is Phase 0's most important output:
 * without it, any architecture decision is a guess.
 */
object Capabilities {

    fun collect(context: Context): List<Cap> {
        val pm = context.packageManager
        fun feature(name: String) = pm.hasSystemFeature(name)

        val aware = context.getSystemService(WifiAwareManager::class.java)
        val nfc = NfcAdapter.getDefaultAdapter(context)
        val bt = context.getSystemService(BluetoothManager::class.java)

        return listOf(
            Cap(
                "Wi-Fi Aware (NAN)",
                feature(PackageManager.FEATURE_WIFI_AWARE),
                buildString {
                    append("feature=").append(feature(PackageManager.FEATURE_WIFI_AWARE))
                    append(", isAvailable=").append(aware?.isAvailable)
                },
            ),
            Cap(
                "Wi-Fi Direct (P2P)",
                feature(PackageManager.FEATURE_WIFI_DIRECT),
                "feature=${feature(PackageManager.FEATURE_WIFI_DIRECT)}",
            ),
            Cap(
                "Wi-Fi RTT (802.11mc)",
                feature(PackageManager.FEATURE_WIFI_RTT),
                "feature=${feature(PackageManager.FEATURE_WIFI_RTT)}",
            ),
            Cap(
                "NFC",
                feature(PackageManager.FEATURE_NFC),
                buildString {
                    append("feature=").append(feature(PackageManager.FEATURE_NFC))
                    append(", adapter=").append(if (nfc == null) "null" else "present")
                    append(", enabled=").append(nfc?.isEnabled)
                },
            ),
            Cap(
                "NFC Host Card Emulation",
                feature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
                "feature=${feature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)}",
            ),
            Cap(
                "Bluetooth LE",
                feature(PackageManager.FEATURE_BLUETOOTH_LE),
                buildString {
                    append("feature=").append(feature(PackageManager.FEATURE_BLUETOOTH_LE))
                    append(", adapter=").append(if (bt?.adapter == null) "null" else "present")
                    append(", enabled=").append(bt?.adapter?.isEnabled)
                },
            ),
            Cap(
                "UWB",
                feature(PackageManager.FEATURE_UWB),
                "feature=${feature(PackageManager.FEATURE_UWB)}",
            ),
            Cap(
                "Android",
                true,
                "SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}), ${Build.MANUFACTURER} ${Build.MODEL}",
            ),
            Cap(
                "Root",
                isRooted(),
                if (isRooted()) "su found / test-keys" else "not detected (KernelSU hides from regular apps)",
            ),
        )
    }

    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
        )
        if (paths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        return File("/data/adb/ksu").exists() || File("/data/adb/magisk").exists()
    }
}
