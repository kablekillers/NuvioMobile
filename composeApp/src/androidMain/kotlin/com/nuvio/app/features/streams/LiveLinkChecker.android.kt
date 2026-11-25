package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerPlaybackNetworking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private val htmlStart = Regex("""^\s*(<!doctype html|<html|<head|<body)""", RegexOption.IGNORE_CASE)

internal actual suspend fun probeLiveLink(url: String, headers: Map<String, String>): Boolean? =
    withContext(Dispatchers.IO) {
        val first = fetchStart(url, headers) ?: return@withContext false
        val (code, contentType, body) = first
        if (code !in 200..299) return@withContext code == 405 || code == 416 // range/method quirks, not dead
        val text = body.decodeToString()
        if (text.trimStart().startsWith("#EXTM3U")) {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // Master playlist: test its first variant, since that's what the player loads.
            val variant = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                .takeIf { it >= 0 }?.let { idx -> lines.drop(idx + 1).firstOrNull { !it.startsWith("#") } }
            if (variant != null) {
                val variantUrl = runCatching { URI(url).resolve(variant).toString() }.getOrNull() ?: return@withContext true
                val v = fetchStart(variantUrl, headers) ?: return@withContext false
                if (v.first !in 200..299) return@withContext false
                val vt = v.third.decodeToString()
                return@withContext vt.trimStart().startsWith("#EXTM3U") && ("#EXTINF" in vt || "#EXT-X-PART" in vt)
            }
            return@withContext "#EXTINF" in text || "#EXT-X-PART" in text
        }
        // An HTML page instead of video is an error / "stream offline" page.
        val isHtml = contentType?.contains("text/html", ignoreCase = true) == true || htmlStart.containsMatchIn(text.take(200))
        !(isHtml && body.size < 64 * 1024)
    }

/** GET the first few KB; null when the host doesn't answer. */
private fun fetchStart(url: String, headers: Map<String, String>): Triple<Int, String?, ByteArray>? = runCatching {
    val connection: HttpURLConnection = PlayerPlaybackNetworking.openConnection(
        url = url,
        headers = headers,
        method = "GET",
        connectTimeoutMs = 4_000,
        readTimeoutMs = 4_000,
        range = "bytes=0-16383",
    )
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input ->
            val buffer = ByteArray(16_384)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: ByteArray(0)
        Triple(code, connection.contentType, bytes)
    } finally {
        connection.disconnect()
    }
}.getOrNull()
