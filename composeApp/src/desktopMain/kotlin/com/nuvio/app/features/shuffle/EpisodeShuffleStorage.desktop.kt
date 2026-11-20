package com.nuvio.app.features.shuffle

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object EpisodeShuffleStorage {
    actual fun load(profileId: Int): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of("episode_shuffle", profileId))

    actual fun save(profileId: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of("episode_shuffle", profileId))
    }
}
