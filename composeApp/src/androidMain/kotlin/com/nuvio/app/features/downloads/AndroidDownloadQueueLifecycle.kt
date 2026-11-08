package com.nuvio.app.features.downloads

import android.content.Context

/**
 * Opt-in Android bridge for the device-local download queue.
 *
 * Initialization is deliberately not wired to app startup while Phase 2 is
 * under development. Consumers explicitly call [load] and handle failures.
 * No transfers are started or resumed by this component.
 */
internal object AndroidDownloadQueueLifecycle {
    @Volatile
    private var repository: DownloadQueueRepository? = null

    @Synchronized
    fun initialize(context: Context): DownloadQueueRepository {
        repository?.let { return it }
        return DownloadQueueRepository(AndroidDownloadQueueStorage(context.applicationContext))
            .also { repository = it }
    }

    /** Restores saved queue state; errors are propagated, never swallowed. */
    suspend fun load(context: Context): DownloadQueue = initialize(context).load()

    fun repositoryOrNull(): DownloadQueueRepository? = repository
}
