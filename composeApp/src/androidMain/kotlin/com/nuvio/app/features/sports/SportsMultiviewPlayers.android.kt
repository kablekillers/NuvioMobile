package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import com.nuvio.app.features.player.playbackMediaItemFromUrl
import com.nuvio.app.features.player.probeMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Gold = Color(0xFFE5B84D)

/**
 * Android grid of up to four independently playing direct links. Only the
 * focused tile is audible. Tiles are keyed by id, so adding or removing one
 * never restarts the others.
 */
@Composable
internal fun SportsMultiviewPlayers(
    tiles: List<SportsMultiviewTile>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = tiles.take(SportsMultiviewSession.MAX_TILES)
    if (visible.isEmpty()) return
    // Each tile is a fraction of the screen, so decoding more than that is wasted work.
    val maxVideoHeight = when {
        visible.size > 2 -> 540
        visible.size == 2 -> 720
        else -> 1080
    }
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        val cols = when {
            visible.size == 1 -> 1
            landscape -> 2
            visible.size == 2 -> 1 // portrait: stack two screens
            else -> 2
        }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                visible.forEach { tile ->
                    key(tile.id) {
                        MultiviewTilePlayer(
                            tile = tile,
                            maxHeight = maxVideoHeight,
                            audible = tile.id == focusedId,
                            onFocus = { onFocus(tile.id) },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val count = measurables.size
            val rows = (count + cols - 1) / cols
            val gap = 4.dp.roundToPx()
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val cellHeight = ((height - gap * (rows - 1)) / rows).coerceAtLeast(0)
            val placed = measurables.mapIndexed { index, measurable ->
                val row = index / cols
                val col = index % cols
                // A lone tile in the last row spans the full width.
                val inRow = minOf(cols, count - row * cols)
                val cellWidth = ((width - gap * (inRow - 1)) / inRow).coerceAtLeast(0)
                Triple(
                    measurable.measure(Constraints.fixed(cellWidth, cellHeight)),
                    col * (cellWidth + gap),
                    row * (cellHeight + gap),
                )
            }
            layout(width, height) {
                placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
            }
        }
    }
}

@Composable
private fun MultiviewTilePlayer(
    tile: SportsMultiviewTile,
    maxHeight: Int,
    audible: Boolean,
    onFocus: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorCount by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val player = remember(tile.id) {
        // Same network stack as the main player: browser User-Agent, the add-on's
        // own headers (Referer, cookies…) and lenient TLS. Plain ExoPlayer defaults
        // get rejected by most live sports hosts.
        val dataSource = PlayerPlaybackNetworking.createDataSourceFactory(context, tile.requestHeaders)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                volume = 0f
                playWhenReady = true
            }
    }
    LaunchedEffect(player, maxHeight) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            .build()
    }
    LaunchedEffect(player, audible) {
        player.volume = if (audible) 1f else 0f
    }
    // Load, and reconnect after a drop (live links often blip). Links without a
    // file extension (most .m3u8 live channels) are probed for their type first.
    LaunchedEffect(player, errorCount) {
        if (errorCount > 0) {
            if (errorCount > 12) return@LaunchedEffect
            delay(minOf(2_000L * errorCount, 15_000L))
        }
        val item = withContext(Dispatchers.IO) {
            val inferred = playbackMediaItemFromUrl(tile.url, tile.responseHeaders, tile.streamType)
            if (inferred.localConfiguration?.mimeType != null) {
                inferred
            } else {
                val probed = runCatching { probeMimeType(tile.url, tile.requestHeaders) }.getOrNull()
                if (probed != null) inferred.buildUpon().setMimeType(probed).build() else inferred
            }
        }
        player.setMediaItem(item)
        player.prepare()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playing = true
                    errorCount = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playing = false
                errorCount++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
    Box(
        Modifier
            .background(Color.Black)
            .border(if (audible) 2.dp else 0.dp, if (audible) Gold else Color.Transparent)
            .clickable(onClick = onFocus),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = (if (audible) "🔊 " else "") + tile.label,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (!playing) {
            Text(
                text = when {
                    errorCount > 12 -> "This link isn't playing. Remove it and pick another."
                    errorCount > 0 -> "Reconnecting…"
                    else -> "Loading…"
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
        }
    }
}
