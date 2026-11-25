package dev.leandro.quicklydrop.lab

import android.app.Activity

/**
 * Tap-based identification: the receiver presents its hotspot credentials; the sender
 * reads them and joins the network to push the data.
 *
 * Unlike BLE, here the distance is zero by construction — Android only delivers the tag
 * when both devices are touching.
 */
class NfcMode(
    activity: Activity,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    private val beam = NfcBeam(activity)
    private val link = HotspotLink(activity, onStatus)

    fun start() {
        beam.problem()?.let {
            fail(it)
            return
        }
        if (role == Role.RECEIVER) startReceiver() else startSender()
    }

    // ------------------------------------------------------------- receiver

    private fun startReceiver() {
        link.host { ssid, pass ->
            beam.present("$ssid\n$pass") {
                onStatus(LabStatus(Phase.CONNECTING, "tap detected, connecting"))
            }
            LabLog.add("NFC: credentials for $ssid published")
            onStatus(LabStatus(Phase.SEARCHING, "touch the other device"))
        }
    }

    // ------------------------------------------------------------- sender

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "touch the receiver"))

        beam.read(
            onPayload = { payload ->
                val parts = payload.split("\n")
                if (parts.size < 2) {
                    fail("NFC: invalid payload")
                    return@read
                }
                onStatus(LabStatus(Phase.FOUND, "tap detected"))
                link.join(parts[0], parts[1])
            },
            onError = { fail(it) },
        )
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        beam.stop()
        link.stop()
        LabLog.add("NFC: stopped")
    }
}
