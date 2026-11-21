package com.nuvio.app.features.music

/** Kable Killers ships Android only; the iOS build has no music engine. */
internal actual object MusicAudioEngine {
    actual fun play(url: String, headers: Map<String, String>) {}
    actual fun pause() {}
    actual fun resume() {}
    actual fun stop() {}
    actual fun seekTo(positionMs: Long) {}
    actual fun setListener(listener: (Boolean, Boolean, Long, Long, Boolean, String?) -> Unit) {}
}
