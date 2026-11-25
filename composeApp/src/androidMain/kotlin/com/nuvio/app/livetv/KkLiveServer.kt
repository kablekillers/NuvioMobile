package com.nuvio.app.livetv

import android.content.Context
import com.nuvio.app.features.livetv.KkLiveChannels
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Serves Kable Killers' add-on channels to the Live TV page, on this phone only:
 *  - /kk/playlist.m3u : every US channel the add-ons carry, as a playlist.
 *  - /kk/ch/<channel> : sends the player on to a working link for that channel.
 * The app shell resolves channels itself before playing (so add-on headers are kept); the
 * redirect is for anything that opens the link directly.
 */
object KkLiveServer {
    private var server: Server? = null

    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val file = File(app.filesDir, "livetv/kk_channels.json")
        KkLiveChannels.save = { text -> file.parentFile?.mkdirs(); file.writeText(text) }
        KkLiveChannels.load = { file.takeIf { it.exists() }?.readText() }
        server = runCatching {
            Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        }.getOrNull()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { addPlaylistOnce(app) }
    }

    /** The "Kable Killers" playlist is put in Live TV once; after that it's the user's to keep or remove. */
    private suspend fun addPlaylistOnce(app: Context) {
        val flags = app.getSharedPreferences("kk_livetv", Context.MODE_PRIVATE)
        if (flags.getBoolean("playlist_added", false)) return
        delay(4_000)
        runCatching {
            val live = LiveTv.get(app)
            val existing = live.prefs.currentPlaylists()
            if (existing.none { it.url == KkLiveChannels.PLAYLIST_URL }) {
                live.repository.addPlaylist(KkLiveChannels.PLAYLIST_NAME, KkLiveChannels.PLAYLIST_URL)
            }
            flags.edit().putBoolean("playlist_added", true).apply()
        }
    }

    private class Server : NanoHTTPD("127.0.0.1", KkLiveChannels.PORT) {
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.orEmpty()
            return when {
                path == "/kk/playlist.m3u" -> {
                    val body = runBlocking {
                        withTimeoutOrNull(75_000) { KkLiveChannels.playlist() }
                    } ?: "#EXTM3U\n"
                    newFixedLengthResponse(Response.Status.OK, "audio/x-mpegurl", body)
                }
                path.startsWith("/kk/ch/") -> {
                    val slug = path.removePrefix("/kk/ch/").trim('/')
                    val stream = runBlocking { withTimeoutOrNull(30_000) { KkLiveChannels.resolve(slug) } }
                    if (stream == null) {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No working link for this channel right now")
                    } else {
                        newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT, MIME_PLAINTEXT, "").apply {
                            addHeader("Location", stream.url)
                        }
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }
        }
    }
}
