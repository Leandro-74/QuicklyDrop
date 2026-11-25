package dev.leandro.quicklydrop.lab.spike

import android.app.KeyguardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/** Estado observável do processo e do aparelho no momento do evento. */
object SpikePresence {

    @Volatile
    var activityVisible: Boolean = false

    @Volatile
    var fgsRunning: Boolean = false

    fun processAgeMs(): Long = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

    fun snapshot(context: Context): SpikeSnapshot {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val nfc = NfcAdapter.getDefaultAdapter(context)
        val secureNfc = runCatching { nfc?.isSecureNfcEnabled }.getOrNull()

        return SpikeSnapshot(
            pid = Process.myPid(),
            processAgeMs = processAgeMs(),
            activityVisible = activityVisible,
            fgsRunning = fgsRunning,
            interactive = power?.isInteractive ?: false,
            keyguardLocked = keyguard?.isKeyguardLocked ?: false,
            secureNfc = secureNfc,
        )
    }

    fun info(context: Context): String = SpikeState.describe(snapshot(context))
}
