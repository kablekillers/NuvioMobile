package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object ResumePromptStorage {
    private const val wasInPlayerKey = "nuvio_resume_prompt_was_in_player"
    private const val lastPlayerVideoIdKey = "nuvio_resume_prompt_last_player_video_id"

    actual fun loadWasInPlayer(): Boolean =
        DesktopDefaults.boolForKey(ProfileScopedKey.of(wasInPlayerKey))

    actual fun saveWasInPlayer(value: Boolean) {
        DesktopDefaults.setBool(value, forKey = ProfileScopedKey.of(wasInPlayerKey))
    }

    actual fun loadLastPlayerVideoId(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(lastPlayerVideoIdKey))

    actual fun saveLastPlayerVideoId(videoId: String?) {
        if (videoId != null) {
            DesktopDefaults.setObject(videoId, forKey = ProfileScopedKey.of(lastPlayerVideoIdKey))
        } else {
            DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(lastPlayerVideoIdKey))
        }
    }
}
