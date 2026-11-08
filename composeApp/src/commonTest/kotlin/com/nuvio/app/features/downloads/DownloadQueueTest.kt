package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class DownloadQueueTest {
    @Test
    fun queueKeepsOtherDownloadsUnchanged() {
        val initial = DownloadQueue.empty().enqueue("one", "Movie One").enqueue("two", "Movie Two")
        val updated = initial.update("one", DownloadState.Running(25, 100))
        assertEquals(DownloadState.Queued, initial.entries[0].state)
        assertEquals(DownloadState.Queued, updated.entries[1].state)
        assertEquals(0.25f, (updated.entries[0].state as DownloadState.Running).progressFraction)
    }

    @Test
    fun unknownLengthHasNoFabricatedProgress() {
        assertNull(DownloadState.Running(12, null).progressFraction)
        assertNull(DownloadState.Running(0, 0).progressFraction)
    }

    @Test
    fun invalidByteCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { DownloadState.Running(-1, 100) }
        assertFailsWith<IllegalArgumentException> { DownloadState.Running(101, 100) }
    }

    @Test
    fun completedDownloadCannotBeOverwritten() {
        val queue = DownloadQueue.empty().enqueue("one", "Movie")
            .update("one", DownloadState.Running(10, 10))
            .update("one", DownloadState.Completed("file:///movie.mp4"))
        assertFailsWith<IllegalArgumentException> {
            queue.update("one", DownloadState.Queued)
        }
        assertIs<DownloadState.Completed>(queue.entries.single().state)
    }

    @Test
    fun failedDownloadCanBeRequeued() {
        val queue = DownloadQueue.empty().enqueue("one", "Movie")
            .update("one", DownloadState.Failed(true, "Connection lost"))
            .update("one", DownloadState.Queued)
        assertEquals(DownloadState.Queued, queue.entries.single().state)
    }

    @Test
    fun duplicateIdsAndUnknownUpdatesAreRejected() {
        val queue = DownloadQueue.empty().enqueue("one", "Movie")
        assertFailsWith<IllegalArgumentException> { queue.enqueue("one", "Another movie") }
        assertFailsWith<NoSuchElementException> {
            queue.update("missing", DownloadState.Paused)
        }
    }

    @Test
    fun forgettingMetadataDoesNotAffectOtherItems() {
        val queue = DownloadQueue.empty().enqueue("one", "Movie One").enqueue("two", "Movie Two")
        assertEquals(listOf("two"), queue.forget("one").entries.map { it.id })
        assertEquals(2, queue.entries.size)
    }
}
