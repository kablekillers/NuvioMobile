package com.nuvio.app.features.music

import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class MusicAlbumTrack(val name: String, val url: String)

internal fun isPlayableAlbum(item: MetaPreview): Boolean =
    item.id.startsWith("ia:") || item.id.startsWith("audiuspl:")

internal suspend fun fetchMusicAlbumTracks(
    item: MetaPreview,
    manifestUrl: String
): List<MusicAlbumTrack> {
    val audius = item.id.startsWith("audiuspl:")
    if (!isPlayableAlbum(item)) return emptyList()
    val identifier = item.id.substringAfter(":")
    if (!Regex("^[a-zA-Z0-9_.-]{1,200}$").matches(identifier)) return emptyList()
    val baseUrl = manifestUrl.substringBeforeLast("/").trimEnd('/')
    val path = if (audius) "/api/music/audius-album/" else "/api/music/album/"
    val response = fetchAddonResponseText(baseUrl + path + identifier)
    val tracks = Json.parseToJsonElement(response) as? JsonObject ?: return emptyList()
    return ((tracks["tracks"] as? JsonArray) ?: return emptyList()).mapNotNull { elem ->
        val track = elem as? JsonObject ?: return@mapNotNull null
        val url = track["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val sourceIsArchive = url.startsWith("https://archive.org/download/")
        val sourceIsAudius = url.startsWith(baseUrl + "/api/music/audius-track/")
        if (!sourceIsArchive && !sourceIsAudius) return@mapNotNull null
        MusicAlbumTrack(
            name = track["title"]?.jsonPrimitive?.contentOrNull ?: "Track",
            url = url,
        )
    }
}

@androidx.compose.runtime.Composable
internal expect fun MusicAlbumPlayerDialog(
    title: String,
    tracks: List<MusicAlbumTrack>,
    onClose: () -> Unit
)
