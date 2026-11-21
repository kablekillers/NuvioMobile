package com.nuvio.app.features.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/** Launch native full-window video rendering instead of an embedded Compose dialog. */
@Composable
internal actual fun YouTubeMusicPlayerDialog(video: YouTubeMusicVideo, onClose: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { onClose() }
    )
    LaunchedEffect(video.videoId) {
        launcher.launch(YouTubeVideoActivity.intentFor(context, video))
    }
}
