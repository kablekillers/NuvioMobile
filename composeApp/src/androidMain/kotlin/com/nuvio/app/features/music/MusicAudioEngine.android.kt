package com.nuvio.app.features.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Audio-only ExoPlayer for the music player. It lives for the whole app process. */
@OptIn(UnstableApi::class)
internal actual object MusicAudioEngine {
    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var listener: ((Boolean, Boolean, Long, Long, Boolean, String?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastError: String? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val ticker = object : Runnable {
        override fun run() {
            report(ended = false)
            if (player?.isPlaying == true) handler.postDelayed(this, 500L)
        }
    }

    private fun ensurePlayer(headers: Map<String, String>): ExoPlayer? {
        player?.let { return it }
        val context = appContext ?: return null
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("KableKillers/Music")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.post(ticker) else report(ended = false)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                report(ended = playbackState == Player.STATE_ENDED)
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = "This track couldn't be played."
                report(ended = false)
            }
        })
        player = exo
        return exo
    }

    private fun report(ended: Boolean) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        listener?.invoke(
            p.isPlaying,
            p.playbackState == Player.STATE_BUFFERING,
            p.currentPosition.coerceAtLeast(0L),
            duration,
            ended,
            lastError,
        )
    }

    actual fun play(url: String, headers: Map<String, String>) {
        val p = ensurePlayer(headers) ?: return
        lastError = null
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    actual fun pause() {
        player?.pause()
    }

    actual fun resume() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
        p.play()
    }

    actual fun stop() {
        handler.removeCallbacks(ticker)
        player?.stop()
        player?.clearMediaItems()
        report(ended = false)
    }

    actual fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    actual fun setListener(listener: (Boolean, Boolean, Long, Long, Boolean, String?) -> Unit) {
        this.listener = listener
    }
}
