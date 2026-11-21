package com.nuvio.app.features.youtube

import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.music.encodeUrlPathSegment
import com.nuvio.app.features.music.musicAddonManifestUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class YouTubeVideo(
    val videoId: String = "",
    val title: String = "",
    val channel: String = "",
    val channelId: String = "",
    val thumbnail: String = "",
    val publishedAt: String = "",
    val duration: String? = null,
    val isLive: Boolean = false,
)

@Serializable
data class YouTubePage(
    val results: List<YouTubeVideo> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class YouTubeChannelRef(val id: String, val name: String)

@Serializable
internal data class YouTubeLocalData(
    val following: List<YouTubeChannelRef> = emptyList(),
    val history: List<YouTubeVideo> = emptyList(),
)

internal expect object YouTubeTabStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * YouTube tab data. Everything goes through the Kable Killers Music addon's official YouTube Data
 * API endpoints, so the API key stays on the server. Follows and history stay on the device.
 */
object YouTubeRepository {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val _local = MutableStateFlow(YouTubeLocalData())
    val local: StateFlow<YouTubeLocalData> = _local.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { YouTubeTabStorage.loadPayload()?.let { json.decodeFromString<YouTubeLocalData>(it) } }
            .getOrNull()?.let { _local.value = it }
    }

    private fun save() {
        runCatching { YouTubeTabStorage.savePayload(json.encodeToString(YouTubeLocalData.serializer(), _local.value)) }
    }

    fun isFollowing(channelId: String): Boolean = _local.value.following.any { it.id == channelId }

    fun toggleFollow(channelId: String, name: String) {
        ensureLoaded()
        if (channelId.isBlank()) return
        val current = _local.value.following
        _local.value = _local.value.copy(
            following = if (current.any { it.id == channelId }) current.filterNot { it.id == channelId }
            else listOf(YouTubeChannelRef(channelId, name)) + current,
        )
        save()
    }

    fun addToHistory(video: YouTubeVideo) {
        ensureLoaded()
        _local.value = _local.value.copy(
            history = (listOf(video) + _local.value.history.filterNot { it.videoId == video.videoId }).take(60),
        )
        save()
    }

    fun clearHistory() {
        ensureLoaded()
        _local.value = _local.value.copy(history = emptyList())
        save()
    }

    suspend fun search(query: String, pageToken: String? = null): YouTubePage =
        get("/api/youtube/search?q=${query.trim().take(160).encodeUrlPathSegment()}" + token(pageToken))

    suspend fun trending(category: String, pageToken: String? = null): YouTubePage =
        get("/api/youtube/trending?category=$category&region=US" + token(pageToken))

    suspend fun channel(channelId: String, pageToken: String? = null): YouTubePage =
        get("/api/youtube/channel/${channelId.encodeUrlPathSegment()}" + token(pageToken).replaceFirst('&', '?'))

    private fun token(pageToken: String?): String =
        if (pageToken.isNullOrBlank()) "" else "&pageToken=${pageToken.encodeUrlPathSegment()}"

    private suspend fun get(path: String): YouTubePage {
        val manifest = musicAddonManifestUrl() ?: throw YouTubeUnavailable("Add Kable Killers Music in Settings → Add-ons to use YouTube.")
        val base = manifest.substringBeforeLast("/").trimEnd('/')
        val body = fetchAddonResponseText(base + path)
        return json.decodeFromString<YouTubePage>(body).let { page ->
            page.copy(results = page.results.filter { Regex("^[a-zA-Z0-9_-]{11}$").matches(it.videoId) })
        }
    }
}

class YouTubeUnavailable(message: String) : Exception(message)

/** "PT1H2M3S" → "1:02:03". */
internal fun formatIsoDuration(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val match = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").matchEntire(iso) ?: return null
    val days = match.groupValues[1].toIntOrNull() ?: 0
    val hours = (match.groupValues[2].toIntOrNull() ?: 0) + days * 24
    val minutes = match.groupValues[3].toIntOrNull() ?: 0
    val seconds = match.groupValues[4].toIntOrNull() ?: 0
    if (hours == 0 && minutes == 0 && seconds == 0) return null
    fun two(n: Int) = n.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "$minutes:${two(seconds)}"
}

/** A short "3 days ago" style label from an ISO date. */
internal fun relativeAge(publishedAt: String, nowEpochMs: Long): String? {
    val instant = runCatching { kotlin.time.Instant.parse(publishedAt) }.getOrNull() ?: return null
    val minutes = (nowEpochMs - instant.toEpochMilliseconds()) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} hr ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} days ago"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)} mo ago"
        else -> "${minutes / (60 * 24 * 365)} yr ago"
    }
}
