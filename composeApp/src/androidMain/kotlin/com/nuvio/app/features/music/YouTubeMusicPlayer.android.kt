package com.nuvio.app.features.music

import android.annotation.SuppressLint
import android.view.View
import android.view.WindowManager
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
        // A video surface can be black while audio works if the dialog window is
        // not hardware accelerated. This flag applies to the player dialog only.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) }
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF070F20)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(video.title, color = Color.White)
            Text(video.channel, color = Color(0xFF19A8FF))
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        // Keep Chromium video compositing hardware-backed.
                        setLayerType(View.LAYER_TYPE_NONE, null)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        // Official YouTube embed URL. Keep player controls and branding visible.
                        loadUrl(
                            "https://www.youtube.com/embed/${video.videoId}?playsinline=1",
                            mapOf("Referer" to "https://kablekillers.github.io/")
                        )
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
