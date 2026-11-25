package dev.leandro.quicklydrop.core.nfc

import dev.leandro.quicklydrop.protocol.DiscoveryPayload

/**
 * Published by the receiver's HCE service and consumed by the sender's reader mode.
 *
 * The payload lives only in memory. If the process dies the window is disarmed and the
 * app must arm it again.
 */
object NfcBeacon {

    @Volatile
    var payload: ByteArray? = null
        private set

    fun arm(discovery: DiscoveryPayload) {
        payload = discovery.encode()
    }

    fun disarm() {
        payload = null
    }
}
