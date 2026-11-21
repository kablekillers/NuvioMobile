package com.nuvio.app.features.music

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One playable song, station or recording in the music player's queue. */
data class MusicTrack(
    /** Addon id ("audius:…", "radio:…") or a stable key for direct album tracks. */
    val id: String,
    val title: String,
    val artist: String = "",
    val artwork: String? = null,
    /** Direct audio URL when known; otherwise it's resolved from the music addon's streams. */
    val url: String? = null,
    val isLive: Boolean = false,
)

enum class MusicRepeatMode { Off, All, One }

data class MusicPlayerState(
    val queue: List<MusicTrack> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: MusicRepeatMode = MusicRepeatMode.Off,
    val error: String? = null,
) {
    val current: MusicTrack? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index in 0 until queue.lastIndex || repeat == MusicRepeatMode.All
    val hasPrevious: Boolean get() = index > 0
}

/** Platform audio output used by [MusicPlayer]. */
internal expect object MusicAudioEngine {
    fun play(url: String, headers: Map<String, String>)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    /** Called with (isPlaying, isBuffering, positionMs, durationMs, ended, errorMessage). */
    fun setListener(listener: (Boolean, Boolean, Long, Long, Boolean, String?) -> Unit)
}

/** App-wide music player: one queue that keeps playing while you move between tabs. */
object MusicPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private val _recent = MutableStateFlow<List<MusicTrack>>(emptyList())
    /** Tracks played this session, newest first. */
    val recent: StateFlow<List<MusicTrack>> = _recent.asStateFlow()

    private var listenerInstalled = false

    private fun ensureListener() {
        if (listenerInstalled) return
        listenerInstalled = true
        MusicAudioEngine.setListener { playing, buffering, position, duration, ended, error ->
            _state.update {
                it.copy(
                    isPlaying = playing,
                    isBuffering = buffering,
                    positionMs = position,
                    durationMs = duration,
                    error = error ?: it.error.takeIf { _ -> !playing },
                )
            }
            if (ended) onTrackEnded()
        }
    }

    fun playQueue(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        ensureListener()
        _state.update {
            it.copy(queue = tracks, index = startIndex.coerceIn(0, tracks.lastIndex), positionMs = 0L, durationMs = 0L, error = null)
        }
        loadCurrent()
    }

    fun playTrack(track: MusicTrack) = playQueue(listOf(track), 0)

    fun togglePlayPause() {
        val s = _state.value
        if (s.current == null) return
        if (s.isPlaying) MusicAudioEngine.pause() else MusicAudioEngine.resume()
    }

    fun pause() {
        if (_state.value.isPlaying) MusicAudioEngine.pause()
    }

    fun next() {
        val s = _state.value
        val nextIndex = when {
            s.shuffle && s.queue.size > 1 -> (s.queue.indices - s.index).random()
            s.index < s.queue.lastIndex -> s.index + 1
            s.repeat == MusicRepeatMode.All -> 0
            else -> return
        }
        _state.update { it.copy(index = nextIndex, positionMs = 0L, durationMs = 0L, error = null) }
        loadCurrent()
    }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 4_000L || s.index <= 0) {
            seekTo(0L)
            return
        }
        _state.update { it.copy(index = s.index - 1, positionMs = 0L, durationMs = 0L, error = null) }
        loadCurrent()
    }

    fun jumpTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        _state.update { it.copy(index = index, positionMs = 0L, durationMs = 0L, error = null) }
        loadCurrent()
    }

    fun seekTo(positionMs: Long) {
        MusicAudioEngine.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun toggleShuffle() = _state.update { it.copy(shuffle = !it.shuffle) }

    fun cycleRepeat() = _state.update {
        it.copy(repeat = when (it.repeat) {
            MusicRepeatMode.Off -> MusicRepeatMode.All
            MusicRepeatMode.All -> MusicRepeatMode.One
            MusicRepeatMode.One -> MusicRepeatMode.Off
        })
    }

    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return
        val newQueue = s.queue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = newQueue, index = if (index < s.index) s.index - 1 else s.index) }
    }

    fun close() {
        loadJob?.cancel()
        MusicAudioEngine.stop()
        _state.value = MusicPlayerState(shuffle = _state.value.shuffle, repeat = _state.value.repeat)
    }

    private fun onTrackEnded() {
        val s = _state.value
        when {
            s.repeat == MusicRepeatMode.One -> { seekTo(0L); MusicAudioEngine.resume() }
            s.hasNext || s.shuffle -> next()
            else -> _state.update { it.copy(isPlaying = false, positionMs = 0L) }
        }
    }

    private fun loadCurrent() {
        val track = _state.value.current ?: return
        loadJob?.cancel()
        _state.update { it.copy(isBuffering = true, isPlaying = false) }
        _recent.update { list -> (listOf(track) + list.filterNot { it.id == track.id }).take(20) }
        loadJob = scope.launch {
            val url = track.url ?: try {
                resolveStreamUrl(track.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (url == null) {
                _state.update { it.copy(isBuffering = false, error = "This track can't be played right now.") }
                return@launch
            }
            MusicAudioEngine.play(url, emptyMap())
        }
    }

    /** Asks the Kable Killers Music addon for a playable audio URL. */
    private suspend fun resolveStreamUrl(id: String): String? {
        val manifest = musicAddonManifestUrl() ?: return null
        val base = manifest.substringBeforeLast("/").trimEnd('/')
        val encodedId = id.encodeUrlPathSegment()
        val body = fetchAddonResponseText("$base/stream/music/$encodedId.json")
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val streams = root["streams"] as? JsonArray ?: return null
        return streams.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
        }
    }
}

internal suspend fun musicAddonManifestUrl(): String? {
    AddonRepository.initialize()
    AddonRepository.awaitManifestsLoaded()
    return AddonRepository.uiState.value.addons.firstOrNull {
        it.isActive && it.manifest?.id == MUSIC_ADDON_ID
    }?.manifest?.transportUrl
}

internal const val MUSIC_ADDON_ID = "org.kablekillers.music"

/** Splits the addon's "Title — Artist" names. */
internal fun MetaPreview.toMusicTrack(): MusicTrack {
    val raw = name.removePrefix("📻").trim()
    val parts = raw.split(" — ", limit = 2)
    return MusicTrack(
        id = id,
        title = parts[0].trim(),
        artist = parts.getOrNull(1)?.trim().orEmpty(),
        artwork = poster ?: banner,
        isLive = id.startsWith("radio:"),
    )
}

internal fun String.encodeUrlPathSegment(): String = buildString {
    this@encodeUrlPathSegment.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        val ch = n.toChar()
        if (n < 128 && (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == ':')) {
            append(ch)
        } else {
            append('%')
            append("0123456789ABCDEF"[n shr 4])
            append("0123456789ABCDEF"[n and 15])
        }
    }
}
