package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta
import java.io.Closeable
import java.io.OutputStream

/**
 * Creates the destination of a received file.
 *
 * The receiver calls [Sink.commit] only after the SHA-256 matches. On any failure it
 * calls [Sink.abort] and then [Sink.close].
 */
interface FileSink {

    fun open(meta: FileMeta): Sink

    interface Sink : Closeable {
        val output: OutputStream
        fun commit()
        fun abort()
    }
}

/** A sink failure that can be reported to the peer with a protocol error. */
class FileSinkException(
    val failure: TransferFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
