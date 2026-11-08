package com.nuvio.app.features.downloads

/**
 * Pure, device-local queue state. Platform download workers and persistence
 * will consume this model; no network requests or file deletion happen here.
 */
internal data class DownloadEntry(
    val id: String,
    val title: String,
    val state: DownloadState = DownloadState.Queued,
) {
    init {
        require(id.isNotBlank()) { "Download ID cannot be blank" }
        require(title.isNotBlank()) { "Download title cannot be blank" }
    }
}

internal class DownloadQueue private constructor(
    val entries: List<DownloadEntry>,
) {
    companion object {
        fun empty(): DownloadQueue = DownloadQueue(emptyList())

        fun restore(entries: List<DownloadEntry>): DownloadQueue {
            require(entries.map { it.id }.distinct().size == entries.size) {
                "Duplicate download IDs"
            }
            return DownloadQueue(entries.toList())
        }
    }

    fun enqueue(id: String, title: String): DownloadQueue {
        require(entries.none { it.id == id }) { "Download already queued: $id" }
        return restore(entries + DownloadEntry(id, title))
    }

    /** Update only the matching item; never modify another download. */
    fun update(id: String, next: DownloadState): DownloadQueue {
        val current = entries.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("Unknown download: $id")
        require(canTransition(current.state, next)) {
            "Invalid download transition from ${current.state} to $next"
        }
        return restore(entries.map { if (it.id == id) it.copy(state = next) else it })
    }

    /** Forget queue metadata only. The underlying downloaded file is not deleted. */
    fun forget(id: String): DownloadQueue = restore(entries.filterNot { it.id == id })

    private fun canTransition(previous: DownloadState, next: DownloadState): Boolean =
        when (previous) {
            DownloadState.Queued -> next is DownloadState.Running ||
                next is DownloadState.Paused || next is DownloadState.Cancelled ||
                next is DownloadState.Failed
            is DownloadState.Running -> next is DownloadState.Running ||
                next is DownloadState.Paused || next is DownloadState.Completed ||
                next is DownloadState.Failed || next is DownloadState.Cancelled
            DownloadState.Paused -> next is DownloadState.Queued ||
                next is DownloadState.Running || next is DownloadState.Cancelled ||
                next is DownloadState.Failed
            is DownloadState.Failed -> next is DownloadState.Queued ||
                next is DownloadState.Cancelled
            is DownloadState.Completed, DownloadState.Cancelled -> false
        }
}
