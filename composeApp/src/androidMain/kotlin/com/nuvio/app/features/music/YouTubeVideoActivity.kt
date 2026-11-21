package com.nuvio.app.features.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

/**
 * Standalone Android window for YouTube's official, visible embedded player.
 * Keeping WebView outside Compose dialogs avoids offscreen video-surface clipping.
 */
class YouTubeVideoActivity : Activity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.rgb(7, 15, 32)
        window.navigationBarColor = Color.rgb(7, 15, 32)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) {
            finish()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 15, 32))
            setPadding(dp(12), dp(14), dp(12), dp(12))
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 2
        }
        val channel = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
            setTextColor(Color.rgb(25, 168, 255))
            textSize = 14f
        }
        layout.addView(title, LinearLayout.LayoutParams(-1, -2))
        layout.addView(channel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val player = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_NONE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl(
                "https://www.youtube.com/embed/$videoId?playsinline=1",
                mapOf("Referer" to "https://kablekillers.github.io/")
            )
        }
        webView = player
        layout.addView(player, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(16) })

        val close = Button(this).apply {
            text = "Close video"
            setOnClickListener { finish() }
        }
        layout.addView(close, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        setContentView(layout)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHANNEL = "channel"

        internal fun intentFor(activity: android.content.Context, video: YouTubeMusicVideo): Intent =
            Intent(activity, YouTubeVideoActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, video.videoId)
                putExtra(EXTRA_TITLE, video.title)
                putExtra(EXTRA_CHANNEL, video.channel)
            }
    }
}
