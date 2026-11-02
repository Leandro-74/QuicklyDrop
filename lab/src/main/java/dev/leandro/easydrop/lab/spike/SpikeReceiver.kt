package dev.leandro.easydrop.lab.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter

/**
 * Recebe o aviso de transação NFC.
 *
 * É a exceção documentada para iniciar um serviço em primeiro plano a partir do
 * background. O spike mede se o broadcast chega quando o app está fechado, se o processo
 * é o mesmo do HCE e se o `startForegroundService` é aceito.
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
