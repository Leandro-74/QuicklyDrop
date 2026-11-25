package dev.leandro.quicklydrop.protocol

import com.southernstorm.noise.protocol.HandshakeState
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Noise NK handshake.
 *
 * The sender is the initiator and uses the public key received over NFC. The receiver
 * is the responder and generates an ephemeral static key for each receive window.
 */
class NoiseHandshake private constructor(
    private val state: HandshakeState,
    val role: Role,
) {

    enum class Role { INITIATOR, RESPONDER }

    companion object {
        const val PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_SHA256"
        const val PROLOGUE = "quicklydrop/1"

        private const val MAX_MESSAGE = 1024
        private val DEFAULT_PROLOGUE = PROLOGUE.toByteArray(Charsets.US_ASCII)

        fun initiator(
            responderPublicKey: ByteArray,
            prologue: ByteArray = DEFAULT_PROLOGUE,
        ): NoiseHandshake {
            if (responderPublicKey.size != 32) {
                throw ProtocolException("responder public key must be 32 bytes")
            }
            val state = newState(HandshakeState.INITIATOR)
            val remote = state.remotePublicKey
                ?: throw ProtocolException("NK pattern without a remote key")
            remote.setPublicKey(responderPublicKey, 0)
            state.setPrologue(prologue, 0, prologue.size)
            state.start()
            return NoiseHandshake(state, Role.INITIATOR)
        }

        fun responder(prologue: ByteArray = DEFAULT_PROLOGUE): NoiseHandshake {
            val state = newState(HandshakeState.RESPONDER)
            val local = state.localKeyPair
                ?: throw ProtocolException("NK pattern without a local key")
            local.generateKeyPair()
            state.setPrologue(prologue, 0, prologue.size)
            state.start()
            return NoiseHandshake(state, Role.RESPONDER)
        }

        private fun newState(role: Int): HandshakeState =
            try {
                HandshakeState(PROTOCOL_NAME, role)
            } catch (e: NoSuchAlgorithmException) {
                throw ProtocolException("Noise unavailable", e)
            }
    }

    /** Static public key of the session. Only exists on the responder. */
    fun localPublicKey(): ByteArray {
        val local = state.localKeyPair
            ?: throw ProtocolException("role without a static key")
        val key = ByteArray(local.publicKeyLength)
        local.getPublicKey(key, 0)
        return key
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(MAX_MESSAGE)
        val length = try {
            state.writeMessage(out, 0, payload, 0, payload.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("Noise message too large", e)
        }
        return out.copyOf(length)
    }

    fun readMessage(message: ByteArray): ByteArray {
        val out = ByteArray(MAX_MESSAGE)
        val length = try {
            state.readMessage(message, 0, message.size, out, 0)
        } catch (e: ShortBufferException) {
            throw ProtocolException("Noise message too large", e)
        } catch (e: BadPaddingException) {
            throw ProtocolException("invalid Noise handshake", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("invalid Noise message", e)
        }
        return out.copyOf(length)
    }

    fun split(): NoiseTransport {
        val pair = try {
            state.split()
        } catch (e: IllegalStateException) {
            throw ProtocolException("incomplete handshake", e)
        }
        return NoiseTransport(pair.sender, pair.receiver)
    }

    fun destroy() {
        state.destroy()
    }
}
