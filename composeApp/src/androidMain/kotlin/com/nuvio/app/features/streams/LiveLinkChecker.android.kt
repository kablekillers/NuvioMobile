package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerPlaybackNetworking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException

private val htmlStart = Regex("""^\s*(<!doctype html|<html|<head|<body)""", RegexOption.IGNORE_CASE)
private val mediaPath = Regex("""\.(m3u8|mp4|mkv|ts|mpd)(\?|$)""", RegexOption.IGNORE_CASE)

/** Status codes that mean the link itself is gone, not "busy" or "needs a token". */
private fun isGoneStatus(code: Int) = code == 404 || code == 410 || code in 500..504

private sealed interface Probe {
    data class Answer(val code: Int, val contentType: String?, val body: ByteArray) : Probe
    data object HostDown : Probe   // no such host / connection refused
    data object NoAnswer : Probe   // timeout or other hiccup: can't tell
}

/**
 * Only reports false when the link is clearly dead (gone status, host down,
 * an error web page where video should be, a playlist whose stream is gone).
 * Slow hosts, token/permission answers and anything unclear count as working,
 * so a good link is never hidden.
 */
internal actual suspend fun probeLiveLink(url: String, headers: Map<String, String>): Boolean? =
    withContext(Dispatchers.IO) {
        when (val first = fetchStart(url, headers)) {
            Probe.HostDown -> false
            Probe.NoAnswer -> null
            is Probe.Answer -> {
                if (isGoneStatus(first.code)) return@withContext false
                if (first.code !in 200..299) return@withContext null
                val text = first.body.decodeToString()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val variantIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                    val variant = if (variantIndex >= 0) lines.drop(variantIndex + 1).firstOrNull { !it.startsWith("#") } else null
                    if (variant != null) {
                        val variantUrl = runCatching { URI(url).resolve(variant).toString() }.getOrNull() ?: return@withContext true
                        val second = fetchStart(variantUrl, headers)
                        if (second == Probe.HostDown) return@withContext false
                        if (second is Probe.Answer && isGoneStatus(second.code)) return@withContext false
                    }
                    return@withContext true
                }
                val looksHtml = first.contentType?.contains("text/html", ignoreCase = true) == true ||
                    htmlStart.containsMatchIn(text.take(200))
                // A web page where a video file / playlist should be is an error or "offline" page.
                if (looksHtml && mediaPath.containsMatchIn(url)) false else true
            }
        }
    }

private fun fetchStart(url: String, headers: Map<String, String>): Probe = try {
    val connection: HttpURLConnection = PlayerPlaybackNetworking.openConnection(
        url = url,
        headers = headers,
        method = "GET",
        connectTimeoutMs = 8_000,
        readTimeoutMs = 8_000,
        range = "bytes=0-16383",
    )
    try {
        val code = connection.responseCode
        val bytes = if (code in 200..299) {
            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        } else ByteArray(0)
        Probe.Answer(code, connection.contentType, bytes)
    } finally {
        connection.disconnect()
    }
} catch (_: UnknownHostException) {
    Probe.HostDown
} catch (_: ConnectException) {
    Probe.HostDown
} catch (_: Exception) {
    Probe.NoAnswer
}
