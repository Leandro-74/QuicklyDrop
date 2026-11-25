package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta

/** Callbacks are invoked on the transfer thread. The UI must marshal them itself. */
interface TransferListener {

    fun onOffer(files: List<FileMeta>) {}

    fun onFileStarted(index: Int, file: FileMeta) {}

    fun onProgress(progress: TransferProgress) {}

    fun onFileCompleted(index: Int, file: FileMeta) {}

    companion object {
        val NONE: TransferListener = object : TransferListener {}
    }
}
