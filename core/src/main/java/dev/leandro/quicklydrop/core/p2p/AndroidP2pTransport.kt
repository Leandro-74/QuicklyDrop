package dev.leandro.quicklydrop.core.p2p

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android implementation of [P2pTransport].
 *
 * All operations are blocking and must be called from a worker thread. The framework
 * callbacks stay on the main looper; the worker only waits for them.
 */
class AndroidP2pTransport(
    context: Context,
    private val operationTimeoutMs: Long = 10_000,
    private val groupTimeoutMs: Long = 60_000,
) : P2pTransport {

    private val manager: WifiP2pManager
    private var channel: WifiP2pManager.Channel?

    @Volatile
    private var cancelled = false

    init {
        val appContext = context.applicationContext
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
        try {
            action(2_000) { manager.removeGroup(current, it) }
        } catch (_: P2pException) {
            // No group or a framework BUSY; there is nothing else to clean up here.
        }
    }

    override fun createGroup(ssid: String, pass: String, frequencyMhz: Int): Int {
        val current = requireChannel()
        if (cancelled) throw P2pException("cancelled")
        try {
            create(current, ssid, pass, frequencyMhz)
        } catch (e: P2pException) {
            if (frequencyMhz == 0 || cancelled) throw e
            create(current, ssid, pass, 0)
        }
        if (cancelled) throw P2pException("cancelled")
        return awaitGroupFrequency(current)
    }

    override fun connect(ssid: String, pass: String, frequencyMhz: Int): String {
        val current = requireChannel()
        if (cancelled) throw P2pException("cancelled")
        try {
            join(current, ssid, pass, frequencyMhz)
        } catch (e: P2pException) {
            if (frequencyMhz == 0 || cancelled) throw e
            join(current, ssid, pass, 0)
        }

        val deadline = System.currentTimeMillis() + groupTimeoutMs
        while (!cancelled && System.currentTimeMillis() < deadline) {
            val info = connectionInfo(current)
            if (info?.groupFormed == true) {
                return info.groupOwnerAddress?.hostAddress
                    ?: throw P2pException("group formed without an owner address")
            }
            sleep(500)
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
        runCatching { manager.stopPeerDiscovery(current, null) }
        runCatching { manager.cancelConnect(current, null) }
        removeGroup()
        channel = null
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
        val deadline = System.currentTimeMillis() + 5_000
        while (!cancelled && System.currentTimeMillis() < deadline) {
            val frequency = groupInfo(current)?.frequency ?: 0
            if (frequency > 0) return frequency
            sleep(300)
        }
        if (cancelled) throw P2pException("cancelled")
        return 0
    }

    private fun groupInfo(current: WifiP2pManager.Channel): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pGroup?>()
        try {
            manager.requestGroupInfo(current) {
                result.set(it)
                latch.countDown()
            }
        } catch (e: SecurityException) {
            throw P2pException("Wi-Fi Direct permission denied", e)
        }
        latch.await(2, TimeUnit.SECONDS)
        return result.get()
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
        latch.await(2, TimeUnit.SECONDS)
        return result.get()
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
        failure.get()?.let { throw P2pException("Wi-Fi Direct failed: ${reasonName(it)}") }
    }

    private fun requireChannel(): WifiP2pManager.Channel =
        channel ?: throw P2pException("Wi-Fi Direct is closed")

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
}
