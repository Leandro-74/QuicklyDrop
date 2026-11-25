package dev.leandro.quicklydrop.core.p2p

import java.io.Closeable

/** Blocking Wi-Fi Direct operations used by the send and receive sessions. */
interface P2pTransport : Closeable {

    /** Removes a stale group. A missing group is not an error. */
    fun removeGroup()

    /**
     * Creates a group and returns the actual frequency in MHz.
     *
     * A zero frequency means the platform did not report it.
     */
    fun createGroup(ssid: String, pass: String, frequencyMhz: Int): Int

    /** Joins a group created by [ssid] and returns the group owner address. */
    fun connect(ssid: String, pass: String, frequencyMhz: Int): String

    override fun close()
}

/** Wi-Fi Direct failure with a message suitable for the UI and logs. */
class P2pException(message: String, cause: Throwable? = null) : Exception(message, cause)
