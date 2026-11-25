package dev.leandro.quicklydrop.core.service

import java.util.Locale

/** Human-readable sizes for notifications. */
object TransferFormat {

    fun bytes(value: Long): String {
        val safe = value.coerceAtLeast(0)
        if (safe < 1024) return "$safe B"

        val kb = safe / 1024.0
        if (kb < 1024) return format(kb, "KB")

        val mb = kb / 1024.0
        if (mb < 1024) return format(mb, "MB")

        return format(mb / 1024.0, "GB")
    }

    private fun format(value: Double, unit: String): String =
        String.format(Locale.US, "%.1f %s", value, unit)
}
