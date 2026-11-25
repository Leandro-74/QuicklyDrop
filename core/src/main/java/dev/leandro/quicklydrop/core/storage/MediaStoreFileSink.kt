package dev.leandro.quicklydrop.core.storage

import android.net.Uri
import dev.leandro.quicklydrop.core.transfer.FileSink
import dev.leandro.quicklydrop.core.transfer.FileSinkException
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferLimits
import dev.leandro.quicklydrop.protocol.FileMeta
import java.io.OutputStream

/**
 * Writes received files to `Download/QuicklyDrop` through MediaStore.
 *
 * Names are sanitized and de-duplicated before insert. Files stay pending until the
 * hash is verified and [Sink.commit] is called.
 */
class MediaStoreFileSink(
    private val gateway: MediaStoreGateway,
    private val relativePath: String = DEFAULT_RELATIVE_PATH,
    private val maxFileSize: Long = TransferLimits.DEFAULT_MAX_FILE_SIZE,
) : FileSink {

    init {
        require(relativePath.endsWith("/")) { "relativePath must end with '/'" }
        require(maxFileSize > 0) { "maxFileSize must be positive" }
    }

    override fun open(meta: FileMeta): FileSink.Sink {
        if (meta.size > maxFileSize) {
            throw FileSinkException(
                TransferFailure.FILE_TOO_LARGE,
                "${meta.name} exceeds $maxFileSize bytes",
            )
        }

        val available = gateway.availableBytes()
        if (available >= 0 && available < meta.size) {
            throw FileSinkException(
                TransferFailure.NO_SPACE,
                "not enough space for ${meta.name}: need ${meta.size}, have $available",
            )
        }

        val name = try {
            DisplayName.unique(meta.name) { candidate -> gateway.exists(candidate, relativePath) }
        } catch (e: Exception) {
            throw FileSinkException(
                TransferFailure.WRITE_ERROR,
                e.message ?: "failed to choose a name for ${meta.name}",
                e,
            )
        }

        val mime = meta.mime.ifEmpty { "application/octet-stream" }
        val uri = try {
            gateway.insert(name, mime, relativePath)
        } catch (e: Exception) {
            throw FileSinkException(
                TransferFailure.WRITE_ERROR,
                e.message ?: "failed to insert $name",
                e,
            )
        }

        val output = try {
            gateway.openOutput(uri)
        } catch (e: Exception) {
            runCatching { gateway.delete(uri) }
            throw FileSinkException(
                TransferFailure.WRITE_ERROR,
                e.message ?: "failed to open $name",
                e,
            )
        }

        return Sink(uri, output)
    }

    private inner class Sink(
        private val uri: Uri,
        private val stream: OutputStream,
    ) : FileSink.Sink {

        private var committed = false
        private var aborted = false

        override val output: OutputStream get() = stream

        override fun commit() {
            check(!committed && !aborted) { "sink already closed" }
            try {
                stream.flush()
                stream.close()
                gateway.publish(uri)
                committed = true
            } catch (e: Exception) {
                runCatching { gateway.delete(uri) }
                aborted = true
                throw FileSinkException(
                    TransferFailure.WRITE_ERROR,
                    e.message ?: "failed to publish $uri",
                    e,
                )
            }
        }

        override fun abort() {
            if (committed || aborted) return
            aborted = true
            runCatching { stream.close() }
            runCatching { gateway.delete(uri) }
        }

        override fun close() {
            if (committed) {
                runCatching { stream.close() }
            } else {
                abort()
            }
        }
    }

    companion object {
        /** Matches [android.os.Environment.DIRECTORY_DOWNLOADS] without touching Android. */
        const val DEFAULT_RELATIVE_PATH: String = "Download/QuicklyDrop/"
    }
}
