package dev.leandro.quicklydrop.core.transfer

import dev.leandro.quicklydrop.protocol.AckStatus
import dev.leandro.quicklydrop.protocol.CancelReason
import dev.leandro.quicklydrop.protocol.Frame
import dev.leandro.quicklydrop.protocol.FrameChannel
import dev.leandro.quicklydrop.protocol.ProtocolException
import dev.leandro.quicklydrop.protocol.TransferStateMachine
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Sends one offer, then each file in sequence.
 *
 * The channel is blocking. Cancellation is checked between chunks; a blocked read is
 * interrupted by closing the underlying socket from another thread.
 */
class TransferSender(
    private val channel: FrameChannel,
    private val listener: TransferListener = TransferListener.NONE,
    private val limits: TransferLimits = TransferLimits(),
) {

    fun send(
        files: List<FileSource>,
        cancelled: () -> Boolean = { false },
    ): TransferResult {
        if (files.isEmpty() || files.size > limits.maxFiles) {
            return TransferResult.Failure(
                TransferFailure.PROTOCOL,
                "invalid file count: ${files.size}",
            )
        }

        val metas = files.map { it.meta }
        val totalBytes = metas.sumOf { it.size }

        return try {
            channel.send(Frame.Offer(metas))
            listener.onOffer(metas)

            var transferred = 0L
            files.forEachIndexed { index, file ->
                val state = TransferStateMachine()
                state.offerSent()

                when (val response = nextFrame()) {
                    is Frame.Accept -> {
                        if (response.fileIndex != index) {
                            throw TransferException(
                                TransferFailure.PROTOCOL,
                                "accepted file ${response.fileIndex}, expected $index",
                            )
                        }
                        if (response.startOffset != 0L) {
                            throw TransferException(
                                TransferFailure.PROTOCOL,
                                "resume is not supported: offset ${response.startOffset}",
                            )
                        }
                    }

                    is Frame.Reject -> {
                        return TransferResult.Failure(
                            TransferFailure.REJECTED,
                            response.message.ifEmpty { "receiver rejected the offer" },
                        )
                    }

                    is Frame.Error -> return failureFrom(response)
                    is Frame.Cancel -> return cancelled(response.reason)
                    else -> throw unexpected("Accept", response)
                }
                state.accepted()
                state.sending()
                listener.onFileStarted(index, file.meta)

                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                file.open().use { input ->
                    val buffer = ByteArray(limits.chunkSize)
                    while (true) {
                        if (cancelled()) {
                            sendCancelQuietly()
                            return TransferResult.Failure(TransferFailure.CANCELLED, "cancelled")
                        }

                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (fileBytes + read > file.meta.size) {
                            throw TransferException(
                                TransferFailure.PROTOCOL,
                                "source is larger than the declared size",
                            )
                        }

                        digest.update(buffer, 0, read)
                        channel.send(Frame.Data(index, fileBytes, buffer.copyOf(read)))
                        fileBytes += read
                        transferred += read
                        listener.onProgress(
                            TransferProgress(
                                fileIndex = index,
                                filesTotal = files.size,
                                file = file.meta,
                                fileBytes = fileBytes,
                                totalBytes = totalBytes,
                                transferredBytes = transferred,
                            ),
                        )
                    }
                }

                if (fileBytes != file.meta.size) {
                    throw TransferException(
                        TransferFailure.PROTOCOL,
                        "source size $fileBytes differs from declared ${file.meta.size}",
                    )
                }

                state.awaitingAck()
                channel.send(Frame.End(index, digest.digest()))

                when (val ack = nextFrame()) {
                    is Frame.Ack -> {
                        if (ack.fileIndex != index) {
                            throw TransferException(
                                TransferFailure.PROTOCOL,
                                "acked file ${ack.fileIndex}, expected $index",
                            )
                        }
                        when (ack.status) {
                            AckStatus.OK -> state.completed()
                            AckStatus.HASH_MISMATCH -> throw TransferException(
                                TransferFailure.HASH_MISMATCH,
                                "receiver reported a hash mismatch",
                            )

                            AckStatus.WRITE_ERROR -> throw TransferException(
                                TransferFailure.WRITE_ERROR,
                                "receiver reported a write error",
                            )

                            AckStatus.CANCELLED -> throw TransferException(
                                TransferFailure.CANCELLED,
                                "receiver cancelled the transfer",
                            )
                        }
                    }

                    is Frame.Error -> return failureFrom(ack)
                    is Frame.Cancel -> return cancelled(ack.reason)
                    else -> throw unexpected("Ack", ack)
                }

                listener.onFileCompleted(index, file.meta)
            }

            TransferResult.Success(files.size, totalBytes)
        } catch (e: TransferException) {
            sendErrorQuietly(e)
            TransferResult.Failure(e.failure, e.message ?: "transfer failed", e)
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
                TransferResult.Failure(TransferFailure.CANCELLED, "cancelled", e)
            } else {
                sendErrorQuietly(
                    TransferException(TransferFailure.IO, e.message ?: "I/O error", e),
                )
                TransferResult.Failure(TransferFailure.IO, e.message ?: "I/O error", e)
            }
        } catch (e: Exception) {
            sendErrorQuietly(
                TransferException(TransferFailure.INTERNAL, e.message ?: "internal error", e),
            )
            TransferResult.Failure(TransferFailure.INTERNAL, e.message ?: "internal error", e)
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
}
