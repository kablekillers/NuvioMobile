package com.nuvio.app.features.downloads

import java.awt.Desktop
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Downloads to "Videos\Kable Killers". Data goes to "<name>.part" first and is
 * renamed when complete; a paused download resumes with an HTTP Range request.
 */
internal actual object DownloadsPlatformDownloader {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    val downloadsDirectory: File by lazy {
        File(File(System.getProperty("user.home"), "Videos"), "Kable Killers").apply { mkdirs() }
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val cancelled = AtomicBoolean(false)
        val target = File(downloadsDirectory, safeName(request.destinationFileName))
        val partial = File(target.path + ".part")

        thread(name = "kk-download", isDaemon = true) {
            try {
                val existing = if (partial.exists()) partial.length() else 0L
                val builder = HttpRequest.newBuilder(URI(request.sourceUrl)).GET()
                request.sourceHeaders.forEach { (name, value) ->
                    runCatching { builder.header(name, value) }
                }
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                if (status !in 200..299) {
                    response.body().close()
                    onFailure("Download failed (HTTP $status)")
                    return@thread
                }
                val resumed = status == 206 && existing > 0
                val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
                val total = when {
                    contentLength < 0 -> null
                    resumed -> existing + contentLength
                    else -> contentLength
                }
                var downloaded = if (resumed) existing else 0L
                RandomAccessFile(partial, "rw").use { out ->
                    if (resumed) out.seek(existing) else out.setLength(0)
                    response.body().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            if (cancelled.get()) {
                                onPaused()
                                return@thread
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250) {
                                lastReport = now
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    onFailure("Couldn't save the downloaded file")
                    return@thread
                }
                onProgress(downloaded, total ?: downloaded)
                onSuccess(target.toURI().toString(), total ?: downloaded)
            } catch (error: Throwable) {
                if (cancelled.get()) onPaused() else onFailure(error.message ?: "Download failed")
            }
        }

        return object : DownloadsTaskHandle {
            override fun cancel() {
                cancelled.set(true)
            }
        }
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun removeFile(localFileUri: String?): Boolean {
        val file = localFileUri?.let(::toFile) ?: return false
        return file.exists() && file.delete()
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val partial = File(downloadsDirectory, safeName(destinationFileName) + ".part")
        return partial.exists() && partial.delete()
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.let(::toFile)?.takeIf { it.isFile }?.let { return it.toURI().toString() }
        val name = destinationFileName.trim().takeIf { it.isNotBlank() } ?: return null
        val file = File(downloadsDirectory, safeName(name))
        return if (file.isFile) file.toURI().toString() else null
    }

    actual fun openDownloadsDirectory(): Boolean = runCatching {
        Desktop.getDesktop().open(downloadsDirectory)
        true
    }.getOrDefault(false)

    private fun toFile(uri: String): File? = runCatching {
        if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
    }.getOrNull()

    private fun safeName(name: String): String =
        name.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("").trim().ifBlank { "download" }
}
