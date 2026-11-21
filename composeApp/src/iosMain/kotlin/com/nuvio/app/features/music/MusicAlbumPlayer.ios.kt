package com.nuvio.app.features.music

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Android Mobile is the first playback target. iOS playback follows after Android validation. */
@Composable
internal actual fun MusicAlbumPlayerDialog(
    title: String,
    tracks: List<MusicAlbumTrack>,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text("Full album playback is currently being tested on Android.") },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
