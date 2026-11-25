package dev.leandro.quicklydrop.core.storage

/**
 * Sanitizes names received from other devices and resolves collisions.
 *
 * The result never contains a path separator or a control character, and it fits in
 * [MAX_BYTES] UTF-8 bytes so it also fits the protocol name field.
 */
object DisplayName {

    const val MAX_BYTES = 200
    private const val FALLBACK = "file"
    private const val MAX_COLLISIONS = 999

    fun sanitize(raw: String): String {
        val cleaned = buildString {
            raw.trim().forEach { ch ->
                append(
                    when {
                        ch == '/' || ch == '\\' -> '_'
                        ch.code < 0x20 || ch.code == 0x7F -> '_'
                        isBidiControl(ch) -> '_'
                        else -> ch
                    },
                )
            }
        }.trim()

        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return FALLBACK
        return limit(cleaned)
    }

    /**
     * Returns [raw] sanitized, or the first free `(n)` variation.
     *
     * [exists] is called with each candidate; [MAX_COLLISIONS] bounds the work.
     */
    fun unique(raw: String, exists: (String) -> Boolean): String {
        val base = sanitize(raw)
        if (!exists(base)) return base

        val (stem, extension) = split(base)
        for (index in 1..MAX_COLLISIONS) {
            val candidate = limit("$stem ($index)$extension")
            if (!exists(candidate)) return candidate
        }
        throw IllegalStateException("too many name collisions for $base")
    }

    /** Bidi controls can visually reorder the extension in file managers. */
    private fun isBidiControl(ch: Char): Boolean =
        ch in '\u202A'..'\u202E' ||
            ch in '\u2066'..'\u2069' ||
            ch == '\u200E' ||
            ch == '\u200F'

    private fun limit(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return name

        val (stem, extension) = split(name)
        val extensionBytes = extension.toByteArray(Charsets.UTF_8).size
        if (extensionBytes >= MAX_BYTES) return truncate(name, MAX_BYTES)

        val truncated = truncate(stem, MAX_BYTES - extensionBytes)
        val safeStem = truncated.ifEmpty { FALLBACK }
        return safeStem + extension
    }

    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private fun truncate(text: String, maxBytes: Int): String {
        val result = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val bytes = chunk.toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            result.append(chunk)
            used += bytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
