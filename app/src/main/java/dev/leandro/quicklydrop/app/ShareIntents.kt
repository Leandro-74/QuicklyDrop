package dev.leandro.quicklydrop.app

import android.content.Intent
import android.net.Uri

/** Extracts content URIs from ACTION_SEND and ACTION_SEND_MULTIPLE. */
object ShareIntents {

    @Suppress("DEPRECATION")
    fun uris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        val extra = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri,
            )

            Intent.ACTION_SEND_MULTIPLE -> intent
                .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                .orEmpty()

            else -> emptyList()
        }

        val clip = intent.clipData?.let { data ->
            (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }
        }.orEmpty()

        return (extra + clip).distinct()
    }
}
