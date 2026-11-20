package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object SimklSyncStorage {
    private const val PAYLOAD_KEY = "simkl_sync_snapshot"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(PAYLOAD_KEY))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(PAYLOAD_KEY))
    }

    actual fun removeProfile(profileId: Int) {
        DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(PAYLOAD_KEY, profileId))
    }
}
