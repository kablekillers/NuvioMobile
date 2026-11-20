package com.nuvio.app.features.downloads

import java.io.File
import java.net.URI

/** Subtitles for a downloaded video live in a "<video>.subs" folder beside it. */
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory: File? = runCatching {
        val video = if (localVideoUri.startsWith("file:")) File(URI(localVideoUri)) else File(localVideoUri)
        File(video.parentFile, video.name + ".subs")
    }.getOrNull()

    actual fun read(fileName: String): String? =
        directory?.let { File(it, fileName) }?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    actual fun write(fileName: String, text: String) {
        val dir = directory ?: return
        runCatching {
            dir.mkdirs()
            File(dir, fileName).writeText(text)
        }
    }

    actual fun localFileUri(fileName: String): String? =
        directory?.let { File(it, fileName) }?.takeIf { it.isFile }?.toURI()?.toString()

    actual fun remove() {
        directory?.deleteRecursively()
    }
}
