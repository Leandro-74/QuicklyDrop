package dev.leandro.quicklydrop.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.leandro.quicklydrop.R
import dev.leandro.quicklydrop.core.p2p.AndroidP2pTransport
import dev.leandro.quicklydrop.core.service.ReceiveService
import dev.leandro.quicklydrop.core.service.TransferStatus
import dev.leandro.quicklydrop.core.service.TransferStatusState
import dev.leandro.quicklydrop.core.session.SendSession
import dev.leandro.quicklydrop.core.storage.ContentUriFileSource
import dev.leandro.quicklydrop.core.transfer.FileSource
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferListener
import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.DiscoveryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    val receiveState: StateFlow<TransferStatusState> = TransferStatus.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var sources: List<FileSource> = emptyList()

    @Volatile
    private var session: SendSession? = null

    @Volatile
    private var cancelRequested = false

    private var sendGeneration = 0

    fun prepareSend(uris: List<Uri>) {
        if (uris.isEmpty()) {
            _sendState.value = SendState.Error(app.getString(R.string.no_files))
            return
        }
        if (_sendState.value is SendState.Sending || _sendState.value is SendState.Loading) {
            showMessage(app.getString(R.string.working))
            return
        }
        if (isReceiving()) {
            showMessage("Stop receiving before sending a file.")
            return
        }

        _sendState.value = SendState.Loading
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.distinct().mapNotNull { uri ->
                    runCatching { ContentUriFileSource(app.contentResolver, uri) }.getOrNull()
                }
            }
            if (loaded.isEmpty()) {
                _sendState.value = SendState.Error(app.getString(R.string.file_read_failed))
                return@launch
            }
            sources = loaded
            _sendState.value = SendState.Ready(loaded.map { it.meta })
        }
    }

    fun onScanStarted() {
        updateReady { copy(scanning = true) }
    }

    fun onScanStopped() {
        updateReady { copy(scanning = false) }
    }

    fun onScanError(message: String) {
        _sendState.value = SendState.Error(message)
    }

    fun send(payload: DiscoveryPayload) {
        val current = sources
        if (current.isEmpty()) {
            _sendState.value = SendState.Error(app.getString(R.string.no_files))
            return
        }
        if (_sendState.value is SendState.Sending) return

        cancelRequested = false
        val generation = ++sendGeneration
        val metas = current.map { it.meta }
        _sendState.value = SendState.Sending(metas)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (cancelRequested) {
                        return@withContext TransferResult.Failure(
                            TransferFailure.CANCELLED,
                            "cancelled",
                        )
                    }
                    val p2p = AndroidP2pTransport(app)
                    val active = SendSession(
                        p2p = p2p,
                        files = current,
                        listener = object : TransferListener {
                            override fun onProgress(progress: TransferProgress) {
                                _sendState.value = SendState.Sending(metas, progress)
                            }
                        },
                    )
                    session = active
                    if (cancelRequested) active.cancel()
                    active.run(payload)
                } catch (e: Exception) {
                    TransferResult.Failure(
                        TransferFailure.INTERNAL,
                        e.message ?: "send failed",
                        e,
                    )
                }
            }
            session = null
            if (generation == sendGeneration) {
                _sendState.value = SendState.Finished(result)
            }
        }
    }

    fun cancelSend() {
        cancelRequested = true
        session?.cancel()
    }

    fun retrySend() {
        _sendState.value = if (sources.isNotEmpty()) {
            SendState.Ready(sources.map { it.meta })
        } else {
            SendState.Idle
        }
    }

    fun dismissSend() {
        cancelRequested = true
        session?.cancel()
        session = null
        sources = emptyList()
        sendGeneration++
        _sendState.value = SendState.Idle
    }

    fun startReceive(context: Context) {
        if (_sendState.value is SendState.Ready || _sendState.value is SendState.Sending) {
            showMessage("Finish sending before receiving a file.")
            return
        }
        TransferStatus.reset()
        runCatching { ReceiveService.start(context) }
            .onFailure { showMessage(it.message ?: "cannot start receiver") }
    }

    fun stopReceive(context: Context) {
        runCatching { ReceiveService.stop(context) }
            .onFailure { showMessage(it.message ?: "cannot stop receiver") }
        TransferStatus.reset()
    }

    fun resetReceive() {
        TransferStatus.reset()
    }

    fun showMessage(message: String) {
        _message.value = message
    }

    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        cancelRequested = true
        session?.cancel()
        session = null
        super.onCleared()
    }

    private fun isReceiving(): Boolean = when (TransferStatus.state.value) {
        is TransferStatusState.Armed, is TransferStatusState.Active -> true
        else -> false
    }

    private inline fun updateReady(block: SendState.Ready.() -> SendState.Ready) {
        val current = _sendState.value
        if (current is SendState.Ready) {
            _sendState.value = current.block()
        }
    }
}
