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

    /**
     * Requests cancellation without waiting for framework callbacks.
     *
     * Safe to call from the main thread. The blocking session still performs its full
     * cleanup in [close] on the worker thread.
     */
    fun cancel()

    override fun close()
}

/**
 * Wi-Fi Direct failure with a message suitable for the UI and logs.
 *
 * [reason] is the framework failure code when the framework reported one.
 */
class P2pException(
    message: String,
    cause: Throwable? = null,
    val reason: Int? = null,
) : Exception(message, cause)
