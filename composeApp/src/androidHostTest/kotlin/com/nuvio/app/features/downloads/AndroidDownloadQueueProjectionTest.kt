package com.nuvio.app.features.downloads

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadQueueProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun liveQueueEmitsStoreChangesWithoutMutatingTransfers() = runTest {
        val store = AndroidDownloadStore(temporary.newFolder())
        val observed = async {
            AndroidDownloadQueueProjection.observeStore(store).take(2).toList()
        }
        // Start collecting before the store is updated.
        testScheduler.runCurrent()
        val transfer = store.begin(transfer(DownloadStatus.Paused).item)
        val snapshots = observed.await()
        assertEquals(0, snapshots.first().entries.size)
        assertEquals(1, snapshots.last().entries.size)
        assertEquals(transfer.item.fileName, snapshots.last().entries.single().id)
        assertEquals(DownloadStatus.Downloading, store.get(transfer.item.fileName)?.item?.status)
    }

    @Test
    fun storeSnapshotReflectsAuthoritativeTransferUpdates() {
        val store = AndroidDownloadStore(temporary.newFolder())
        val initial = store.begin(transfer(DownloadStatus.Paused).item)
        assertIs<DownloadState.Running>(AndroidDownloadQueueProjection.fromStore(store).entries.single().state)

        store.update(initial.item.fileName, initial.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused))
        }
        assertIs<DownloadState.Paused>(AndroidDownloadQueueProjection.fromStore(store).entries.single().state)
    }

    private fun transfer(status: DownloadStatus): AndroidDownloadTransfer {
        val item = downloadItem().copy(
            fileName = "projection.mkv",
            status = status,
            downloadedBytes = 20,
            totalBytes = 100,
        )
        return AndroidDownloadTransfer(item = item, jobId = 1, generation = "test-generation")
    }

    @Test
    fun runningTransferIsProjectedWithoutChangingTheSource() {
        val source = transfer(DownloadStatus.Downloading)
        val queue = AndroidDownloadQueueProjection.fromTransfers(listOf(source))
        val entry = queue.entries.single()
        assertEquals(source.item.fileName, entry.id)
        assertEquals(source.item.title, entry.title)
        assertEquals(DownloadState.Running(20, 100), entry.state)
        assertEquals(DownloadStatus.Downloading, source.item.status)
    }

    @Test
    fun pausedTransferIsProjectedAsPaused() {
        val queue = AndroidDownloadQueueProjection.fromTransfers(listOf(transfer(DownloadStatus.Paused)))
        assertIs<DownloadState.Paused>(queue.entries.single().state)
    }

    @Test
    fun completedTransferUsesExistingFileUri() {
        val source = transfer(DownloadStatus.Completed).copy(
            item = transfer(DownloadStatus.Completed).item.copy(localFileUri = "file:///downloads/projection.mkv"),
        )
        val queue = AndroidDownloadQueueProjection.fromTransfers(listOf(source))
        assertEquals(DownloadState.Completed("file:///downloads/projection.mkv"), queue.entries.single().state)
    }

    @Test
    fun missingCompletedFileUriIsNotPresentedAsPlayable() {
        val queue = AndroidDownloadQueueProjection.fromTransfers(listOf(transfer(DownloadStatus.Completed)))
        assertIs<DownloadState.Failed>(queue.entries.single().state)
    }

    @Test
    fun failedTransferPreservesErrorMessage() {
        val source = transfer(DownloadStatus.Failed).copy(
            item = transfer(DownloadStatus.Failed).item.copy(errorMessage = "Network unavailable"),
        )
        val queue = AndroidDownloadQueueProjection.fromTransfers(listOf(source))
        assertEquals(DownloadState.Failed(true, "Network unavailable"), queue.entries.single().state)
    }
}
