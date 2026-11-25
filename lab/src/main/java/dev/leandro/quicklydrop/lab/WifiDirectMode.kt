package dev.leandro.quicklydrop.lab

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct: the receiver creates the group (becomes owner, no dialog) and publishes its
 * own name + SSID + passphrase through the NFC tap. The sender discovers the peer and joins
 * the existing group, pushing the data to the owner, which is always 192.168.49.1.
 *
 * The advantage over the local hotspot is not depending on `WifiNetworkSpecifier` or the
 * confirmation dialog — the group already exists when the other side arrives.
 *
 * P2P discovery starts as soon as the sender is armed, not after the tap: when NFC
 * delivers the name, the address is usually already in the list. Measured: 0.6 s to 14 s
 * when discovery started only on the tap.
 */
class WifiDirectMode(
    private val activity: Activity,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        private const val CONNECT_MS = 60_000L
        private const val DISCOVER_MS = 60_000L
        private const val REDISCOVER_MS = 8_000L
        private const val POLL_MS = 500L

        /** Channel 149, non-DFS: avoids CAC and gives more spectrum than a 2.4 GHz group. */
        private const val GROUP_FREQUENCY = 5745
    }

    private val beam = NfcBeam(activity)
    private val tcp = TcpLink(onStatus)
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(WifiP2pManager::class.java)
    private val random = java.security.SecureRandom()

    private var channel: WifiP2pManager.Channel? = null

    /** Device name → P2P address, filled by discovery. */
    private val peers = mutableMapOf<String, String>()

    private var target: String? = null
    private var deadline = 0L
    private var lastDiscover = 0L
    private var polling = false

    /** Triggered by the P2P connection broadcast to wake the poll immediately. */
    private var groupListener: (() -> Unit)? = null
    private var groupReceiver: BroadcastReceiver? = null

    @Volatile
    private var finished = false

    fun start() {
        beam.problem()?.let {
            fail(it)
            return
        }
        val manager = manager
        if (manager == null) {
            fail("Wi-Fi Direct unavailable")
            return
        }

        channel = manager.initialize(activity, Looper.getMainLooper(), null)
        if (channel == null) {
            fail("Wi-Fi Direct channel unavailable")
            return
        }

        // An old group survives the force-stop and makes the connect create another group
        // instead of joining the other device's group. Cleaning up first keeps the test repeatable.
        P2pCleanup.remove(activity) {
            if (finished) return@remove
            if (role == Role.RECEIVER) startReceiver() else startSender()
        }
    }

    // ------------------------------------------------------------- receiver

    private fun startReceiver() {
        onStatus(LabStatus(Phase.SEARCHING, "creating Wi-Fi Direct group"))
        createGroup()
    }

    /**
     * Creates the group on the requested frequency with known credentials. The sender
     * receives SSID and passphrase over NFC and joins the group directly: no GO
     * negotiation, no WPS and no authorization dialog for `UserAuthorizingJoinState`.
     *
     * If the device cannot do 5 GHz, it falls back to automatic — better a group on
     * 2.4 GHz than no group at all.
     */
    private fun createGroup(frequency: Int = GROUP_FREQUENCY) {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return

        val ssid = "DIRECT-QD-${randomToken(4)}"
        val pass = randomToken(12)

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (finished) return
                val where = if (frequency == 0) "auto" else "$frequency MHz"
                LabLog.add("P2P: group $ssid created ($where)")
                publish(ssid, pass)
            }

            override fun onFailure(reason: Int) {
                if (finished) return
                if (frequency != 0) {
                    LabLog.add("P2P: $frequency MHz failed (${reasonName(reason)}), trying automatic")
                    createGroup(0)
                } else {
                    fail("createGroup failed (${reasonName(reason)})")
                }
            }
        }

        manager.createGroup(channel, P2pConfig.group(ssid, pass, frequency), listener)
    }

    private fun publish(ssid: String, pass: String, attempt: Int = 0) {
        val manager = manager ?: return
        val channel = channel ?: return

        manager.requestGroupInfo(channel) { group ->
            if (finished) return@requestGroupInfo
            val frequency = group?.frequency ?: 0
            if (frequency == 0 && attempt < 10) {
                handler.postDelayed({ publish(ssid, pass, attempt + 1) }, POLL_MS)
                return@requestGroupInfo
            }

            manager.requestDeviceInfo(channel) { device ->
                if (finished) return@requestDeviceInfo
                // The local MAC has been masked since Android 10; the name is what is left.
                val name = device?.deviceName?.takeIf { it.isNotBlank() }
                if (name == null) {
                    if (attempt >= 20) {
                        fail("no P2P name")
                        return@requestDeviceInfo
                    }
                    handler.postDelayed({ publish(ssid, pass, attempt + 1) }, POLL_MS)
                    return@requestDeviceInfo
                }

                LabLog.add("P2P: group $ssid on $frequency MHz, I am $name")
                // The frequency goes along so the sender scans only the group channel.
                beam.present("$name\n$ssid\n$pass\n$frequency") {
                    onStatus(LabStatus(Phase.CONNECTING, "tap detected, waiting for connection"))
                }
                onStatus(LabStatus(Phase.SEARCHING, "touch the other device"))
                tcp.serve()
            }
        }
    }

    private fun randomToken(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
    }

    // ------------------------------------------------------------- sender

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "touch the receiver"))

        beam.read(
            onPayload = { payload ->
                val lines = payload.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val name = lines.getOrNull(0)
                val ssid = lines.getOrNull(1)
                val pass = lines.getOrNull(2)
                val frequency = lines.getOrNull(3)?.toIntOrNull() ?: 0
                if (name == null) {
                    fail("NFC: invalid payload")
                    return@read
                }

                onStatus(LabStatus(Phase.FOUND, "tap detected"))
                if (ssid != null && pass != null) {
                    connectGroup(ssid, pass, frequency)
                } else {
                    // Older receiver published only the name; fall back to peer discovery.
                    target = name
                    deadline = System.currentTimeMillis() + DISCOVER_MS
                    startDiscovery()
                    tryConnect()
                }
            },
            onError = { fail(it) },
        )
    }

    private fun startDiscovery() {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return
        lastDiscover = System.currentTimeMillis()

        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (polling) return
                    polling = true
                    pollPeers()
                }

                override fun onFailure(reason: Int) {
                    LabLog.add("P2P: discoverPeers failed (${reasonName(reason)})")
                }
            },
        )
    }

    private fun pollPeers() {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return

        manager.requestPeers(channel) { result ->
            if (finished) return@requestPeers

            val list = result?.deviceList.orEmpty()
            list.forEach {
                if (peers.put(it.deviceName, it.deviceAddress) == null) {
                    LabLog.add("P2P: saw ${it.deviceName} at ${it.deviceAddress}")
                }
            }

            if (tryConnect()) return@requestPeers

            // Framework discovery stalls; reissue it from time to time.
            if (System.currentTimeMillis() - lastDiscover > REDISCOVER_MS) startDiscovery()

            handler.postDelayed({ pollPeers() }, POLL_MS)
        }
    }

    /** Connects if the target has already appeared. Returns true when the connection was started. */
    private fun tryConnect(): Boolean {
        val name = target ?: return false
        val address = peers[name]
        if (address == null) {
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                target = null
                fail("peer $name did not show up")
            }
            return false
        }

        target = null
        connect(address)
        return true
    }

    /**
     * Joins the group with SSID and passphrase, without going through provision discovery.
     * This is the path that avoids the confirmation dialog on the group owner and does not
     * need discovery.
     */
    private fun connectGroup(ssid: String, pass: String, frequency: Int, retried: Boolean = false) {
        val manager = manager ?: return
        val channel = channel ?: return

        val config = P2pConfig.group(ssid, pass, frequency)

        val where = if (frequency > 0) "$frequency MHz" else "auto"
        LabLog.add("P2P: joining group $ssid on $where")
        onStatus(LabStatus(Phase.CONNECTING, "joining group"))

        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = awaitGroup()
                override fun onFailure(reason: Int) {
                    // If the published channel is wrong, try again on any channel.
                    if (frequency > 0 && !retried) {
                        LabLog.add("P2P: $where failed (${reasonName(reason)}), trying auto")
                        connectGroup(ssid, pass, 0, retried = true)
                    } else {
                        fail("connect failed (${reasonName(reason)})")
                    }
                }
            },
        )
    }

    private fun connect(mac: String) {
        val manager = manager ?: return
        val channel = channel ?: return

        val config = WifiP2pConfig().apply {
            deviceAddress = mac
            wps.setup = WpsInfo.PBC
            // 0 = accept being a client; the group already exists on the other side.
            groupOwnerIntent = 0
        }

        LabLog.add("P2P: joining $mac's group")
        onStatus(LabStatus(Phase.CONNECTING, "joining group"))

        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = awaitGroup()
                override fun onFailure(reason: Int) = fail("connect failed (${reasonName(reason)})")
            },
        )
    }

    private fun awaitGroup() {
        val groupDeadline = System.currentTimeMillis() + CONNECT_MS

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val manager = manager ?: return
                val channel = channel ?: return

                manager.requestConnectionInfo(channel) { info ->
                    if (finished) return@requestConnectionInfo

                    if (info != null && info.groupFormed) {
                        stopListeningGroup()
                        val host = info.groupOwnerAddress?.hostAddress
                        if (host == null) {
                            fail("group without address")
                            return@requestConnectionInfo
                        }
                        LabLog.add("P2P: group formed, owner at $host")
                        onStatus(LabStatus(Phase.CONNECTED, "in the P2P group", host))
                        tcp.send(host)
                        return@requestConnectionInfo
                    }

                    if (System.currentTimeMillis() > groupDeadline) {
                        fail("group did not form within ${CONNECT_MS / 1000}s")
                        return@requestConnectionInfo
                    }
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }

        // The broadcast arrives as soon as the group forms; the 500 ms poll remains as a
        // safety net for when the framework does not deliver the notice.
        listenToGroupChanges {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun listenToGroupChanges(onChange: () -> Unit) {
        groupListener = onChange
        if (groupReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    groupListener?.invoke()
                }
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            groupReceiver = receiver
        }.onFailure {
            LabLog.add("P2P: broadcast unavailable (${it.javaClass.simpleName})")
        }
    }

    private fun stopListeningGroup() {
        groupListener = null
        groupReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        groupReceiver = null
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> "code $reason"
    }

    private fun fail(message: String) {
        stopListeningGroup()
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        beam.stop()
        stopListeningGroup()
        tcp.stop()

        val manager = manager
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.cancelConnect(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
        }
        channel = null

        // `removeGroup` may fail with BUSY and leave the group behind. The cleanup
        // repeats the removal and has its own timeout, so the next test starts clean.
        P2pCleanup.remove(activity)
        LabLog.add("P2P: stopped")
    }
}
