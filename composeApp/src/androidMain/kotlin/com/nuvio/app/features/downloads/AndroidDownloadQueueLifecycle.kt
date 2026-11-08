package com.nuvio.app.features.downloads

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opt-in Android lifecycle bridge for the device-local download queue.
 *
 * Call [initialize] once from the Android app's startup path when the
 * download-manager UI is ready to consume recovered queue state.
 * Initialization is intentionally NOT wired into startup yet: this feature
 * branch must not alter the existing customer download implementation.
 *
 * The restored queue converts interrupted Running transfers to Paused through
 * DownloadQueueSnapshotCodec. No network transfer is automatically resumed.
 */
internal object AndroidDownloadQueueLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: DownloadQueueRepository? = null

    @Synchronized
    fun initialize(context: Context) {
        if (repository != null) return
        val queueRepository = DownloadQueueRepository(AndroidDownloadQueueStorage(context))
        repository = queueRepository
        scope.launch {
            // Failures remain observable to callers of load() and do not
            // trigger any media deletion or transfer execution.
            runCatching { queueRepository.load() }
        }
    }

    @Synchronized
    fun repositoryOrNull(): DownloadQueueRepository? = repository
}
