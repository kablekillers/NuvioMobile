package com.nuvio.app.features.downloads

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-only queue snapshot storage, scoped to this app installation.
 *
 * A temporary file is fully flushed before replacing the previous snapshot.
 * No media files are touched by this adapter.
 */
internal class AndroidDownloadQueueStorage(context: Context) : DownloadQueueStorage {
    private val directory: File = File(context.applicationContext.filesDir, "download_manager")
    private val snapshotFile: File = File(directory, "queue-v1.json")

    override suspend fun readSnapshot(): String? = withContext(Dispatchers.IO) {
        if (!snapshotFile.exists()) null else snapshotFile.readText(Charsets.UTF_8)
    }

    override suspend fun writeSnapshot(snapshot: String): Unit = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create download manager storage directory"
        }
        val temporaryFile = File(directory, "queue-v1.json.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(snapshot.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryFile.toPath(),
                snapshotFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // Cleanup only our temporary snapshot, never downloaded media.
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }
}
