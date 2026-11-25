package dev.leandro.quicklydrop.protocol

/** Erro de protocolo: payload, frame ou handshake inválido. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
