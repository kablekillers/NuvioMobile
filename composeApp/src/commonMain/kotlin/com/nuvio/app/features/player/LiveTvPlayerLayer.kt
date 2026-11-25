package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.LiveTvHost

/**
 * Live TV extras over the player (from Nuvio One M): channel up / down, the channel list,
 * the sleep timer, what's on now, and Live TV's own overlays.
 */
@Composable
internal fun PlayerScreenRuntime.RenderLiveTvLayer(modifier: Modifier = Modifier) {
    val type = contentType ?: parentMetaType
    if (!type.startsWith("livetv", ignoreCase = true)) return
    val channelKey = activeVideoId ?: videoId.orEmpty()
    val isChannel = type.equals("livetv", ignoreCase = true)
    var showPanel by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        LiveTvHost.playerExtras?.invoke(channelKey)

        if (isChannel && LiveTvHost.zap != null) {
            val nowPlaying = LiveTvHost.nowPlaying?.invoke(channelKey)
            AnimatedVisibility(
                visible = controlsVisible && !showPanel,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!nowPlaying.isNullOrBlank()) {
                        Text(
                            text = nowPlaying,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp).padding(start = 8.dp, end = 4.dp),
                        )
                    }
                    IconButton(onClick = { LiveTvHost.zap?.invoke(channelKey, -1) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Previous channel", tint = Color.White)
                    }
                    IconButton(onClick = { LiveTvHost.zap?.invoke(channelKey, 1) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Next channel", tint = Color.White)
                    }
                    if (LiveTvHost.channelPanel != null) {
                        IconButton(onClick = { showPanel = true }) {
                            Icon(Icons.Filled.List, contentDescription = "Channels", tint = Color.White)
                        }
                    }
                    LiveTvHost.askSleep?.let { askSleep ->
                        IconButton(onClick = askSleep) {
                            Icon(Icons.Filled.Bedtime, contentDescription = "Sleep timer", tint = Color.White)
                        }
                    }
                }
            }
        }

        if (showPanel && isChannel) {
            Box(Modifier.fillMaxSize()) {
                LiveTvHost.channelPanel?.invoke(channelKey) { showPanel = false }
            }
        }
    }
}
