package com.nuvio.app.features.music

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** YouTube's visible, unmodified embedded video player. Does not extract audio. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun YouTubeMusicPlayerDialog(video: YouTubeMusicVideo, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF070F20)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(video.title, color = Color.White)
            Text(video.channel, color = Color(0xFF19A8FF))
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        // Official YouTube embed URL. Keep player controls and branding visible.
                        loadUrl(\n                            "https://www.youtube.com/embed/${video.videoId}?playsinline=1",\n                            mapOf("Referer" to "https://kablekillers.github.io/")\n                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.destroy()
                }
            )
            Button(onClick = onClose) { Text("Close video") }
        }
    }
}
