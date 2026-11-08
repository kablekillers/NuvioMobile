package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Versioned on-device queue snapshot. This codec does not perform disk I/O.
 * The platform storage adapter must write snapshots atomically.
 */
internal object DownloadQueueSnapshotCodec {
    private const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Snapshot(val version: Int = CURRENT_VERSION, val items: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        val title: String,
        val status: String,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long? = null,
        val localUri: String? = null,
        val retryable: Boolean = false,
        val reason: String = "",
    )

    fun encode(queue: DownloadQueue): String = json.encodeToString(
        Snapshot(items = queue.entries.map { entry ->
            val state = entry.state
            Item(
                id = entry.id,
                title = entry.title,
                status = when (state) {
                    DownloadState.Queued -> "queued"
                    is DownloadState.Running -> "running"
                    DownloadState.Paused -> "paused"
                    is DownloadState.Completed -> "completed"
                    is DownloadState.Failed -> "failed"
                    DownloadState.Cancelled -> "cancelled"
                },
                bytesDownloaded = (state as? DownloadState.Running)?.bytesDownloaded ?: 0,
                totalBytes = (state as? DownloadState.Running)?.totalBytes,
                localUri = (state as? DownloadState.Completed)?.localUri,
                retryable = (state as? DownloadState.Failed)?.retryable ?: false,
                reason = (state as? DownloadState.Failed)?.reason.orEmpty(),
            )
        })
    )

    /**
     * Active transfers are restored as paused, never as running: a worker
     * must explicitly reconcile partial files before resuming.
     */
    fun decode(raw: String): DownloadQueue {
        val snapshot = json.decodeFromString<Snapshot>(raw)
        require(snapshot.version == CURRENT_VERSION) { "Unsupported download snapshot version" }
        return DownloadQueue.restore(snapshot.items.map { item ->
            val state: DownloadState = when (item.status) {
                "queued" -> DownloadState.Queued
                "running", "paused" -> DownloadState.Paused
                "completed" -> DownloadState.Completed(
                    requireNotNull(item.localUri) { "Completed item missing local URI" }
                )
                "failed" -> DownloadState.Failed(item.retryable, item.reason)
                "cancelled" -> DownloadState.Cancelled
                else -> throw IllegalArgumentException("Unknown download status: ${item.status}")
            }
            DownloadEntry(item.id, item.title, state)
        })
    }
}
