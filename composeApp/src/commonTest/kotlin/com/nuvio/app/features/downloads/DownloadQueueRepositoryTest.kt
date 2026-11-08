package com.nuvio.app.features.downloads

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal class DownloadQueueRepositoryTest {
    private class MemoryStorage(var snapshot: String? = null) : DownloadQueueStorage {
        var failWrites = false
        var writes = 0

        override suspend fun readSnapshot(): String? = snapshot

        override suspend fun writeSnapshot(snapshot: String) {
            if (failWrites) error("Simulated storage failure")
            this.snapshot = snapshot
            writes++
        }
    }

    @Test
    fun queueRestoresFromStoredSnapshot() = runTest {
        val storage = MemoryStorage()
        val first = DownloadQueueRepository(storage)
        first.enqueue("movie-1", "Movie One")
        first.update("movie-1", DownloadState.Running(42, 100))

        val restored = DownloadQueueRepository(storage).load()
        assertEquals(1, restored.entries.size)
        assertEquals("movie-1", restored.entries.single().id)
        // Running transfers cannot resume until the platform worker reconnects.
        assertIs<DownloadState.Paused>(restored.entries.single().state)
    }

    @Test
    fun failedWriteDoesNotPublishNewQueue() = runTest {
        val storage = MemoryStorage()
        val repository = DownloadQueueRepository(storage)
        repository.enqueue("movie-1", "Movie One")

        storage.failWrites = true
        assertFailsWith<IllegalStateException> {
            repository.enqueue("movie-2", "Movie Two")
        }
        assertEquals(listOf("movie-1"), repository.load().entries.map { it.id })
        assertEquals(listOf("movie-1"), DownloadQueueRepository(storage).load().entries.map { it.id })
    }

    @Test
    fun invalidSnapshotIsNotSilentlyOverwritten() = runTest {
        val storage = MemoryStorage("{invalid-json")
        val repository = DownloadQueueRepository(storage)
        assertFailsWith<Exception> { repository.enqueue("movie-1", "Movie One") }
        assertEquals("{invalid-json", storage.snapshot)
        assertEquals(0, storage.writes)
    }

    @Test
    fun forgetOnlyChangesMetadata() = runTest {
        val storage = MemoryStorage()
        val repository = DownloadQueueRepository(storage)
        repository.enqueue("movie-1", "Movie One")
        repository.forget("movie-1")
        assertEquals(emptyList(), DownloadQueueRepository(storage).load().entries)
    }

    @Test
    fun queueUpdatesAreStoredIndependently() = runTest {
        val storage = MemoryStorage()
        val repository = DownloadQueueRepository(storage)
        repository.enqueue("movie-1", "Movie One")
        repository.enqueue("movie-2", "Movie Two")
        repository.update("movie-1", DownloadState.Running(10, 20))
        val restored = DownloadQueueRepository(storage).load()
        assertEquals(2, restored.entries.size)
        assertIs<DownloadState.Paused>(restored.entries.first().state)
        assertIs<DownloadState.Queued>(restored.entries.last().state)
    }
    @Test
    fun concurrentEnqueuesAreSerializedWithoutLostEntries() = runTest {
        val storage = MemoryStorage()
        val repository = DownloadQueueRepository(storage)
        (1..25).map { index ->
            async { repository.enqueue("download-$index", "Title $index") }
        }.awaitAll()

        val recovered = DownloadQueueRepository(storage).load()
        assertEquals(25, recovered.entries.size)
        assertEquals(25, recovered.entries.map { it.id }.distinct().size)
        assertEquals(25, storage.writes)
    }

    @Test
    fun completedDownloadRemainsCompletedAfterRestart() = runTest {
        val storage = MemoryStorage()
        val repository = DownloadQueueRepository(storage)
        repository.enqueue("movie-1", "Movie One")
        repository.update("movie-1", DownloadState.Running(100, 100))
        repository.update("movie-1", DownloadState.Completed("file:///local/movie-1.mp4"))

        val restored = DownloadQueueRepository(storage).load()
        assertEquals(
            DownloadState.Completed("file:///local/movie-1.mp4"),
            restored.entries.single().state,
        )
    }

}
