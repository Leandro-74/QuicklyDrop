package dev.leandro.quicklydrop.lab

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects the UI to the test modes. Each `start` creates fresh instances, so no dirty
 * state from a previous test leaks into the next one — in the lab, repeating the same
 * clean test is worth more than reusing an object.
 */
class LabController(private val activity: Activity) {

    private val context: Context = activity

    private val _status = MutableStateFlow(LabStatus())
    val status: StateFlow<LabStatus> = _status.asStateFlow()

    private var lan: LanMode? = null
    private var ble: BleMode? = null
    private var nfc: NfcMode? = null
    private var direct: WifiDirectMode? = null

    fun start(mode: Mode, role: Role) {
        stop(quiet = true)
        LabLog.add("--- ${mode.label} / ${role.label} ---")
        _status.value = LabStatus(
            phase = Phase.SEARCHING,
            detail = "starting ${mode.label}",
            mode = mode,
            role = role,
        )

        when (mode) {
            Mode.LAN -> LanMode(context) { report(it) }.also {
                lan = it
                if (role == Role.RECEIVER) it.receiver() else it.sender()
            }
            Mode.BLE -> BleMode(context, role) { report(it) }.also {
                ble = it
                it.start()
            }
            Mode.NFC -> NfcMode(activity, role) { report(it) }.also {
                nfc = it
                it.start()
            }
            Mode.P2P -> WifiDirectMode(activity, role) { report(it) }.also {
                direct = it
                it.start()
            }
            else -> fail("${mode.label}: not implemented yet")
        }
    }

    fun stop(quiet: Boolean = false) {
        lan?.stop()
        lan = null
        ble?.stop()
        ble = null
        nfc?.stop()
        nfc = null
        val hadDirect = direct != null
        direct?.stop()
        direct = null
        // If the app was restarted, the P2P mode is no longer in memory, but the group
        // may have been left in the framework. In that case, clean it up when stopping.
        if (!quiet && !hadDirect) P2pCleanup.remove(activity)
        _status.value = LabStatus(
            mode = _status.value.mode,
            role = _status.value.role,
        )
        if (!quiet) LabLog.add("stopped")
    }

    fun selectMode(mode: Mode) {
        _status.value = _status.value.choose(mode = mode)
    }

    fun selectRole(role: Role) {
        _status.value = _status.value.choose(role = role)
    }

    /** Modes report only phase/detail; mode and role belong to the controller. */
    private fun report(status: LabStatus) {
        _status.value = status.copy(
            mode = _status.value.mode,
            role = _status.value.role,
        )
    }

    private fun fail(message: String) {
        LabLog.add(message)
        _status.value = _status.value.copy(phase = Phase.ERROR, detail = message)
    }
}
