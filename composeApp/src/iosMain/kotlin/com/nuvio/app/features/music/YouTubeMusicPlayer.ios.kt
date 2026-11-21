package com.nuvio.app.features.music

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** iOS playback will be implemented and tested after Android validation. */
@Composable
internal actual fun YouTubeMusicPlayerDialog(video: YouTubeMusicVideo, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(video.title) },
        text = { Text("YouTube embedded playback is currently being tested on Android.") },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
