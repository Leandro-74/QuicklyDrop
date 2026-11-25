package dev.leandro.quicklydrop.lab.spike

import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.leandro.quicklydrop.lab.P2pConfig

/**
 * Actions triggered by the NFC tap.
 *
 * Each attempt is recorded with the result or the exception. The goal is to separate
 * "Android called the HCE" from "the app managed to start a foreground service" and
 * from "the app managed to create the P2P group", which are different permissions.
 */
object SpikeActions {

    private const val GROUP_SSID = "DIRECT-ED-SPIKE"
    private const val GROUP_PASS = "spike123456"
    private const val GROUP_FREQUENCY = 5745

    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null

    fun startForeground(context: Context, source: String) {
        if (SpikePresence.fgsRunning) {
            SpikeLog.event(context, "$source.fgs", "already active")
            return
        }

        SpikeLog.event(context, "$source.fgs", "trying startForegroundService")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SpikeForegroundService::class.java).putExtra("source", source),
            )
            SpikeLog.event(context, "$source.fgs", "startForegroundService accepted")
        } catch (e: Exception) {
            SpikeLog.event(
                context,
                "$source.fgs",
                "${SpikeFailure.foregroundService(e.javaClass.name)}: ${e.message}",
            )
        }
    }

    fun createGroup(context: Context, source: String) {
        val manager = context.getSystemService(WifiP2pManager::class.java)
        if (manager == null) {
            SpikeLog.event(context, "$source.p2p", "Wi-Fi Direct unavailable")
            return
        }

        val existing = channel
        val local = existing ?: manager.initialize(context, Looper.getMainLooper(), null)
        if (local == null) {
            SpikeLog.event(context, "$source.p2p", "channel unavailable")
            return
        }
        channel = local

        SpikeLog.event(context, "$source.p2p", "trying createGroup on $GROUP_FREQUENCY MHz")
        val started = SystemClock.uptimeMillis()

        // An old group survives the force-stop; removing it first keeps the test repeatable.
        try {
            manager.removeGroup(
                local,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = create(manager, local, context, source, started)
                    override fun onFailure(reason: Int) = create(manager, local, context, source, started)
                },
            )
        } catch (e: SecurityException) {
            SpikeLog.event(context, "$source.p2p", "BLOCKED (permission): ${e.message}")
        }
    }

    private fun create(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        context: Context,
        source: String,
        started: Long,
    ) {
        handler.postDelayed(
            {
                try {
                    manager.createGroup(
                        channel,
                        P2pConfig.group(GROUP_SSID, GROUP_PASS, GROUP_FREQUENCY),
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                val elapsed = SystemClock.uptimeMillis() - started
                                SpikeLog.event(context, "$source.p2p", "group created in ${elapsed}ms")
                            }

                            override fun onFailure(reason: Int) {
                                SpikeLog.event(
                                    context,
                                    "$source.p2p",
                                    "failed: ${SpikeFailure.p2p(reason)}",
                                )
                            }
                        },
                    )
                } catch (e: SecurityException) {
                    SpikeLog.event(context, "$source.p2p", "BLOCKED (permission): ${e.message}")
                }
            },
            300,
        )
    }
}
