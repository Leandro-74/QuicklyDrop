package dev.leandro.quicklydrop.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.leandro.quicklydrop.R
import dev.leandro.quicklydrop.core.nfc.NfcReader
import dev.leandro.quicklydrop.core.p2p.P2pPermissions

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var nfcReader: NfcReader? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (hasWifiPermission()) {
            action?.invoke()
        } else if (action != null) {
            viewModel.showMessage(getString(R.string.permission_needed))
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.prepareSend(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            QuicklyDropTheme {
                QuicklyDropApp(
                    viewModel = viewModel,
                    onReceive = ::startReceive,
                    onStopReceive = ::stopReceive,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onStartScan = ::startScan,
                    onCancelSend = viewModel::cancelSend,
                    onDismissSend = viewModel::dismissSend,
                    onRetrySend = viewModel::retrySend,
                    onResetReceive = viewModel::resetReceive,
                )
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        stopNfcReader()
        super.onPause()
    }

    override fun onDestroy() {
        nfcReader?.stop()
        nfcReader = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        stopNfcReader()
        val uris = ShareIntents.uris(intent)
        if (uris.isEmpty()) {
            viewModel.showMessage(getString(R.string.no_files))
        } else {
            viewModel.prepareSend(uris)
        }
    }

    private fun startReceive() {
        withWifiPermission {
            val problem = NfcReader(this).problem()
            if (problem != null) {
                viewModel.showMessage(problem)
            } else {
                viewModel.startReceive(this)
            }
        }
    }

    private fun stopReceive() {
        viewModel.stopReceive(this)
    }

    private fun startScan() {
        withWifiPermission {
            stopNfcReader()

            val reader = NfcReader(this)
            val problem = reader.problem()
            if (problem != null) {
                viewModel.showMessage(problem)
                return@withWifiPermission
            }

            nfcReader = reader
            viewModel.onScanStarted()
            reader.start(
                onPayload = { payload ->
                    nfcReader = null
                    viewModel.send(payload)
                },
                onError = { message ->
                    nfcReader = null
                    viewModel.onScanError(message)
                },
            )
        }
    }

    private fun stopNfcReader() {
        nfcReader?.stop()
        nfcReader = null
        viewModel.onScanStopped()
    }

    private fun withWifiPermission(action: () -> Unit) {
        if (hasWifiPermission()) {
            action()
            return
        }

        pendingPermissionAction = action
        permissionLauncher.launch(
            P2pPermissions.required(Build.VERSION.SDK_INT).toTypedArray(),
        )
    }

    private fun hasWifiPermission(): Boolean =
        checkSelfPermission(wifiPermission()) == PackageManager.PERMISSION_GRANTED

    private fun wifiPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun requestMissingPermissions() {
        val missing = P2pPermissions.required(Build.VERSION.SDK_INT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
