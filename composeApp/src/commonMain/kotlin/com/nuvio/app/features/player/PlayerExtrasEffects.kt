package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/** Night mode, subtitles on mute and Sync by ear for the current player session. */
@Composable
internal fun PlayerScreenRuntime.BindPlayerExtrasEffects() {
    val runtime = this
    val extras by remember {
        PlayerExtrasRepository.ensureLoaded()
        PlayerExtrasRepository.state
    }.collectAsState()
    val nightMode by PlayerExtrasRuntime.nightModeEnabled.collectAsState()
    val syncByEarOpen by PlayerExtrasRuntime.syncByEarOpen.collectAsState()

    // Each video starts with night mode at the saved default.
    LaunchedEffect(Unit) {
        PlayerExtrasRepository.reload() // settings are per profile
        PlayerExtrasRuntime.setNightMode(PlayerExtrasRepository.state.value.nightModeByDefault)
        PlayerExtrasRuntime.closeSyncByEar()
    }

    LaunchedEffect(playerController) {
        PlayerExtrasRuntime.setNightModeSupported(playerController?.supportsNightMode == true)
    }

    LaunchedEffect(playerController, nightMode) {
        playerController?.setNightModeEnabled(nightMode)
    }

    // Sync by ear needs the video visible, so it closes the subtitle panel.
    LaunchedEffect(syncByEarOpen) {
        if (syncByEarOpen) runtime.showSubtitleModal = false
    }

    // Subtitles on mute: turn the preferred subtitle on while muted, and back off afterwards.
    val muted by rememberMediaVolumeMuted()
    var autoEnabledIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(muted, extras.subtitlesOnMute, subtitleTracks, playerController) {
        val controller = playerController ?: return@LaunchedEffect
        if (muted && extras.subtitlesOnMute) {
            val nothingShowing = selectedSubtitleIndex < 0 && !useCustomSubtitles
            if (nothingShowing && subtitleTracks.isNotEmpty() && autoEnabledIndex == null) {
                val preferred = PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage
                val index = subtitleTracks.indexOfFirst { track ->
                    !track.language.isNullOrBlank() && track.language.equals(preferred, ignoreCase = true)
                }.takeIf { it >= 0 } ?: subtitleTracks.indexOfFirst { !it.isForced }.takeIf { it >= 0 } ?: 0
                val trackIndex = subtitleTracks[index].index
                controller.selectSubtitleTrack(trackIndex)
                selectedSubtitleIndex = trackIndex
                autoEnabledIndex = trackIndex
            }
        } else {
            val auto = autoEnabledIndex
            if (auto != null) {
                // Only undo it if the viewer didn't pick something else in the meantime.
                if (selectedSubtitleIndex == auto && !useCustomSubtitles) {
                    controller.selectSubtitleTrack(-1)
                    selectedSubtitleIndex = -1
                }
                autoEnabledIndex = null
            }
        }
    }
}

/**
 * Sync by ear: tap "Heard" when you hear a line and "Saw" when its subtitle appears, in either
 * order. The gap between the two taps becomes the subtitle delay.
 */
@Composable
internal fun PlayerScreenRuntime.PlayerExtrasOverlay() {
    val open by PlayerExtrasRuntime.syncByEarOpen.collectAsState()
    var heardAtMs by remember { mutableStateOf<Long?>(null) }
    var sawAtMs by remember { mutableStateOf<Long?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(open) {
        if (open) {
            heardAtMs = null
            sawAtMs = null
            result = null
        }
    }

    fun finishIfReady() {
        val heard = heardAtMs ?: return
        val saw = sawAtMs ?: return
        // Subtitle shown before the line was heard (saw < heard) means it is early: delay it.
        val newDelay = (subtitleDelayMs + (heard - saw).toInt())
            .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        setSubtitleDelay(newDelay)
        result = if (newDelay == 0) "Now: in sync" else "Subtitle delay set to ${formatDelay(newDelay)}"
        heardAtMs = null
        sawAtMs = null
    }

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .padding(bottom = 36.dp, start = 24.dp, end = 24.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sync by ear", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    text = when {
                        result != null -> result!!
                        heardAtMs != null -> "Heard captured. Tap Saw when its subtitle shows."
                        sawAtMs != null -> "Saw captured. Tap Heard when you hear the line."
                        else -> "Tap when you hear a line and when its subtitle shows, in either order."
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SyncByEarButton(
                        label = "Heard",
                        captured = heardAtMs != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        result = null
                        heardAtMs = playbackSnapshot.positionMs
                        finishIfReady()
                    }
                    SyncByEarButton(
                        label = "Saw",
                        captured = sawAtMs != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        result = null
                        sawAtMs = playbackSnapshot.positionMs
                        finishIfReady()
                    }
                }
                Text(
                    text = "Done",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { PlayerExtrasRuntime.closeSyncByEar() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncByEarButton(
    label: String,
    captured: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (captured) tokens.colors.accent else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (captured) tokens.colors.onAccent else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

private fun formatDelay(delayMs: Int): String {
    val sign = if (delayMs > 0) "+" else if (delayMs < 0) "-" else ""
    val abs = kotlin.math.abs(delayMs)
    val seconds = abs / 1000
    val tenths = (abs % 1000) / 100
    return "$sign$seconds.${tenths}s"
}
