package dev.leandro.quicklydrop.core.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.leandro.quicklydrop.core.transfer.FileSource
import dev.leandro.quicklydrop.protocol.FileMeta
import java.io.IOException
import java.io.InputStream

/**
 * Reads a file shared through a content URI.
 *
 * The display name and size come from [OpenableColumns]; providers that omit the size
 * fall back to the asset file length. A file with unknown size is rejected because the
 * protocol requires an exact size before Accept.
 */
class ContentUriFileSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : FileSource {

    override val meta: FileMeta = readMeta()

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

    private fun readMeta(): FileMeta {
        var name: String? = null
        var size = -1L

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        if (size < 0) {
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        if (size < 0) {
            throw IllegalArgumentException("unknown size for $uri")
        }

        val displayName = name ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return FileMeta(
            name = DisplayName.sanitize(displayName),
            size = size,
            mime = mime,
        )
    }
}
