package com.nuvio.app.features.downloads

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadQueueProjectionTest {
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
