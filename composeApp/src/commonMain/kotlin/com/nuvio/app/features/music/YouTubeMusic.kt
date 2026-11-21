package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import com.nuvio.app.features.addons.fetchAddonResponseText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class YouTubeMusicVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String
)

internal suspend fun findYouTubeMusic(query: String, addonManifestUrl: String): List<YouTubeMusicVideo> {
    val trimmed = query.trim().take(160)
    if (trimmed.isEmpty()) return emptyList()
    val base = addonManifestUrl.substringBeforeLast("/").trimEnd('/')
    val queryEncoded = buildString {
        trimmed.encodeToByteArray().forEach { byte ->
            val n = byte.toInt() and 255
            val ch = n.toChar()
            if (ch.isLetterOrDigit() && n < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%')
                append("0123456789ABCDEF"[n shr 4])
                append("0123456789ABCDEF"[n and 15])
            }
        }
    }
    val response = fetchAddonResponseText("$base/api/music/youtube/search?q=$queryEncoded")
    val root = Json.parseToJsonElement(response) as? JsonObject ?: return emptyList()
    val videos = root["results"] as? JsonArray ?: return emptyList()
    return videos.mapNotNull { element ->
        val video = element as? JsonObject ?: return@mapNotNull null
        val id = video["videoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(id)) return@mapNotNull null
        YouTubeMusicVideo(
            videoId = id,
            title = video["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            channel = video["channel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            thumbnail = video["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}

@Composable
internal expect fun YouTubeMusicPlayerDialog(
    video: YouTubeMusicVideo,
    onClose: () -> Unit
)
