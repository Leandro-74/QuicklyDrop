package dev.leandro.quicklydrop.protocol

import com.southernstorm.noise.protocol.CipherState
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Encrypted channel after the handshake.
 *
 * Each direction has its own [CipherState] and nonce. The nonce is never reused:
 * the library increments it for every message.
 */
class NoiseTransport internal constructor(
    private val sender: CipherState,
    private val receiver: CipherState,
) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + sender.macLength)
        val length = try {
            sender.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("insufficient cipher buffer", e)
        }
        if (length != out.size) throw ProtocolException("unexpected ciphertext length: $length")
        return out
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val mac = receiver.macLength
        if (ciphertext.size < mac) throw ProtocolException("short ciphertext")
        val out = ByteArray(ciphertext.size - mac)
        val length = try {
            receiver.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("insufficient decryption buffer", e)
        } catch (e: BadPaddingException) {
            throw ProtocolException("invalid ciphertext", e)
        }
        if (length != out.size) throw ProtocolException("unexpected plaintext length: $length")
        return out
    }

    fun destroy() {
        sender.destroy()
        receiver.destroy()
    }
}
