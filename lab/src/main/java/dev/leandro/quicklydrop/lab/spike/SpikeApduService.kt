package dev.leandro.quicklydrop.lab.spike

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Base of the spike HCE cards.
 *
 * The card only responds to SELECT and GET DATA. GET DATA publishes the persisted payload
 * and triggers the measured actions: foreground service and P2P group creation.
 */
abstract class SpikeApduBase : HostApduService() {

    companion object {
        /** AID F0 51 44 52 4F 51, permissive regarding the screen. */
        val AID_PERMISSIVE: ByteArray =
            byteArrayOf(0xF0.toByte(), 0x51, 0x44, 0x52, 0x4F, 0x51)

        /** AID F0 51 44 52 4F 52, requires the screen on. */
        val AID_SCREEN_ON: ByteArray =
            byteArrayOf(0xF0.toByte(), 0x51, 0x44, 0x52, 0x4F, 0x52)

        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)
    }

    protected abstract val label: String

    override fun onCreate() {
        super.onCreate()
        SpikeLog.event(this, "hce.onCreate", "$label ${SpikePresence.info(this)}")
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 2) return UNKNOWN

        val ins = commandApdu[1].toInt() and 0xFF
        SpikeLog.event(
            this,
            "hce.apdu",
            "$label ins=0x%02X ${SpikePresence.info(this)}".format(ins),
        )

        return when {
            commandApdu[0] == 0x00.toByte() && ins == 0xA4 -> {
                SpikeLog.event(this, "hce.select", label)
                OK
            }

            commandApdu[0] == 0x80.toByte() && ins == 0xCA -> onRead()

            else -> {
                SpikeLog.event(this, "hce.unknown", "$label ins=0x%02X".format(ins))
                UNKNOWN
            }
        }
    }

    private fun onRead(): ByteArray {
        val config = SpikeStore.config(this)
        val error = SpikePayload.validate(config.payload)
        if (error != null) {
            SpikeLog.event(this, "hce.payload", "$label $error")
            return SpikePayload.notFound
        }

        val size = config.payload!!.toByteArray(Charsets.UTF_8).size
        SpikeLog.event(this, "hce.tap", "$label payload=${size}B ${SpikePresence.info(this)}")

        if (config.fgsOnTap) SpikeActions.startForeground(this, "hce")
        if (config.groupOnTap) SpikeActions.createGroup(this, "hce")

        return SpikePayload.response(config.payload)
    }

    override fun onDeactivated(reason: Int) {
        SpikeLog.event(this, "hce.deactivated", "$label reason=$reason")
    }
}

/** Permissive card: responds with the screen off. */
class SpikeApduService : SpikeApduBase() {
    override val label: String = "permissive"
}

/** Strict card: only responds with the screen on. */
class SpikeScreenOnApduService : SpikeApduBase() {
    override val label: String = "screenOn"
}
