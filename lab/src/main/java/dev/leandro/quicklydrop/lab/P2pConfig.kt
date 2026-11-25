package dev.leandro.quicklydrop.lab

import android.net.wifi.p2p.WifiP2pConfig

/**
 * Builds the group/join configuration always with SSID and passphrase.
 *
 * `WifiP2pConfig.Builder` throws `IllegalStateException` if the frequency is set
 * without credentials — that was the crash in the first version of the forced 5 GHz
 * group. With SSID and passphrase, the framework uses the fast connection path and does
 * not ask for confirmation.
 */
internal object P2pConfig {

    fun group(ssid: String, pass: String, frequency: Int = 0): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .apply { if (frequency > 0) setGroupOperatingFrequency(frequency) }
            .build()
            .apply {
                // 0 = accept being a client; whoever creates the group is already the owner.
                groupOwnerIntent = 0
            }
}
