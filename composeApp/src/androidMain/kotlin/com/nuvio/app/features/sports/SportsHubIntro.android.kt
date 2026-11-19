package com.nuvio.app.features.sports

import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.R
import kotlinx.coroutines.delay

@Composable
internal actual fun SportsHubIntro(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        delay(10_000)
        finish()
    }
    Dialog(
        onDismissRequest = { finish() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        }
                        setOnPreparedListener { player ->
                            player.setVolume(0f, 0f)
                            start()
                        }
                        setOnCompletionListener { finish() }
                        setOnErrorListener { _, _, _ ->
                            finish()
                            true
                        }
                        setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.sports_hub_intro}"))
                    }
                },
                onRelease = { it.stopPlayback() },
            )
            Button(onClick = { finish() }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Text("Skip")
            }
        }
    }
}
