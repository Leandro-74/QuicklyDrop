package dev.leandro.quicklydrop.protocol

/** Protocol error: invalid payload, frame, or handshake. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
