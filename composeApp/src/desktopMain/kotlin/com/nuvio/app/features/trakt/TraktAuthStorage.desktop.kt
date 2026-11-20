package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object TraktAuthStorage {
    private const val payloadKey = "trakt_auth_payload"

    actual fun loadPayload(profileId: Int): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey, profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey, profileId))
    }

    actual fun removeProfile(profileId: Int) {
        DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(payloadKey, profileId))
    }
}
