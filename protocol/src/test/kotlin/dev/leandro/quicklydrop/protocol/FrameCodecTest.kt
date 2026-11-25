package dev.leandro.quicklydrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class FrameCodecTest {

    @Test
    fun `round trip offer`() {
        val frame = Frame.Offer(
            listOf(
                FileMeta("foto ção.jpg", 1234, "image/jpeg"),
                FileMeta("relatório.pdf", 9_000_000_000, "application/pdf"),
            ),
        )

        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip accept`() {
        val frame = Frame.Accept(7, 0)
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip reject`() {
        val frame = Frame.Reject(RejectReason.NO_SPACE, "no space")
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip data`() {
        val frame = Frame.Data(3, 1_048_576, ByteArray(FrameCodec.MAX_DATA) { (it % 251).toByte() })
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip end`() {
        val frame = Frame.End(2, ByteArray(32) { it.toByte() })
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip ack`() {
        val frame = Frame.Ack(4, AckStatus.HASH_MISMATCH)
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip cancel`() {
        val frame = Frame.Cancel(CancelReason.TIMEOUT)
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `round trip error`() {
        val frame = Frame.Error(ErrorCode.NO_SPACE, "disk full")
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `writeTo and readFrom preserve the sequence`() {
        val output = ByteArrayOutputStream()
        val offer = Frame.Offer(listOf(FileMeta("a.txt", 1, "text/plain")))
        val data = Frame.Data(0, 0, byteArrayOf(1, 2, 3))

        FrameCodec.writeTo(output, offer)
        FrameCodec.writeTo(output, data)

        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals(offer, FrameCodec.readFrom(input))
        assertEquals(data, FrameCodec.readFrom(input))
        assertNull(FrameCodec.readFrom(input))
    }

    @Test
    fun `rejects an unknown type`() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(byteArrayOf(0x7F, 0))
        }
    }

    @Test
    fun `rejects reserved flags`() {
        val bytes = FrameCodec.encode(Frame.Cancel(CancelReason.USER))
        bytes[1] = 1

        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(bytes)
        }
    }

    @Test
    fun `rejects a truncated frame`() {
        val bytes = FrameCodec.encode(Frame.Offer(listOf(FileMeta("a.txt", 1))))

        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(bytes.copyOf(bytes.size - 1))
        }
    }

    @Test
    fun `rejects extra bytes`() {
        val bytes = FrameCodec.encode(Frame.Cancel(CancelReason.USER)) + byteArrayOf(0)

        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(bytes)
        }
    }

    @Test
    fun `rejects invalid utf8`() {
        val bytes = FrameCodec.encode(Frame.Reject(RejectReason.INVALID, "A"))
        bytes[3] = 0
        bytes[4] = 1
        bytes[5] = 0xFF.toByte()

        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(bytes)
        }
    }

    @Test
    fun `rejects data above the limit`() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(Frame.Data(0, 0, ByteArray(FrameCodec.MAX_DATA + 1)))
        }
    }

    @Test
    fun `rejects empty data`() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(Frame.Data(0, 0, ByteArray(0)))
        }
    }

    @Test
    fun `rejects an empty offer`() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(Frame.Offer(emptyList()))
        }
    }

    @Test
    fun `rejects an empty name`() {
        assertThrows(IllegalArgumentException::class.java) {
            FileMeta("", 1)
        }
    }

    @Test
    fun `rejects a message above the limit`() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(Frame.Error(ErrorCode.INTERNAL, "x".repeat(FrameCodec.MAX_MESSAGE + 1)))
        }
    }

    @Test
    fun `rejects an invalid frame length in the stream`() {
        val input = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(0).array())

        assertThrows(ProtocolException::class.java) {
            FrameCodec.readFrom(input)
        }
    }

    @Test
    fun `rejects a truncated stream`() {
        val input = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(10).array() + ByteArray(5))

        assertThrows(ProtocolException::class.java) {
            FrameCodec.readFrom(input)
        }
    }

    @Test
    fun `data and end compare content`() {
        val a = Frame.Data(0, 0, byteArrayOf(1, 2, 3))
        val b = Frame.Data(0, 0, byteArrayOf(1, 2, 3))
        val c = Frame.Data(0, 1, byteArrayOf(1, 2, 3))

        assertEquals(a, b)
        assertTrue(a != c)

        val endA = Frame.End(0, ByteArray(32) { 1 })
        val endB = Frame.End(0, ByteArray(32) { 1 })
        assertEquals(endA, endB)
        assertArrayEquals(endA.sha256, endB.sha256)
    }
}
