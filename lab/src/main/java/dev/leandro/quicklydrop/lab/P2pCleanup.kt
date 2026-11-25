package dev.leandro.quicklydrop.lab

import android.app.Activity
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Removes a P2P group from previous runs.
 *
 * The group survives the app's force-stop. If it is left behind, the other device's
 * `connect` may form a new group instead of joining the existing one, and the sender
 * ends up connecting to itself. Cleaning up before starting keeps the test repeatable.
 */
object P2pCleanup {

    private const val TIMEOUT_MS = 2_000L

    fun remove(activity: Activity, onDone: () -> Unit = {}) {
        val manager = activity.getSystemService(WifiP2pManager::class.java)
        val channel = manager?.initialize(activity, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            onDone()
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var done = false

        fun finish() {
            if (done) return
            done = true
            handler.post(onDone)
        }

        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = finish()
                override fun onFailure(reason: Int) = finish()
            },
        )

        // If the framework hangs, do not leave the test waiting forever.
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }
}
