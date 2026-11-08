package com.nuvio.app.features.downloads

/**
 * A platform-independent snapshot of one device-local download.
 * This model does not start transfers or alter existing playback behavior.
 */
internal sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val bytesDownloaded: Long, val totalBytes: Long?) : DownloadState {
        init {
            require(bytesDownloaded >= 0) { "Downloaded bytes cannot be negative" }
            require(totalBytes == null || totalBytes >= bytesDownloaded) {
                "Total bytes must be at least downloaded bytes"
            }
        }

        /** Null when the server did not provide a trustworthy total length. */
        val progressFraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toDouble().div(it).toFloat() }
    }
    data object Paused : DownloadState
    data class Completed(val localUri: String) : DownloadState {
        init { require(localUri.isNotBlank()) { "Completed downloads require a local URI" } }
    }
    data class Failed(val retryable: Boolean, val reason: String) : DownloadState
    data object Cancelled : DownloadState
}
