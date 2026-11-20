package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object ContinueWatchingPreferencesStorage {
    private const val payloadKey = "continue_watching_preferences_payload"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
}
