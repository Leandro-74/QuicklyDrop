package dev.leandro.quicklydrop.protocol

/** Bidirectional frame channel, implemented by [SecureChannel] and test doubles. */
interface FrameChannel {
    fun send(frame: Frame)
    fun receive(): Frame?
}
