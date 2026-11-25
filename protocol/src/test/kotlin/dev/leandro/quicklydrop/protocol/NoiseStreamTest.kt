package dev.leandro.quicklydrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NoiseStreamTest {

    @Test
    fun `handshake over streams opens working secure channels`() {
        val server = ServerSocket(0)
        val responder = NoiseHandshake.responder()
        val responderSocket = AtomicReference<Socket?>()
        val responderError = AtomicReference<Throwable?>()
        val responderDone = CountDownLatch(1)

        thread(name = "responder") {
            try {
                val socket = server.accept()
                responderSocket.set(socket)
                NoiseStream.read(responder, socket.getInputStream())
                NoiseStream.write(responder, socket.getOutputStream())
            } catch (t: Throwable) {
                responderError.set(t)
            } finally {
                responderDone.countDown()
            }
        }

        val initiatorSocket = Socket("127.0.0.1", server.localPort)
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        NoiseStream.write(initiator, initiatorSocket.getOutputStream())
        NoiseStream.read(initiator, initiatorSocket.getInputStream())

        assertTrue("responder did not finish", responderDone.await(5, TimeUnit.SECONDS))
        assertNull("responder failed: ${responderError.get()}", responderError.get())

        val initiatorChannel = SecureChannel(
            input = initiatorSocket.getInputStream(),
            output = initiatorSocket.getOutputStream(),
            transport = initiator.split(),
        )
        val peer = responderSocket.get()!!
        val responderChannel = SecureChannel(
            input = peer.getInputStream(),
            output = peer.getOutputStream(),
            transport = responder.split(),
        )

        val offer = Frame.Offer(listOf(FileMeta("a.txt", 3, "text/plain")))
        initiatorChannel.send(offer)
        assertEquals(offer, responderChannel.receive())

        responderChannel.send(Frame.Accept(0))
        assertEquals(Frame.Accept(0), initiatorChannel.receive())

        val data = Frame.Data(0, 0, "abc".toByteArray())
        initiatorChannel.send(data)
        assertEquals(data, responderChannel.receive())

        initiatorChannel.send(Frame.End(0, ByteArray(32)))
        assertEquals(Frame.End(0, ByteArray(32)), responderChannel.receive())

        responderChannel.send(Frame.Ack(0, AckStatus.OK))
        assertEquals(Frame.Ack(0, AckStatus.OK), initiatorChannel.receive())

        initiatorSocket.close()
        peer.close()
        server.close()
    }

    @Test
    fun `handshake message includes the length prefix`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val output = ByteArrayOutputStream()

        NoiseStream.write(initiator, output)

        assertEquals(4 + 48, output.size())
        assertEquals(48, ByteBuffer.wrap(output.toByteArray()).int)
    }

    @Test
    fun `rejects a truncated length prefix`() {
        val responder = NoiseHandshake.responder()

        assertThrows(ProtocolException::class.java) {
            NoiseStream.read(responder, ByteArrayInputStream(byteArrayOf(0, 0)))
        }
    }

    @Test
    fun `rejects an oversized message length`() {
        val responder = NoiseHandshake.responder()
        val header = ByteBuffer.allocate(4).putInt(NoiseStream.MAX_MESSAGE + 1).array()

        assertThrows(ProtocolException::class.java) {
            NoiseStream.read(responder, ByteArrayInputStream(header))
        }
    }

    @Test
    fun `rejects an empty message length`() {
        val responder = NoiseHandshake.responder()
        val header = ByteBuffer.allocate(4).putInt(0).array()

        assertThrows(ProtocolException::class.java) {
            NoiseStream.read(responder, ByteArrayInputStream(header))
        }
    }

}
