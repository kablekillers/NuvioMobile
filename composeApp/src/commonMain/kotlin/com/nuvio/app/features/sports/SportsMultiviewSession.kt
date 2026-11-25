package com.nuvio.app.features.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.streams.StreamItem

/** One screen in Multi-View: an already resolved direct link plus the headers it needs. */
internal data class SportsMultiviewTile(
    val id: String,
    val label: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
)

/**
 * Multi-View screens live here rather than in the hub's composition, so they
 * survive opening a stream, switching tabs and coming back.
 */
internal object SportsMultiviewSession {
    const val MAX_TILES = 4

    val tiles = mutableStateListOf<SportsMultiviewTile>()
    var focusedId by mutableStateOf<String?>(null)
    var open by mutableStateOf(false)

    fun tileFor(sourceName: String, stream: StreamItem): SportsMultiviewTile? {
        val url = SportsMultiviewSourceSelection.directUrl(stream) ?: return null
        return SportsMultiviewTile(
            id = stream.addonId + "|" + url,
            label = sourceName,
            url = url,
            requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            streamType = stream.streamType,
        )
    }

    /** Add a screen (the oldest one makes room once all four are used), give it audio and show Multi-View. */
    fun add(tile: SportsMultiviewTile) {
        tiles.removeAll { it.id == tile.id }
        while (tiles.size >= MAX_TILES) tiles.removeAt(0)
        tiles.add(tile)
        focusedId = tile.id
        open = true
    }

    fun remove(id: String?) {
        tiles.removeAll { it.id == id }
        if (focusedId == id || tiles.none { it.id == focusedId }) focusedId = tiles.firstOrNull()?.id
        if (tiles.isEmpty()) open = false
    }

    fun clear() {
        tiles.clear()
        focusedId = null
        open = false
    }
}
