package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.AckStatus
import dev.leandro.quicklydrop.protocol.CancelReason
import dev.leandro.quicklydrop.protocol.Frame
import dev.leandro.quicklydrop.protocol.FrameChannel
import dev.leandro.quicklydrop.protocol.FileMeta
import dev.leandro.quicklydrop.protocol.ProtocolException
import dev.leandro.quicklydrop.protocol.TransferStateMachine
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Receives one offer and then each file in sequence.
 *
 * The sink is opened before Accept; a sink failure is reported as an Error frame.
 * Partial files are aborted on every failure path.
 */
class TransferReceiver(
    private val channel: FrameChannel,
    private val sink: FileSink,
    private val listener: TransferListener = TransferListener.NONE,
    private val limits: TransferLimits = TransferLimits(),
) {

    fun receive(
        cancelled: () -> Boolean = { false },
    ): TransferResult {
        return try {
            val offer = when (val frame = nextFrame()) {
                is Frame.Offer -> frame
                is Frame.Cancel -> return cancelled(frame.reason)
                is Frame.Error -> return failureFrom(frame)
                else -> throw unexpected("Offer", frame)
            }

            validateOffer(offer.files)
            listener.onOffer(offer.files)

            val totalBytes = offer.files.sumOf { it.size }
            var transferred = 0L

            offer.files.forEachIndexed { index, meta ->
                if (cancelled()) {
                    sendCancelQuietly()
                    return TransferResult.Failure(TransferFailure.CANCELLED, "cancelled")
                }

                val target = try {
                    sink.open(meta)
                } catch (e: FileSinkException) {
                    sendErrorQuietly(e)
                    return TransferResult.Failure(e.failure, e.message ?: "sink error", e)
                } catch (e: Exception) {
                    val failure = FileSinkException(
                        TransferFailure.WRITE_ERROR,
                        e.message ?: "failed to open sink",
                        e,
                    )
                    sendErrorQuietly(failure)
                    return TransferResult.Failure(
                        TransferFailure.WRITE_ERROR,
                        failure.message ?: "sink error",
                        e,
                    )
                }

                val state = TransferStateMachine()
                state.offerSent()
                var committed = false

                try {
                    channel.send(Frame.Accept(index, 0))
                    state.accepted()
                    state.sending()
                    listener.onFileStarted(index, meta)

                    val output = target.output
                    val digest = MessageDigest.getInstance("SHA-256")
                    var offset = 0L

                    while (offset < meta.size) {
                        if (cancelled()) {
                            abortQuietly(target)
                            sendCancelQuietly()
                            return TransferResult.Failure(TransferFailure.CANCELLED, "cancelled")
                        }

                        when (val frame = nextFrame()) {
                            is Frame.Data -> {
                                if (frame.fileIndex != index) {
                                    throw TransferException(
                                        TransferFailure.PROTOCOL,
                                        "data for file ${frame.fileIndex}, expected $index",
                                    )
                                }
                                if (frame.offset != offset) {
                                    throw TransferException(
                                        TransferFailure.PROTOCOL,
                                        "data at offset ${frame.offset}, expected $offset",
                                    )
                                }
                                if (frame.bytes.isEmpty() || frame.bytes.size > limits.maxDataSize) {
                                    throw TransferException(
                                        TransferFailure.PROTOCOL,
                                        "invalid data length: ${frame.bytes.size}",
                                    )
                                }
                                if (frame.bytes.size > meta.size - offset) {
                                    throw TransferException(
                                        TransferFailure.PROTOCOL,
                                        "data exceeds the declared file size",
                                    )
                                }

                                output.write(frame.bytes)
                                digest.update(frame.bytes)
                                offset += frame.bytes.size
                                transferred += frame.bytes.size
                                listener.onProgress(
                                    TransferProgress(
                                        fileIndex = index,
                                        filesTotal = offer.files.size,
                                        file = meta,
                                        fileBytes = offset,
                                        totalBytes = totalBytes,
                                        transferredBytes = transferred,
                                    ),
                                )
                            }

                            is Frame.Cancel -> {
                                abortQuietly(target)
                                return cancelled(frame.reason)
                            }

                            is Frame.Error -> {
                                abortQuietly(target)
                                return failureFrom(frame)
                            }

                            else -> throw unexpected("Data or End", frame)
                        }
                    }

                    when (val end = nextFrame()) {
                        is Frame.End -> {
                            if (end.fileIndex != index) {
                                throw TransferException(
                                    TransferFailure.PROTOCOL,
                                    "end for file ${end.fileIndex}, expected $index",
                                )
                            }
                            state.awaitingAck()
                            if (!MessageDigest.isEqual(digest.digest(), end.sha256)) {
                                abortQuietly(target)
                                runCatching {
                                    channel.send(Frame.Ack(index, AckStatus.HASH_MISMATCH))
                                }
                                return TransferResult.Failure(
                                    TransferFailure.HASH_MISMATCH,
                                    "SHA-256 mismatch for ${meta.name}",
                                )
                            }
                        }

                        is Frame.Cancel -> {
                            abortQuietly(target)
                            return cancelled(end.reason)
                        }

                        is Frame.Error -> {
                            abortQuietly(target)
                            return failureFrom(end)
                        }

                        else -> throw unexpected("End", end)
                    }

                    try {
                        target.commit()
                        committed = true
                    } catch (e: Exception) {
                        throw TransferException(
                            TransferFailure.WRITE_ERROR,
                            e.message ?: "failed to commit ${meta.name}",
                            e,
                        )
                    }

                    channel.send(Frame.Ack(index, AckStatus.OK))
                    state.completed()
                    listener.onFileCompleted(index, meta)
                } catch (e: Exception) {
                    if (!committed) abortQuietly(target)
                    throw e
                } finally {
                    runCatching { target.close() }
                }
            }

            TransferResult.Success(offer.files.size, totalBytes)
        } catch (e: TransferException) {
            sendErrorQuietly(e)
            TransferResult.Failure(e.failure, e.message ?: "transfer failed", e)
        } catch (e: FileSinkException) {
            sendErrorQuietly(e)
            TransferResult.Failure(e.failure, e.message ?: "sink error", e)
        } catch (e: SocketTimeoutException) {
            val failure = TransferException(TransferFailure.TIMEOUT, "timed out", e)
            sendErrorQuietly(failure)
            TransferResult.Failure(TransferFailure.TIMEOUT, "timed out", e)
        } catch (e: ProtocolException) {
            val failure = TransferException(TransferFailure.PROTOCOL, e.message ?: "protocol error", e)
            sendErrorQuietly(failure)
            TransferResult.Failure(TransferFailure.PROTOCOL, e.message ?: "protocol error", e)
        } catch (e: IOException) {
            if (cancelled()) {
                sendCancelQuietly()
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                sendErrorQuietly(
                    TransferException(TransferFailure.IO, e.message ?: "I/O error", e),
                )
                TransferResult.Failure(TransferFailure.IO, e.message ?: "I/O error", e)
            }
        } catch (e: Exception) {
            val failure = TransferException(TransferFailure.INTERNAL, e.message ?: "internal error", e)
            sendErrorQuietly(failure)
            TransferResult.Failure(TransferFailure.INTERNAL, e.message ?: "internal error", e)
        }
    }

    private fun validateOffer(files: List<FileMeta>) {
        if (files.isEmpty()) {
            throw TransferException(TransferFailure.PROTOCOL, "empty offer")
        }
        if (files.size > limits.maxFiles) {
            throw TransferException(
                TransferFailure.PROTOCOL,
                "too many files: ${files.size}",
            )
        }
        val tooLarge = files.firstOrNull { it.size > limits.maxFileSize }
        if (tooLarge != null) {
            throw TransferException(
                TransferFailure.FILE_TOO_LARGE,
                "${tooLarge.name} exceeds the ${limits.maxFileSize} byte limit",
            )
        }
    }

    private fun nextFrame(): Frame = channel.receive()
        ?: throw TransferException(TransferFailure.IO, "connection closed")

    private fun failureFrom(error: Frame.Error): TransferResult.Failure =
        TransferResult.Failure(
            error.code.toFailure(),
            error.message.ifEmpty { "peer reported ${error.code}" },
        )

    private fun cancelled(reason: CancelReason): TransferResult.Failure =
        TransferResult.Failure(TransferFailure.CANCELLED, "peer cancelled: $reason")

    private fun unexpected(expected: String, actual: Frame): TransferException =
        TransferException(
            TransferFailure.PROTOCOL,
            "expected $expected, got ${actual::class.simpleName}",
        )

    private fun abortQuietly(target: FileSink.Sink) {
        runCatching { target.abort() }
    }

    private fun sendCancelQuietly() {
        runCatching { channel.send(Frame.Cancel(CancelReason.USER)) }
    }

    private fun sendErrorQuietly(error: TransferException) {
        if (error.failure == TransferFailure.CANCELLED) {
            sendCancelQuietly()
            return
        }
        val message = error.message.orEmpty().take(200)
        runCatching { channel.send(Frame.Error(error.failure.toErrorCode(), message)) }
    }

    private fun sendErrorQuietly(error: FileSinkException) {
        val message = error.message.orEmpty().take(200)
        runCatching { channel.send(Frame.Error(error.failure.toErrorCode(), message)) }
    }
}
