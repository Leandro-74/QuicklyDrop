package dev.leandro.quicklydrop.core.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android implementation of [P2pTransport].
 *
 * All operations are blocking and must be called from a worker thread. The framework
 * callbacks stay on the main looper; the worker only waits for them.
 */
@SuppressLint("MissingPermission") // Checked by P2pPermissions; action() handles SecurityException.
class AndroidP2pTransport(
    context: Context,
    private val operationTimeoutMs: Long = 10_000,
    private val groupTimeoutMs: Long = 60_000,
) : P2pTransport {

    private val manager: WifiP2pManager
    private val wifiManager: WifiManager?
    private var channel: WifiP2pManager.Channel?

    @Volatile
    private var cancelled = false

    init {
        val appContext = context.applicationContext
        wifiManager = appContext.getSystemService(WifiManager::class.java)
        manager = appContext.getSystemService(WifiP2pManager::class.java)
            ?: throw P2pException("Wi-Fi Direct is not available")
        channel = try {
            manager.initialize(appContext, Looper.getMainLooper(), null)
        } catch (e: SecurityException) {
            throw P2pException("Wi-Fi Direct permission denied", e)
        }
        if (channel == null) throw P2pException("Wi-Fi Direct channel is not available")
    }

    override fun removeGroup() {
        val current = channel ?: return
        remove(current)
    }

    override fun createGroup(ssid: String, pass: String, frequencyMhz: Int): Int {
        val current = requireChannel()
        ensureReady()
        try {
            retryOnBusy { create(current, ssid, pass, frequencyMhz) }
        } catch (e: P2pException) {
            // BUSY means the framework is still tearing a group down, not that the
            // frequency is unsupported. Retrying on another channel would also fail.
            if (frequencyMhz == 0 || cancelled || e.reason == WifiP2pManager.BUSY) throw e
            retryOnBusy { create(current, ssid, pass, 0) }
        }
        if (cancelled) throw P2pException("cancelled")
        return awaitGroupFrequency(current)
    }

    override fun connect(ssid: String, pass: String, frequencyMhz: Int): String {
        val current = requireChannel()
        ensureReady()
        try {
            retryOnBusy { join(current, ssid, pass, frequencyMhz) }
        } catch (e: P2pException) {
            if (frequencyMhz == 0 || cancelled || e.reason == WifiP2pManager.BUSY) throw e
            retryOnBusy { join(current, ssid, pass, 0) }
        }

        val deadline = System.currentTimeMillis() + groupTimeoutMs
        while (!cancelled && System.currentTimeMillis() < deadline) {
            val info = connectionInfo(current)
            if (info?.groupFormed == true) {
                return info.groupOwnerAddress?.hostAddress
                    ?: throw P2pException("group formed without an owner address")
            }
            sleep(CONNECT_POLL_MS)
        }
        if (cancelled) throw P2pException("cancelled")
        throw P2pException("group did not form in ${groupTimeoutMs}ms")
    }

    override fun cancel() {
        cancelled = true
        val current = channel ?: return
        runCatching { manager.stopPeerDiscovery(current, null) }
        runCatching { manager.cancelConnect(current, null) }
        runCatching { manager.removeGroup(current, null) }
    }

    override fun close() {
        cancelled = true
        val current = channel ?: return
        channel = null
        runCatching { manager.stopPeerDiscovery(current, null) }
        runCatching { manager.cancelConnect(current, null) }
        runCatching { remove(current) }
        runCatching { current.close() }
    }

    private fun remove(current: WifiP2pManager.Channel) {
        // removeGroup fails with BUSY when no group exists and, on some devices, leaves
        // the framework in a state where the next createGroup is also BUSY.
        val state = queryGroupInfo(current)
        if (state.answered && state.group == null) return

        try {
            retryOnBusy {
                action(REMOVE_TIMEOUT_MS) { manager.removeGroup(current, it) }
            }
        } catch (_: P2pException) {
            // No group or a framework BUSY; createGroup retries the teardown race.
            return
        }

        // removeGroup only acknowledges the request. createGroup answers BUSY until the
        // group actually disappears, which can take a moment on Samsung devices.
        awaitGroupGone(current)
    }

    private fun create(
        current: WifiP2pManager.Channel,
        ssid: String,
        pass: String,
        frequencyMhz: Int,
    ) {
        action {
            manager.createGroup(current, P2pConfig.group(ssid, pass, frequencyMhz), it)
        }
    }

    private fun join(
        current: WifiP2pManager.Channel,
        ssid: String,
        pass: String,
        frequencyMhz: Int,
    ) {
        action {
            manager.connect(current, P2pConfig.group(ssid, pass, frequencyMhz), it)
        }
    }

    private fun awaitGroupFrequency(current: WifiP2pManager.Channel): Int {
        val deadline = System.currentTimeMillis() + FREQUENCY_TIMEOUT_MS
        while (!cancelled && System.currentTimeMillis() < deadline) {
            val frequency = queryGroupInfo(current).group?.frequency ?: 0
            if (frequency > 0) return frequency
            sleep(FREQUENCY_POLL_MS)
        }
        if (cancelled) throw P2pException("cancelled")
        return 0
    }

    private fun awaitGroupGone(current: WifiP2pManager.Channel) {
        val deadline = System.currentTimeMillis() + GROUP_GONE_TIMEOUT_MS
        while (!cancelled && System.currentTimeMillis() < deadline) {
            val state = queryGroupInfo(current)
            if (state.answered && state.group == null) return
            sleep(GROUP_GONE_POLL_MS)
        }
    }

    private fun queryGroupInfo(current: WifiP2pManager.Channel): GroupState {
        val latch = CountDownLatch(1)
        val group = AtomicReference<WifiP2pGroup?>()
        val answered = AtomicBoolean(false)
        try {
            manager.requestGroupInfo(current) {
                group.set(it)
                answered.set(true)
                latch.countDown()
            }
        } catch (e: SecurityException) {
            throw P2pException("Wi-Fi Direct permission denied", e)
        }
        latch.await(GROUP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return GroupState(answered.get(), group.get())
    }

    private fun connectionInfo(current: WifiP2pManager.Channel): WifiP2pInfo? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pInfo?>()
        try {
            manager.requestConnectionInfo(current) {
                result.set(it)
                latch.countDown()
            }
        } catch (e: SecurityException) {
            throw P2pException("Wi-Fi Direct permission denied", e)
        }
        latch.await(GROUP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    /**
     * Retries a framework action while it answers BUSY.
     *
     * The framework answers BUSY while a previous group is being removed and while its
     * state machine is settling, both of which clear on their own after a short wait.
     */
    private fun <T> retryOnBusy(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: P2pException) {
                if (e.reason != WifiP2pManager.BUSY || attempt >= BUSY_RETRIES) throw e
                attempt++
                sleep(BUSY_RETRY_MS * attempt)
                if (cancelled) throw P2pException("cancelled")
            }
        }
    }

    private fun action(
        timeoutMs: Long = operationTimeoutMs,
        operation: (WifiP2pManager.ActionListener) -> Unit,
    ) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Int?>()

        try {
            operation(
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        failure.set(reason)
                        latch.countDown()
                    }
                },
            )
        } catch (e: SecurityException) {
            throw P2pException("Wi-Fi Direct permission denied", e)
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw P2pException("Wi-Fi Direct operation timed out")
        }
        failure.get()?.let { reason ->
            val message = if (reason == WifiP2pManager.BUSY) {
                "Wi-Fi Direct is busy; turn Wi-Fi off and on and try again"
            } else {
                "Wi-Fi Direct failed: ${reasonName(reason)}"
            }
            throw P2pException(message, reason = reason)
        }
    }

    private fun requireChannel(): WifiP2pManager.Channel =
        channel ?: throw P2pException("Wi-Fi Direct is closed")

    private fun ensureReady() {
        if (cancelled) throw P2pException("cancelled")
        if (wifiManager?.isWifiEnabled == false) {
            throw P2pException("Wi-Fi is off; turn it on to use Wi-Fi Direct")
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw P2pException("Wi-Fi Direct interrupted", e)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> "code $reason"
    }

    private class GroupState(val answered: Boolean, val group: WifiP2pGroup?)

    private companion object {
        const val REMOVE_TIMEOUT_MS = 2_000L
        const val GROUP_INFO_TIMEOUT_MS = 2_000L
        const val FREQUENCY_TIMEOUT_MS = 5_000L
        const val FREQUENCY_POLL_MS = 300L
        const val CONNECT_POLL_MS = 500L
        const val GROUP_GONE_TIMEOUT_MS = 3_000L
        const val GROUP_GONE_POLL_MS = 150L
        const val BUSY_RETRIES = 3
        const val BUSY_RETRY_MS = 300L
    }
}
