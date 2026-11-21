package com.nuvio.app.features.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler

/**
 * Shows the mini player above the bottom bar on every tab while music plays, and the full
 * Now Playing screen when it is tapped.
 */
@Composable
fun MusicPlayerHost(bottomPadding: Dp, modifier: Modifier = Modifier) {
    val state by MusicPlayer.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val track = state.current
    if (track == null) {
        expanded = false
        return
    }

    PlatformBackHandler(enabled = expanded) { expanded = false }

    Box(modifier.fillMaxSize()) {
        if (!expanded) {
            MusicMiniPlayer(
                state = state,
                onOpen = { expanded = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 10.dp, end = 10.dp, bottom = bottomPadding + 6.dp)
                    .widthIn(max = 560.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            MusicNowPlayingScreen(state = state, onCollapse = { expanded = false })
        }
    }
}

@Composable
private fun MusicMiniPlayer(state: MusicPlayerState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2101A2C))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArtwork(track.artwork, Modifier.size(44.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    state.error != null -> state.error
                    state.isBuffering -> "Loading…"
                    track.isLive -> "LIVE · ${track.artist}".trimEnd(' ', '·')
                    else -> track.artist
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MusicIconButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", 26.dp) {
                MusicPlayer.togglePlayPause()
            }
            MusicIconButton(Icons.Rounded.SkipNext, "Next", 24.dp, enabled = state.hasNext || state.shuffle) { MusicPlayer.next() }
            MusicIconButton(Icons.Rounded.Close, "Close player", 20.dp) { MusicPlayer.close() }
        }
        if (!track.isLive) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MusicAccent))
            }
        }
    }
}

@Composable
private fun MusicNowPlayingScreen(state: MusicPlayerState, onCollapse: () -> Unit) {
    val track = state.current ?: return
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val spin = rememberInfiniteTransition(label = "vinyl")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "vinylAngle",
    )

    Box(Modifier.fillMaxSize().background(MusicBackground).clickable(enabled = false) {}) {
        // Soft, blurred artwork behind everything.
        if (!track.artwork.isNullOrBlank()) {
            AsyncImage(
                model = track.artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.45f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), MusicBackground.copy(alpha = 0.95f))),
            ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MusicIconButton(Icons.Rounded.KeyboardArrowDown, "Close Now Playing", 30.dp, onClick = onCollapse)
                    Text(
                        "NOW PLAYING · KABLE KILLERS MUSIC",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }
            item(key = "art") {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 16.dp).widthIn(max = 420.dp).aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // A vinyl record peeking out behind the cover while music plays.
                    Box(
                        Modifier
                            .fillMaxSize(0.92f)
                            .align(Alignment.CenterEnd)
                            .rotate(if (state.isPlaying) angle else 0f)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFF2A2A2A), Color(0xFF050505), Color(0xFF1C1C1C)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(MusicAccent))
                    }
                    MusicArtwork(track.artwork, Modifier.fillMaxSize(0.9f).align(Alignment.CenterStart), corner = 18.dp)
                }
            }
            item(key = "titles") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                    Text(track.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (track.artist.isNotBlank()) {
                        Text(track.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    state.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item(key = "progress") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (track.isLive) {
                        Text(
                            "● LIVE",
                            color = Color(0xFFFF5252),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                        )
                    } else {
                        val duration = state.durationMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = scrubbing ?: state.positionMs.toFloat().coerceIn(0f, duration),
                            onValueChange = { scrubbing = it },
                            onValueChangeFinished = {
                                scrubbing?.let { MusicPlayer.seekTo(it.toLong()) }
                                scrubbing = null
                            },
                            valueRange = 0f..duration,
                            enabled = state.durationMs > 0,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MusicAccent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                            ),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text(formatMusicTime((scrubbing?.toLong()) ?: state.positionMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatMusicTime(state.durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                }
            }
            item(key = "controls") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicIconButton(Icons.Rounded.Shuffle, "Shuffle", 24.dp, tint = if (state.shuffle) MusicAccent else Color.White) {
                        MusicPlayer.toggleShuffle()
                    }
                    MusicIconButton(Icons.Rounded.SkipPrevious, "Previous", 38.dp) { MusicPlayer.previous() }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(MusicAccent).clickable { MusicPlayer.togglePlayPause() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play or pause",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    MusicIconButton(Icons.Rounded.SkipNext, "Next", 38.dp, enabled = state.hasNext || state.shuffle) { MusicPlayer.next() }
                    MusicIconButton(
                        if (state.repeat == MusicRepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat",
                        24.dp,
                        tint = if (state.repeat == MusicRepeatMode.Off) Color.White else MusicAccent,
                    ) { MusicPlayer.cycleRepeat() }
                }
            }
            if (state.queue.size > 1) {
                item(key = "upnext") {
                    Text(
                        "Up next",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 24.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(state.queue, key = { i, t -> "q$i:${t.id}" }) { index, queued ->
                    MusicTrackRow(
                        track = queued,
                        isCurrent = index == state.index,
                        onClick = { MusicPlayer.jumpTo(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicIconButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

private fun formatMusicTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
