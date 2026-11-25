package dev.leandro.quicklydrop.protocol

import java.io.InputStream

/** Stream helpers shared by the handshake and the secure frame channel. */
internal object StreamIO {

    /**
     * Reads exactly [length] bytes.
     *
     * Returns null only when [allowEof] is true and the stream ends before the first byte.
     * Any partial read is a protocol error.
     */
    fun readExactly(input: InputStream, length: Int, allowEof: Boolean): ByteArray? {
        require(length >= 0) { "negative length: $length" }

        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) {
                if (read == 0 && allowEof) return null
                throw ProtocolException("stream ended in the middle of a frame")
            }
            read += count
        }
        return bytes
    }
}
