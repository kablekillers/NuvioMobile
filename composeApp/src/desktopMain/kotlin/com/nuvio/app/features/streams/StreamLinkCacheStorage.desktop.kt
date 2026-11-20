package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object StreamLinkCacheStorage {
    actual fun loadEntry(hashedKey: String): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(hashedKey))

    actual fun saveEntry(hashedKey: String, payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(hashedKey))
    }

    actual fun removeEntry(hashedKey: String) {
        DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(hashedKey))
    }
}
