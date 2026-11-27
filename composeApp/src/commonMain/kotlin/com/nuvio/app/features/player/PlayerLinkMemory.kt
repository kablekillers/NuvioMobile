package com.nuvio.app.features.player

import com.nuvio.app.features.streams.LinkMemory

/** Notes when the playing link has really played (20 seconds), once per link. */
private object PlayerLinkMemoryTracker {
    var key: Any? = null
    var playingSince: Long = 0L
    var recorded = false
}

private fun nowMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal fun PlayerScreenRuntime.trackLinkMemory(snapshot: PlayerPlaybackSnapshot, playbackKey: Any?) {
    val tracker = PlayerLinkMemoryTracker
    val identity = listOf(playbackKey, activeSourceUrl)
    if (tracker.key != identity) {
        tracker.key = identity
        tracker.playingSince = 0L
        tracker.recorded = false
    }
    if (tracker.recorded) return
    if (!snapshot.isPlaying || snapshot.isLoading) {
        tracker.playingSince = 0L
        return
    }
    val time = nowMillis()
    if (tracker.playingSince == 0L) tracker.playingSince = time
    if (time - tracker.playingSince >= 20_000L) {
        tracker.recorded = true
        LinkMemory.recordSuccess(parentMetaId, title, activeProviderName, activeStreamTitle)
    }
}

internal fun PlayerScreenRuntime.recordLinkFailure() {
    // Only links that never got going count as broken.
    if (PlayerLinkMemoryTracker.recorded) return
    LinkMemory.recordFailure(parentMetaId, title, activeProviderName, activeStreamTitle)
}
