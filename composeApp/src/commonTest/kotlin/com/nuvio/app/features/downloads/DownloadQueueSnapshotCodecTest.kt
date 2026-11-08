package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DownloadQueueSnapshotCodecTest {
    @Test
    fun completedAndFailedEntriesSurviveRoundTrip() {
        val original = DownloadQueue.empty()
            .enqueue("done", "Completed movie")
            .update("done", DownloadState.Running(20, 20))
            .update("done", DownloadState.Completed("file:///completed.mp4"))
            .enqueue("failed", "Failed movie")
            .update("failed", DownloadState.Failed(true, "Connection lost"))
        val restored = DownloadQueueSnapshotCodec.decode(DownloadQueueSnapshotCodec.encode(original))
        assertEquals(original.entries, restored.entries)
    }

    @Test
    fun activeTransferRestoresPausedRatherThanRunning() {
        val original = DownloadQueue.empty().enqueue("active", "Downloading")
            .update("active", DownloadState.Running(50, 100))
        val restored = DownloadQueueSnapshotCodec.decode(DownloadQueueSnapshotCodec.encode(original))
        assertEquals(DownloadState.Paused, restored.entries.single().state)
    }

    @Test
    fun rejectsUnsupportedSnapshotVersion() {
        assertFailsWith<IllegalArgumentException> {
            DownloadQueueSnapshotCodec.decode("""{"version":99,"items":[]}""")
        }
    }

    @Test
    fun rejectsCompletedEntryWithoutLocalUri() {
        assertFailsWith<IllegalArgumentException> {
            DownloadQueueSnapshotCodec.decode(
                """{"version":1,"items":[{"id":"one","title":"Movie","status":"completed"}]}"""
            )
        }
    }

    @Test
    fun emptyQueueRoundTrips() {
        val restored = DownloadQueueSnapshotCodec.decode(
            DownloadQueueSnapshotCodec.encode(DownloadQueue.empty())
        )
        assertIs<DownloadQueue>(restored)
        assertEquals(0, restored.entries.size)
    }
}
