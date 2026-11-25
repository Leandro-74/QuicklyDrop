package dev.leandro.quicklydrop.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Transport indicated in the discovery payload. */
enum class Transport(val code: Int) {
    P2P(1),
    HOTSPOT(2),
    LAN(3);

    companion object {
        fun fromCode(code: Int): Transport =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown transport: $code")
    }
}

/**
 * Payload delivered over NFC (HCE).
 *
 * Length-prefixed binary format: a newline in the name, SSID, or password does not
 * break parsing. The maximum size is 199 bytes, within the short APDU limit.
 */
class DiscoveryPayload(
    val name: String,
    val ssid: String,
    val pass: String,
    val frequencyMhz: Int,
    noisePublicKey: ByteArray,
    val transport: Transport = Transport.P2P,
) {

    private val key: ByteArray = noisePublicKey.copyOf()

    /** Defensive copy of the receiver's X25519 public key. */
    val noisePublicKey: ByteArray get() = key.copyOf()

    init {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val passBytes = pass.toByteArray(StandardCharsets.UTF_8)

        require(nameBytes.isNotEmpty()) { "empty name" }
        require(nameBytes.size <= MAX_NAME) { "name longer than $MAX_NAME bytes" }
        require(ssidBytes.size <= MAX_SSID) { "SSID longer than $MAX_SSID bytes" }
        require(passBytes.size <= MAX_PASS) { "password longer than $MAX_PASS bytes" }
        require(ssid.isNotEmpty() || pass.isEmpty()) { "password without SSID" }
        require(frequencyMhz in 0..0xFFFF) { "frequency outside u16" }
        require(key.size == KEY_SIZE) { "Noise key must be $KEY_SIZE bytes" }
    }

    private val hasCredentials: Boolean get() = ssid.isNotEmpty()

    val flags: Int
        get() = transport.code or
            (if (hasCredentials) FLAG_CREDENTIALS else 0) or
            FLAG_NOISE_KEY

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(MAX_SIZE)
        buffer.put(VERSION.toByte())
        buffer.put(flags.toByte())
        putString(buffer, name)
        putString(buffer, ssid)
        putString(buffer, pass)
        buffer.putShort(frequencyMhz.toShort())
        buffer.put(key)
        return buffer.array().copyOf(buffer.position())
    }

    override fun equals(other: Any?): Boolean =
        other is DiscoveryPayload &&
            name == other.name &&
            ssid == other.ssid &&
            pass == other.pass &&
            frequencyMhz == other.frequencyMhz &&
            transport == other.transport &&
            key.contentEquals(other.key)

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ssid.hashCode()
        result = 31 * result + pass.hashCode()
        result = 31 * result + frequencyMhz
        result = 31 * result + transport.hashCode()
        result = 31 * result + key.contentHashCode()
        return result
    }

    /** Does not include the password. */
    override fun toString(): String =
        "DiscoveryPayload(name=$name, ssid=$ssid, pass=***, frequencyMhz=$frequencyMhz, " +
            "transport=$transport, key=${key.size}B)"

    companion object {
        const val VERSION = 1
        const val MAX_NAME = 64
        const val MAX_SSID = 32
        const val MAX_PASS = 64
        const val KEY_SIZE = 32
        const val MAX_SIZE = 199

        private const val FLAG_CREDENTIALS = 0x04
        private const val FLAG_NOISE_KEY = 0x08
        private const val FLAG_RESERVED = 0xF0

        fun decode(bytes: ByteArray): DiscoveryPayload {
            if (bytes.size < 2) throw ProtocolException("short payload")

            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get().toInt() and 0xFF
            if (version != VERSION) throw ProtocolException("unsupported version $version")

            val flags = buffer.get().toInt() and 0xFF
            if (flags and FLAG_RESERVED != 0) throw ProtocolException("reserved flags in use")

            val transport = Transport.fromCode(flags and 0x03)
            val hasCredentials = flags and FLAG_CREDENTIALS != 0
            val hasKey = flags and FLAG_NOISE_KEY != 0
            if (!hasKey) throw ProtocolException("missing Noise key")

            val name = readString(buffer, MAX_NAME)
            val ssid = readString(buffer, MAX_SSID)
            val pass = readString(buffer, MAX_PASS)

            if (buffer.remaining() < 2 + KEY_SIZE) throw ProtocolException("truncated payload")
            val frequency = buffer.getShort().toInt() and 0xFFFF
            val key = ByteArray(KEY_SIZE)
            buffer.get(key)
            if (buffer.hasRemaining()) throw ProtocolException("extra bytes in payload")

            if (name.isEmpty()) throw ProtocolException("empty name")
            if (hasCredentials && ssid.isEmpty()) throw ProtocolException("missing credentials")
            if (!hasCredentials && (ssid.isNotEmpty() || pass.isNotEmpty())) {
                throw ProtocolException("unexpected credentials")
            }

            return DiscoveryPayload(name, ssid, pass, frequency, key, transport)
        }

        private fun putString(buffer: ByteBuffer, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > 0xFF) throw ProtocolException("string too long")
            buffer.put(bytes.size.toByte())
            buffer.put(bytes)
        }

        private fun readString(buffer: ByteBuffer, max: Int): String {
            if (buffer.remaining() < 1) throw ProtocolException("truncated payload")
            val length = buffer.get().toInt() and 0xFF
            if (length > max) throw ProtocolException("string of $length bytes exceeds the limit $max")
            if (buffer.remaining() < length) throw ProtocolException("truncated payload")
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return decodeUtf8(bytes)
        }

        private fun decodeUtf8(bytes: ByteArray): String {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw ProtocolException("invalid UTF-8", e)
            }
        }
    }
}
