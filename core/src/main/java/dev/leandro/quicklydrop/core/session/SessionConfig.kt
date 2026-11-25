package dev.leandro.quicklydrop.core.session

import dev.leandro.quicklydrop.core.net.TcpSockets

/** Timeouts and port shared by the send and receive sessions. */
data class SessionConfig(
    val port: Int = TcpSockets.PORT,
    val groupFrequencyMhz: Int = 5745,
    val acceptTimeoutMs: Int = 120_000,
    val handshakeTimeoutMs: Int = 15_000,
    val idleTimeoutMs: Int = 30_000,
) {
    init {
        require(port in 0..65535) { "invalid port: $port" }
        require(groupFrequencyMhz in 0..65535) { "invalid frequency: $groupFrequencyMhz" }
        require(acceptTimeoutMs > 0) { "accept timeout must be positive" }
        require(handshakeTimeoutMs > 0) { "handshake timeout must be positive" }
        require(idleTimeoutMs > 0) { "idle timeout must be positive" }
    }
}
