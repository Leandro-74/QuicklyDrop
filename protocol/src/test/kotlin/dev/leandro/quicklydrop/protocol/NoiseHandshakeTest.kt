package dev.leandro.quicklydrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseHandshakeTest {

    private data class Session(
        val initiator: NoiseHandshake,
        val responder: NoiseHandshake,
        val initiatorTransport: NoiseTransport,
        val responderTransport: NoiseTransport,
    )

    private fun complete(
        prologue: ByteArray = NoiseHandshake.PROLOGUE.toByteArray(Charsets.US_ASCII),
    ): Session {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey(), prologue)
        val m1 = initiator.writeMessage()
        responder.readMessage(m1)
        val m2 = responder.writeMessage()
        initiator.readMessage(m2)
        return Session(initiator, responder, initiator.split(), responder.split())
    }

    @Test
    fun `handshake completes and exchanges messages in both directions`() {
        val session = complete()
        val doEmissor = "sender".toByteArray()
        val doReceptor = "receiver".toByteArray()

        assertArrayEquals(
            doEmissor,
            session.responderTransport.decrypt(session.initiatorTransport.encrypt(doEmissor)),
        )
        assertArrayEquals(
            doReceptor,
            session.initiatorTransport.decrypt(session.responderTransport.encrypt(doReceptor)),
        )
    }

    @Test
    fun `fails with the wrong public key`() {
        val real = NoiseHandshake.responder()
        val outro = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(real.localPublicKey())
        val m1 = initiator.writeMessage()

        assertThrows(ProtocolException::class.java) {
            outro.readMessage(m1)
        }
    }

    @Test
    fun `fails with a tampered message`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val m1 = initiator.writeMessage()
        m1[0] = (m1[0].toInt() xor 1).toByte()

        assertThrows(ProtocolException::class.java) {
            responder.readMessage(m1)
        }
    }

    @Test
    fun `rejects replay of the same ciphertext`() {
        val session = complete()
        val ciphertext = session.initiatorTransport.encrypt("x".toByteArray())

        assertArrayEquals("x".toByteArray(), session.responderTransport.decrypt(ciphertext))
        assertThrows(ProtocolException::class.java) {
            session.responderTransport.decrypt(ciphertext)
        }
    }

    @Test
    fun `a different prologue fails`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(
            responder.localPublicKey(),
            "quicklydrop/2".toByteArray(Charsets.US_ASCII),
        )
        val m1 = initiator.writeMessage()

        assertThrows(ProtocolException::class.java) {
            responder.readMessage(m1)
        }
    }

    @Test
    fun `handshake messages have 48 bytes`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val m1 = initiator.writeMessage()
        responder.readMessage(m1)
        val m2 = responder.writeMessage()

        assertEquals(48, m1.size)
        assertEquals(48, m2.size)
    }

    @Test
    fun `responder public key has 32 bytes`() {
        assertEquals(32, NoiseHandshake.responder().localPublicKey().size)
    }

    @Test
    fun `initiator has no static key`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())

        assertThrows(ProtocolException::class.java) {
            initiator.localPublicKey()
        }
    }

    @Test
    fun `rejects a public key of the wrong size`() {
        assertThrows(ProtocolException::class.java) {
            NoiseHandshake.initiator(ByteArray(31))
        }
    }

    @Test
    fun `transport rejects short ciphertext`() {
        val session = complete()

        assertThrows(ProtocolException::class.java) {
            session.responderTransport.decrypt(ByteArray(4))
        }
    }
}
