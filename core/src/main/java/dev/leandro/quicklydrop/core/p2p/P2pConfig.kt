package dev.leandro.quicklydrop.core.p2p

import android.net.wifi.p2p.WifiP2pConfig

/**
 * Builds group and join configurations with SSID and passphrase.
 *
 * The credentials path avoids provision discovery and the confirmation dialog on the
 * group owner. The builder throws if a frequency is set without credentials.
 */
object P2pConfig {

    fun group(ssid: String, pass: String, frequencyMhz: Int = 0): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .apply {
                if (frequencyMhz > 0) setGroupOperatingFrequency(frequencyMhz)
            }
            .build()
            .apply {
                // 0 means the peer accepts the client role; the creator is the owner.
                groupOwnerIntent = 0
            }
}
