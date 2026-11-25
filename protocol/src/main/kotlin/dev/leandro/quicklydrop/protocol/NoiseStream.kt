package dev.leandro.quicklydrop.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Length-prefixed Noise handshake messages over a stream.
 *
 * NK has exactly two messages. Each one uses a `u32` big-endian length prefix, like a
 * secure frame, but with a smaller maximum.
 */
object NoiseStream {

    const val MAX_MESSAGE = 1024

    fun write(
        handshake: NoiseHandshake,
        output: OutputStream,
        payload: ByteArray = ByteArray(0),
    ) {
        val message = handshake.writeMessage(payload)
        if (message.size > MAX_MESSAGE) {
            throw ProtocolException("handshake message of ${message.size} bytes exceeds the limit")
        }
        output.write(ByteBuffer.allocate(4).putInt(message.size).array())
        output.write(message)
        output.flush()
    }

    fun read(handshake: NoiseHandshake, input: InputStream): ByteArray {
        val header = StreamIO.readExactly(input, 4, allowEof = true)
            ?: throw ProtocolException("stream ended before the handshake")
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..MAX_MESSAGE) {
            throw ProtocolException("invalid handshake length: $length")
        }
        val message = StreamIO.readExactly(input, length, allowEof = false)!!
        return handshake.readMessage(message)
    }
}
