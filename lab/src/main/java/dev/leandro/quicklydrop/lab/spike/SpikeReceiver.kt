package dev.leandro.quicklydrop.lab.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter

/**
 * Receives the NFC transaction notice.
 *
 * This is the documented exception for starting a foreground service from the
 * background. The spike measures whether the broadcast arrives when the app is closed,
 * whether the process is the same as the HCE one, and whether `startForegroundService` is
 * accepted.
 */
class SpikeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NfcAdapter.ACTION_TRANSACTION_DETECTED) return

        val aid = intent.getByteArrayExtra(NfcAdapter.EXTRA_AID)?.toHex() ?: "-"
        SpikeLog.event(
            context,
            "receiver.transaction",
            "aid=$aid ${SpikePresence.info(context)}",
        )

        if (SpikeStore.config(context).fgsOnTap) {
            SpikeActions.startForeground(context, "receiver")
        }
    }
}
