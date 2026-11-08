package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only view of the existing Android transfer store.
 *
 * The existing AndroidDownloadStore remains the single authority for transfer
 * state and file locations. This projection never schedules, pauses, resumes,
 * cancels, or removes a transfer, and never persists duplicate transfer state.
 */
internal object AndroidDownloadQueueProjection {
    /** Live, read-only queue for the UI; no second persistence layer. */
    fun observeStore(store: AndroidDownloadStore): Flow<DownloadQueue> =
        store.transfers.map { transfers -> fromTransfers(transfers.values) }

    fun fromStore(store: AndroidDownloadStore): DownloadQueue =
        fromTransfers(store.transfers.value.values)

    fun fromTransfers(transfers: Collection<AndroidDownloadTransfer>): DownloadQueue =
        DownloadQueue.restore(transfers.map { transfer ->
            val item = transfer.item
            val state = when (item.status) {
                DownloadStatus.Downloading -> DownloadState.Running(
                    item.downloadedBytes.coerceAtLeast(0),
                    item.totalBytes?.takeIf { it >= item.downloadedBytes && it >= 0 },
                )
                DownloadStatus.Paused -> DownloadState.Paused
                DownloadStatus.Completed -> item.localFileUri
                    ?.takeIf(String::isNotBlank)
                    ?.let(DownloadState::Completed)
                    ?: DownloadState.Failed(false, "Completed transfer has no local file URI")
                DownloadStatus.Failed -> DownloadState.Failed(true, item.errorMessage ?: "Download failed")
                else -> DownloadState.Queued
            }
            DownloadEntry(id = item.id, title = item.title, state = state)
        })
}
