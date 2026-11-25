package dev.leandro.quicklydrop.core.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream

/** MediaStore implementation used by the product receiver. */
class AndroidMediaStoreGateway(
    private val resolver: ContentResolver,
    private val freeSpace: () -> Long = ::defaultFreeSpace,
) : MediaStoreGateway {

    override fun exists(displayName: String, relativePath: String): Boolean {
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor -> cursor.count > 0 } ?: false
    }

    override fun insert(displayName: String, mimeType: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(
                MediaStore.MediaColumns.DATE_EXPIRES,
                System.currentTimeMillis() + PENDING_EXPIRATION_MS,
            )
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore did not create $displayName")
    }

    override fun openOutput(uri: Uri): OutputStream =
        resolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")

    override fun publish(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        if (resolver.update(uri, values, null, null) <= 0) {
            throw IOException("cannot publish $uri")
        }
    }

    override fun delete(uri: Uri) {
        if (resolver.delete(uri, null, null) <= 0) {
            Log.w(TAG, "MediaStore delete failed for $uri")
        }
    }

    override fun availableBytes(): Long = freeSpace()

    private companion object {
        const val TAG = "QuicklyDropMediaStore"
        const val PENDING_EXPIRATION_MS = 24 * 60 * 60 * 1000L

        fun defaultFreeSpace(): Long =
            runCatching {
                // shortcut: assumes the primary external volume; MediaStore may use another volume.
                StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
            }.getOrDefault(-1)
    }
}
