package dev.leandro.quicklydrop.core.storage

import android.net.Uri
import java.io.OutputStream

/** Thin boundary around MediaStore, replaceable in unit tests. */
interface MediaStoreGateway {

    fun exists(displayName: String, relativePath: String): Boolean

    fun insert(displayName: String, mimeType: String, relativePath: String): Uri

    fun openOutput(uri: Uri): OutputStream

    fun publish(uri: Uri)

    fun delete(uri: Uri)

    /** Available bytes, or -1 when the platform cannot tell. */
    fun availableBytes(): Long
}
