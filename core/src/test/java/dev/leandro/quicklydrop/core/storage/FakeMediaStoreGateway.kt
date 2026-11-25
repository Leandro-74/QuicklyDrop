package dev.leandro.quicklydrop.core.storage

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

internal class FakeMediaStoreGateway(
    var available: Long = Long.MAX_VALUE,
) : MediaStoreGateway {

    val existing = mutableSetOf<String>()
    val names = mutableMapOf<Uri, String>()
    val outputs = mutableMapOf<Uri, ByteArrayOutputStream>()
    val published = mutableSetOf<Uri>()
    val deleted = mutableSetOf<Uri>()
    var failInsert = false

    override fun exists(displayName: String, relativePath: String): Boolean =
        displayName in existing

    override fun insert(displayName: String, mimeType: String, relativePath: String): Uri {
        if (failInsert) throw IOException("insert failed")
        val uri = Uri.parse("content://test/${outputs.size + 1}")
        names[uri] = displayName
        outputs[uri] = ByteArrayOutputStream()
        return uri
    }

    override fun openOutput(uri: Uri): OutputStream =
        outputs[uri] ?: throw IOException("missing output")

    override fun publish(uri: Uri) {
        published += uri
    }

    override fun delete(uri: Uri) {
        deleted += uri
        outputs.remove(uri)
    }

    override fun availableBytes(): Long = available
}
