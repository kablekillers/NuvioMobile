package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kable Killers player additions: passthrough, night mode, subtitles on mute and seek thumbnails. */
@Serializable
data class PlayerExtrasSettings(
    /** Send Dolby and DTS audio undecoded to a soundbar or AV receiver. */
    val audioPassthrough: Boolean = false,
    /** Start every video with night mode (dynamic range compression) on. */
    val nightModeByDefault: Boolean = false,
    /** Turn subtitles on while the device is muted, and back off when it is unmuted. */
    val subtitlesOnMute: Boolean = false,
    /** Show a picture of the video above the seek bar while scrubbing. */
    val scrubThumbnails: Boolean = true,
)

internal expect object PlayerExtrasStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

object PlayerExtrasRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(PlayerExtrasSettings())
    val state: StateFlow<PlayerExtrasSettings> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching {
            PlayerExtrasStorage.loadPayload()?.let { json.decodeFromString<PlayerExtrasSettings>(it) }
        }.getOrNull()
        if (stored != null) _state.value = stored
    }

    /** The current settings, loading them first if needed. */
    fun current(): PlayerExtrasSettings {
        ensureLoaded()
        return _state.value
    }

    /** Re-reads the stored settings, for example after switching profiles. */
    fun reload() {
        loaded = false
        _state.value = PlayerExtrasSettings()
        ensureLoaded()
    }

    private fun update(transform: (PlayerExtrasSettings) -> PlayerExtrasSettings) {
        ensureLoaded()
        _state.update(transform)
        runCatching { PlayerExtrasStorage.savePayload(json.encodeToString(PlayerExtrasSettings.serializer(), _state.value)) }
    }

    fun setAudioPassthrough(enabled: Boolean) = update { it.copy(audioPassthrough = enabled) }
    fun setNightModeByDefault(enabled: Boolean) = update { it.copy(nightModeByDefault = enabled) }
    fun setSubtitlesOnMute(enabled: Boolean) = update { it.copy(subtitlesOnMute = enabled) }
    fun setScrubThumbnails(enabled: Boolean) = update { it.copy(scrubThumbnails = enabled) }
}

/** State shared between the player screen and its panels for the current playback session. */
internal object PlayerExtrasRuntime {
    private val _nightModeEnabled = MutableStateFlow(false)
    val nightModeEnabled: StateFlow<Boolean> = _nightModeEnabled.asStateFlow()

    private val _nightModeSupported = MutableStateFlow(false)
    val nightModeSupported: StateFlow<Boolean> = _nightModeSupported.asStateFlow()

    private val _syncByEarOpen = MutableStateFlow(false)
    val syncByEarOpen: StateFlow<Boolean> = _syncByEarOpen.asStateFlow()

    fun setNightMode(enabled: Boolean) { _nightModeEnabled.value = enabled }
    fun setNightModeSupported(supported: Boolean) { _nightModeSupported.value = supported }
    fun openSyncByEar() { _syncByEarOpen.value = true }
    fun closeSyncByEar() { _syncByEarOpen.value = false }
}

/** The stream the seek bar previews frames from. */
internal data class ScrubThumbnailSource(
    val url: String,
    val headers: Map<String, String>,
)

internal val LocalScrubThumbnailSource = staticCompositionLocalOf<ScrubThumbnailSource?> { null }

/** One shared frame reader for the whole player screen. */
internal val LocalScrubThumbnailLoader = staticCompositionLocalOf<ScrubThumbnailLoader?> { null }

/** Fetches still frames of a stream for the seek bar preview. */
internal interface ScrubThumbnailLoader {
    /** A frame near [positionMs], or null when this stream can't provide one. */
    suspend fun frameAt(positionMs: Long): ImageBitmap?

    /** The closest frame already made, returned instantly (no network), or null. */
    fun nearest(positionMs: Long): ImageBitmap? = null
}

@Composable
internal expect fun rememberScrubThumbnailLoader(source: ScrubThumbnailSource?): ScrubThumbnailLoader?

/** True while the device's media volume is at zero (muted). */
@Composable
internal expect fun rememberMediaVolumeMuted(): State<Boolean>

/**
 * The latest seek-preview frame for [positionMs] (null while not seeking). Requests are
 * conflated, not cancelled: a slow network still delivers frames while the finger keeps moving,
 * and the newest finished frame stays on screen until a closer one arrives.
 */
@Composable
internal fun rememberScrubPreviewFrame(positionMs: Long?): ImageBitmap? {
    val loader = LocalScrubThumbnailLoader.current
    val requests = remember { MutableStateFlow<Long?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    SideEffect {
        requests.value = positionMs
        // Show the closest ready thumbnail right away; the exact frame follows.
        if (positionMs != null) loader?.nearest(positionMs)?.let { frame = it }
    }
    LaunchedEffect(loader) {
        val active = loader ?: return@LaunchedEffect
        requests.filterNotNull().conflate().collect { position ->
            active.frameAt(position)?.let { frame = it }
        }
    }
    LaunchedEffect(positionMs == null) {
        if (positionMs == null) frame = null
    }
    return if (positionMs == null) null else frame
}
