package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object BingeGroupCacheStorage {
    actual fun load(hashedKey: String): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(hashedKey))

    actual fun save(hashedKey: String, value: String) {
        DesktopDefaults.setObject(value, forKey = ProfileScopedKey.of(hashedKey))
    }

    actual fun remove(hashedKey: String) {
        DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(hashedKey))
    }
}
