package dev.leandro.quicklydrop.core.transfer

/** Limits enforced by both ends before allocating or accepting data. */
data class TransferLimits(
    val maxFiles: Int = 1024,
    val maxFileSize: Long = 10L * 1024 * 1024 * 1024,
    val maxDataSize: Int = 32 * 1024,
    val chunkSize: Int = 32 * 1024,
) {
    init {
        require(maxFiles in 1..65535) { "invalid maxFiles: $maxFiles" }
        require(maxFileSize > 0) { "maxFileSize must be positive" }
        require(maxDataSize in 1..65535) { "invalid maxDataSize: $maxDataSize" }
        require(chunkSize in 1..maxDataSize) { "chunkSize must be between 1 and maxDataSize" }
    }
}
