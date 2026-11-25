package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.FileMeta
import java.io.InputStream

/** A file offered by the sender. [open] must return a fresh stream for each call. */
interface FileSource {
    val meta: FileMeta
    fun open(): InputStream
}
