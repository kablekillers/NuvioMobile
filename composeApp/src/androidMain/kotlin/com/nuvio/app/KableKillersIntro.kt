package com.nuvio.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView

/**
 * Plays the Kable Killers intro clip over the app on cold start.
 *
 * - Shown on every cold start (not when reopening from a link or after rotation).
 * - Muted and does not request audio focus, so it never pauses the user's music.
 * - Tap anywhere to skip.
 */
internal class KableKillersIntro(private val activity: Activity) {
    private var overlay: FrameLayout? = null
    private var videoView: VideoView? = null

    fun playIfDue() {
        play()
    }

    fun dismiss() {
        val view = videoView
        val container = overlay
        videoView = null
        overlay = null
        view?.stopPlayback()
        (container?.parent as? ViewGroup)?.removeView(container)
    }

    private fun play() {
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.kk_intro_skip_description)
            setOnClickListener { dismiss() }
        }
        val view = VideoView(activity).apply {
            setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            }
        }
        overlay = container
        videoView = view

        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        activity.addContentView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        view.setOnCompletionListener { dismiss() }
        view.setOnErrorListener { _, _, _ ->
            dismiss()
            true
        }
        view.setOnPreparedListener { player ->
            player.isLooping = false
            player.setVolume(0f, 0f)
            view.setBackgroundColor(Color.TRANSPARENT)
            view.start()
        }
        try {
            view.setVideoURI(Uri.parse("android.resource://${activity.packageName}/${R.raw.kable_killers_intro}"))
        } catch (_: Exception) {
            dismiss()
        }
    }
}
