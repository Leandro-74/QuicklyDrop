package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.p2p.P2pTransport

internal class FakeP2pTransport(
    private val host: String = "127.0.0.1",
    private val frequency: Int = 5745,
) : P2pTransport {

    var removeCalls = 0
    var cancelCalls = 0
    var closed = false
    var createdSsid: String? = null
    var createdPass: String? = null
    var connectedSsid: String? = null

    override fun removeGroup() {
        removeCalls++
    }

    override fun createGroup(ssid: String, pass: String, frequencyMhz: Int): Int {
        createdSsid = ssid
        createdPass = pass
        return frequency
    }

    override fun connect(ssid: String, pass: String, frequencyMhz: Int): String {
        connectedSsid = ssid
        return host
    }

    override fun cancel() {
        cancelCalls++
    }

    override fun close() {
        closed = true
    }
}
