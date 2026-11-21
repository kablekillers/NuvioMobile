package com.nuvio.app.features.music

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Android-first audio-only album player. Each track releases MediaPlayer before the next begins. */
@Composable
internal actual fun MusicAlbumPlayerDialog(
    title: String,
    tracks: List<MusicAlbumTrack>,
    onClose: () -> Unit
) {
    if (tracks.isEmpty()) return
    var currentIndex by remember(tracks) { mutableStateOf(0) }
    var playing by remember(tracks) { mutableStateOf(false) }
    var prepared by remember(tracks) { mutableStateOf(false) }
    var error by remember(tracks) { mutableStateOf<String?>(null) }
    var mediaPlayer by remember(tracks) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(currentIndex, tracks) {
        val player = MediaPlayer()
        mediaPlayer = player
        prepared = false
        error = null
        player.setOnPreparedListener {
            prepared = true
            if (playing) it.start()
        }
        player.setOnCompletionListener {
            if (currentIndex < tracks.lastIndex) currentIndex++ else playing = false
        }
        player.setOnErrorListener { _, _, _ ->
            error = "Unable to play this track."
            playing = false
            true
        }
        try {
            player.setDataSource(tracks[currentIndex].url)
            player.prepareAsync()
        } catch (_: Exception) {
            error = "Could not load this track."
        }
        onDispose {
            mediaPlayer = null
            runCatching { player.release() }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF101D33)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = Color(0xFF19A8FF))
            Text("Track ${currentIndex + 1} of ${tracks.size}: ${tracks[currentIndex].name}", color = Color.White)
            error?.let { Text(it, color = Color.White) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = currentIndex > 0, onClick = {
                    currentIndex--
                    playing = true
                }) { Text("Previous") }
                Button(enabled = prepared, onClick = {
                    val player = mediaPlayer ?: return@Button
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        player.start()
                        playing = true
                    }
                }) { Text(if (playing) "Pause" else "Play") }
                Button(enabled = currentIndex < tracks.lastIndex, onClick = {
                    currentIndex++
                    playing = true
                }) { Text("Next") }
            }
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEachIndexed { index, track ->
                    Text(
                        "${index + 1}. ${track.name}",
                        color = if (index == currentIndex) Color(0xFF19A8FF) else Color.White,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (index != currentIndex) {
                                currentIndex = index
                                playing = true
                            } else if (prepared) {
                                mediaPlayer?.seekTo(0)
                                mediaPlayer?.start()
                                playing = true
                            }
                        }.padding(vertical = 6.dp)
                    )
                }
            }
            Button(onClick = onClose) { Text("Close") }
        }
    }
}
