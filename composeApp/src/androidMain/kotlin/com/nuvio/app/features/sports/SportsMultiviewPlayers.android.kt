package com.nuvio.app.features.sports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Android-only player surface for two independently resolved media URLs.
 * Callers must resolve add-on streams through the existing stream repository
 * before constructing tiles. Never pass a catalog ID as a playback URL.
 */
internal data class SportsMultiviewTile(val id: String, val playbackUrl: String)

@Composable
internal fun SportsMultiviewPlayers(
    tiles: List<SportsMultiviewTile>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlaybackError: (String, String) -> Unit = { _, _ -> },
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tiles.take(2).forEach { tile ->
            SportsMultiviewPlayer(
                tile = tile,
                audible = tile.id == focusedId,
                onFocus = { onFocus(tile.id) },
                onPlaybackError = { message -> onPlaybackError(tile.id, message) },
                modifier = Modifier.weight(1f).height(220.dp),
            )
        }
    }
}

@Composable
private fun SportsMultiviewPlayer(
    tile: SportsMultiviewTile,
    audible: Boolean,
    onFocus: () -> Unit,
    onPlaybackError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(context, tile.id, tile.playbackUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(tile.playbackUrl))
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, onPlaybackError) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error.errorCodeName)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, audible) {
        player.volume = if (audible) 1f else 0f
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = true
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        onDispose {
            player.volume = 0f
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
        update = { view -> view.player = player },
        modifier = modifier
            .border(if (audible) 2.dp else 0.dp, if (audible) Color(0xFF00BFFF) else Color.Transparent)
            .clickable(onClick = onFocus),
    )
}
