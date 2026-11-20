package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object TraktSettingsStorage {
    private const val payloadKey = "trakt_settings_payload"
    private const val pendingWatchProgressSourceKey = "pending_watch_progress_source"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadPendingWatchProgressSourcePayload(profileId: Int): String? =
        DesktopDefaults.stringForKey(
            ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
        )

    actual fun savePendingWatchProgressSourcePayload(profileId: Int, payload: String) {
        DesktopDefaults.setObject(
            payload,
            forKey = ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
        )
    }

    actual fun clearPendingWatchProgressSourcePayload(profileId: Int) {
        DesktopDefaults.removeObjectForKey(
            ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
        )
    }
}
