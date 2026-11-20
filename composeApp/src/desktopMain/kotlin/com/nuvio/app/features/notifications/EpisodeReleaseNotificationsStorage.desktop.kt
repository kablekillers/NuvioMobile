package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object EpisodeReleaseNotificationsStorage {
    private const val payloadKey = "episode_release_notifications_payload"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(
            payload,
            forKey = ProfileScopedKey.of(payloadKey),
        )
    }
}