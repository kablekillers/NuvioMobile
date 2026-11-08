package com.nuvio.app.features.downloads

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Storage contract for the queue snapshot. Implementations must commit the
 * complete string atomically, or throw without modifying the previous value.
 */
internal interface DownloadQueueStorage {
    suspend fun readSnapshot(): String?
    suspend fun writeSnapshot(snapshot: String)
}

/**
 * Serialized queue mutations. Disk writes happen before publishing a new
 * in-memory queue, so a failed write never reports a successful change.
 *
 * No transfer execution or file deletion is performed by this repository.
 */
internal class DownloadQueueRepository(private val storage: DownloadQueueStorage) {
    private val mutex = Mutex()
    private var loaded = false
    private var current = DownloadQueue.empty()

    suspend fun load(): DownloadQueue = mutex.withLock {
        if (!loaded) {
            val snapshot = storage.readSnapshot()
            current = if (snapshot.isNullOrBlank()) {
                DownloadQueue.empty()
            } else {
                DownloadQueueSnapshotCodec.decode(snapshot)
            }
            loaded = true
        }
        current
    }

    suspend fun enqueue(id: String, title: String): DownloadQueue =
        mutate { it.enqueue(id, title) }

    suspend fun update(id: String, state: DownloadState): DownloadQueue =
        mutate { it.update(id, state) }

    suspend fun forget(id: String): DownloadQueue =
        mutate { it.forget(id) }

    private suspend fun mutate(change: (DownloadQueue) -> DownloadQueue): DownloadQueue =
        mutex.withLock {
            if (!loaded) {
                val snapshot = storage.readSnapshot()
                current = if (snapshot.isNullOrBlank()) DownloadQueue.empty()
                    else DownloadQueueSnapshotCodec.decode(snapshot)
                loaded = true
            }
            val next = change(current)
            storage.writeSnapshot(DownloadQueueSnapshotCodec.encode(next))
            current = next
            next
        }
}
