package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import com.nuvio.app.desktop.player.DesktopMpvPlayer
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect

/**
 * Built-in playback on Windows: mpv renders each frame into memory and this
 * surface draws it; the shared player controls sit on top as on the phone.
 */
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: AndroidPlaybackEngine?,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnControllerReady = rememberUpdatedState(onControllerReady)
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    PlayerSettingsRepository.ensureLoaded()
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()

    val playerResult = remember { DesktopMpvPlayer.create() }
    val player = playerResult.getOrNull()
    if (player == null) {
        LaunchedEffect(Unit) {
            latestOnError.value(
                "The video player couldn't start: ${playerResult.exceptionOrNull()?.message.orEmpty()}"
            )
        }
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    val controller = remember(player) { DesktopPlayerController(player) }

    LaunchedEffect(controller, sourceUrl, sourceAudioUrl, sourceHeaders) {
        latestOnControllerReady.value(controller)
    }

    LaunchedEffect(player, sourceUrl, sourceAudioUrl, sourceHeaders, externalSubtitles) {
        player.applySettings(playerSettings)
        withContext(Dispatchers.IO) {
            player.load(
                videoUrl = sourceUrl,
                audioUrl = sourceAudioUrl,
                headers = sanitizePlaybackHeaders(sourceHeaders),
                subtitles = externalSubtitles.map { subtitle ->
                    DesktopMpvPlayer.StartupSubtitle(
                        url = subtitle.url,
                        title = subtitle.name,
                        language = subtitle.language,
                        headers = sanitizePlaybackHeaders(subtitle.headers),
                    )
                },
            )
        }
        if (playWhenReady) player.play() else player.pause()
    }

    LaunchedEffect(player, playWhenReady) {
        if (playWhenReady) player.play() else player.pause()
    }

    LaunchedEffect(player, resizeMode) {
        player.setResizeMode(
            when (resizeMode) {
                PlayerResizeMode.Fit -> 0
                PlayerResizeMode.Fill -> 1
                PlayerResizeMode.Zoom -> 2
            }
        )
    }

    LaunchedEffect(player, playerSettings) {
        player.applySettings(playerSettings)
    }

    LaunchedEffect(player) {
        var lastError: String? = null
        while (isActive) {
            withContext(Dispatchers.IO) { player.refreshPlaybackState() }
            latestOnSnapshot.value(
                PlayerPlaybackSnapshot(
                    isLoading = player.isLoading,
                    isOpening = !player.isFileLoaded,
                    isPlaying = player.isPlaying,
                    isEnded = player.isEnded,
                    durationMs = player.durationMs,
                    positionMs = player.positionMs,
                    bufferedPositionMs = player.bufferedMs,
                    playbackSpeed = player.speed,
                )
            )
            val error = player.errorMessage()
            if (error != lastError) {
                lastError = error
                latestOnError.value(error)
            }
            delay(250L)
        }
    }

    DisposableEffect(player) {
        onDispose { player.destroy() }
    }

    val frame by player.frame.collectAsState()
    Box(modifier = modifier.background(Color.Black)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> player.setViewportSize(size.width, size.height) },
        ) {
            val image = frame ?: return@Canvas
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(size.width, size.height),
                )
            }
        }
    }
}

private fun DesktopMpvPlayer.applySettings(settings: PlayerSettingsUiState) {
    setVideoTuning(
        hardwareDecoding = settings.iosHardwareDecoderMode != IosHardwareDecoderMode.Off,
        deband = settings.iosDebandEnabled,
        brightness = settings.iosBrightness,
        contrast = settings.iosContrast,
        saturation = settings.iosSaturation,
        gamma = settings.iosGamma,
    )
}

private class DesktopPlayerController(private val player: DesktopMpvPlayer) : PlayerEngineController {
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(offsetMs: Long) = player.seekBy(offsetMs)
    override fun retry() = player.retry()
    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)
    override fun setMuted(muted: Boolean) = player.setMuted(muted)

    override fun getAudioTracks(): List<AudioTrack> = player.audioTracks.map { track ->
        AudioTrack(
            index = track.index,
            id = track.id.toString(),
            label = track.title,
            language = track.lang.ifBlank { null },
            isSelected = track.selected,
        )
    }

    override fun getSubtitleTracks(): List<SubtitleTrack> = player.subtitleTracks.map { track ->
        SubtitleTrack(
            index = track.index,
            id = track.id.toString(),
            label = track.title,
            language = track.lang.ifBlank { null },
            isSelected = track.selected,
            isForced = inferForcedSubtitleTrack(
                label = track.title,
                language = track.lang,
                trackId = track.id.toString(),
            ),
        )
    }

    override fun applyAudioLanguagePreferences(languages: List<String>) = player.applyAudioLanguagePreferences(languages)

    override fun selectAudioTrack(index: Int) {
        val track = player.audioTracks.firstOrNull { it.index == index } ?: return
        player.selectAudio(track.id)
    }

    override fun selectSubtitleTrack(index: Int) {
        if (index < 0) {
            player.selectSubtitle(-1)
            return
        }
        val track = player.subtitleTracks.firstOrNull { it.index == index } ?: return
        player.selectSubtitle(track.id)
    }

    override fun setSubtitleUri(url: String) = player.addSubtitleUrl(url)

    override fun clearExternalSubtitle() = player.removeExternalSubtitles()

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        val trackId = player.subtitleTracks.firstOrNull { it.index == trackIndex }?.id ?: -1
        player.removeExternalSubtitles(selectAfter = if (trackIndex < 0) -1 else trackId)
    }

    override fun setSubtitleDelayMs(delayMs: Int) = player.setSubtitleDelayMs(delayMs)

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        player.applySubtitleStyle(
            textColor = style.textColor.toMpvColor(),
            backgroundColor = style.backgroundColor.toMpvColor(),
            outlineColor = style.outlineColor.toMpvColor(),
            outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
            bold = style.bold,
            fontSize = (style.fontSizeSp * 3f).coerceIn(18f, 96f),
            subPos = (100 - (style.bottomOffset / 2)).coerceIn(0, 150),
            stripSdh = style.stripSdh,
        )
    }

    override suspend fun getMediaInfo(): PlayerMediaInfo =
        mpvMediaInfo { name -> player.getString(name)?.ifBlank { null } }

    override suspend fun getChapters(): List<PlayerChapter> =
        mpvChapters { name -> player.getString(name)?.ifBlank { null } }
}

private fun Color.toMpvColor(): String {
    fun hex(component: Float) = (component * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#${hex(alpha)}${hex(red)}${hex(green)}${hex(blue)}"
}
