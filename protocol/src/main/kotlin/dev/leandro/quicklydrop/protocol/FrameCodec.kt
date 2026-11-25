package dev.leandro.quicklydrop.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Binary frame codec.
 *
 * Format: `u8 type`, `u8 flags`, payload. The transport framing (length prefix)
 * lives in [writeTo]/[readFrom] and in [SecureChannel].
 */
object FrameCodec {

    const val MAX_PLAINTEXT = 65519
    const val MAX_DATA = 32 * 1024
    const val MAX_FILES = 1024
    const val MAX_MESSAGE = 512

    private const val MAX_NAME = 255
    private const val MAX_MIME = 127

    private enum class Type(val code: Int) {
        OFFER(0x01),
        ACCEPT(0x02),
        REJECT(0x03),
        DATA(0x04),
        END(0x05),
        ACK(0x06),
        CANCEL(0x07),
        ERROR(0x08);

        companion object {
            fun fromCode(code: Int): Type =
                entries.firstOrNull { it.code == code }
                    ?: throw ProtocolException("unknown frame type: $code")
        }
    }

    fun encode(frame: Frame): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(typeOf(frame).code)
        data.writeByte(0)

        when (frame) {
            is Frame.Offer -> {
                if (frame.files.isEmpty() || frame.files.size > MAX_FILES) {
                    throw ProtocolException("invalid file count: ${frame.files.size}")
                }
                data.writeShort(frame.files.size)
                frame.files.forEach { file ->
                    data.writeString(file.name, MAX_NAME)
                    data.writeLong(file.size)
                    data.writeString(file.mime, MAX_MIME)
                }
            }

            is Frame.Accept -> {
                data.writeIndex(frame.fileIndex)
                if (frame.startOffset < 0) throw ProtocolException("negative offset")
                data.writeLong(frame.startOffset)
            }

            is Frame.Reject -> {
                data.writeByte(frame.reason.code)
                data.writeString(frame.message, MAX_MESSAGE)
            }

            is Frame.Data -> {
                data.writeIndex(frame.fileIndex)
                if (frame.offset < 0) throw ProtocolException("negative offset")
                data.writeLong(frame.offset)
                if (frame.bytes.isEmpty() || frame.bytes.size > MAX_DATA) {
                    throw ProtocolException("invalid data size: ${frame.bytes.size}")
                }
                data.writeInt(frame.bytes.size)
                data.write(frame.bytes)
            }

            is Frame.End -> {
                data.writeIndex(frame.fileIndex)
                if (frame.sha256.size != 32) throw ProtocolException("SHA-256 must be 32 bytes")
                data.write(frame.sha256)
            }

            is Frame.Ack -> {
                data.writeIndex(frame.fileIndex)
                data.writeByte(frame.status.code)
            }

            is Frame.Cancel -> data.writeByte(frame.reason.code)

            is Frame.Error -> {
                data.writeShort(frame.code.code)
                data.writeString(frame.message, MAX_MESSAGE)
            }
        }

        data.flush()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_PLAINTEXT) {
            throw ProtocolException("frame of ${bytes.size} bytes exceeds the limit")
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < 2) throw ProtocolException("short frame")

        val input = DataInputStream(ByteArrayInputStream(bytes))
        val type = try {
            Type.fromCode(input.readUnsignedByte())
        } catch (e: EOFException) {
            throw ProtocolException("truncated frame", e)
        }
        val flags = input.readUnsignedByte()
        if (flags != 0) throw ProtocolException("reserved flags in use: $flags")

        val frame = try {
            when (type) {
                Type.OFFER -> {
                    val count = input.readUnsignedShort()
                    if (count !in 1..MAX_FILES) throw ProtocolException("invalid count: $count")
                    val files = ArrayList<FileMeta>(count)
                    repeat(count) {
                        val name = input.readString(MAX_NAME)
                        if (name.isEmpty()) throw ProtocolException("empty name")
                        val size = input.readLong()
                        if (size < 0) throw ProtocolException("negative size")
                        val mime = input.readString(MAX_MIME)
                        files += FileMeta(name, size, mime)
                    }
                    Frame.Offer(files)
                }

                Type.ACCEPT -> {
                    val index = input.readIndex()
                    val offset = input.readLong()
                    if (offset < 0) throw ProtocolException("negative offset")
                    Frame.Accept(index, offset)
                }

                Type.REJECT -> Frame.Reject(
                    RejectReason.fromCode(input.readUnsignedByte()),
                    input.readString(MAX_MESSAGE),
                )

                Type.DATA -> {
                    val index = input.readIndex()
                    val offset = input.readLong()
                    if (offset < 0) throw ProtocolException("negative offset")
                    val length = input.readInt()
                    if (length !in 1..MAX_DATA) throw ProtocolException("invalid data length: $length")
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    Frame.Data(index, offset, payload)
                }

                Type.END -> {
                    val index = input.readIndex()
                    val hash = ByteArray(32)
                    input.readFully(hash)
                    Frame.End(index, hash)
                }

                Type.ACK -> Frame.Ack(
                    input.readIndex(),
                    AckStatus.fromCode(input.readUnsignedByte()),
                )

                Type.CANCEL -> Frame.Cancel(CancelReason.fromCode(input.readUnsignedByte()))

                Type.ERROR -> Frame.Error(
                    ErrorCode.fromCode(input.readUnsignedShort()),
                    input.readString(MAX_MESSAGE),
                )
            }
        } catch (e: EOFException) {
            throw ProtocolException("truncated frame", e)
        }

        if (input.available() != 0) throw ProtocolException("extra bytes in frame")
        return frame
    }

    /** Writes a length-prefixed frame without encryption. Used in tests and the lab. */
    fun writeTo(output: OutputStream, frame: Frame) {
        val bytes = encode(frame)
        output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        output.write(bytes)
        output.flush()
    }

    /** Reads a length-prefixed frame without encryption. Returns `null` at end of stream. */
    fun readFrom(input: InputStream): Frame? {
        val header = readExactly(input, 4, allowEof = true) ?: return null
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..MAX_PLAINTEXT) throw ProtocolException("invalid frame length: $length")
        val bytes = readExactly(input, length, allowEof = false)!!
        return decode(bytes)
    }

    private fun typeOf(frame: Frame): Type = when (frame) {
        is Frame.Offer -> Type.OFFER
        is Frame.Accept -> Type.ACCEPT
        is Frame.Reject -> Type.REJECT
        is Frame.Data -> Type.DATA
        is Frame.End -> Type.END
        is Frame.Ack -> Type.ACK
        is Frame.Cancel -> Type.CANCEL
        is Frame.Error -> Type.ERROR
    }

    private fun DataOutputStream.writeIndex(index: Int) {
        if (index !in 0..0xFFFF) throw ProtocolException("invalid index: $index")
        writeShort(index)
    }

    private fun DataInputStream.readIndex(): Int = readUnsignedShort()

    private fun DataOutputStream.writeString(value: String, max: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > max) throw ProtocolException("string of ${bytes.size} bytes exceeds $max")
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(max: Int): String {
        val length = readUnsignedShort()
        if (length > max) throw ProtocolException("string of $length bytes exceeds $max")
        val bytes = ByteArray(length)
        readFully(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolException("invalid UTF-8", e)
        }
    }

    private fun readExactly(input: InputStream, length: Int, allowEof: Boolean): ByteArray? {
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
