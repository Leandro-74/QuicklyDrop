package dev.leandro.quicklydrop.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Encrypted frame channel over a pair of streams.
 *
 * Each frame is sent as `u32 ciphertextLength` followed by the Noise ciphertext. The
 * length prefix is not encrypted; the content and the frame type are.
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: NoiseTransport,
) : FrameChannel {

    companion object {
        const val MAX_CIPHERTEXT = 65535
    }

    override fun send(frame: Frame) {
        val plaintext = FrameCodec.encode(frame)
        val ciphertext = transport.encrypt(plaintext)
        if (ciphertext.size > MAX_CIPHERTEXT) {
            throw ProtocolException("ciphertext of ${ciphertext.size} bytes exceeds the limit")
        }
        output.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array())
        output.write(ciphertext)
        output.flush()
    }

    override fun receive(): Frame? {
        val header = StreamIO.readExactly(input, 4, allowEof = true) ?: return null
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..MAX_CIPHERTEXT) {
            throw ProtocolException("invalid ciphertext length: $length")
        }
        val ciphertext = StreamIO.readExactly(input, length, allowEof = false)!!
        return FrameCodec.decode(transport.decrypt(ciphertext))
    }
}
