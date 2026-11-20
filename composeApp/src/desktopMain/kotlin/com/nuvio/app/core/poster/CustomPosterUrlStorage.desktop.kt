package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object CustomPosterUrlStorage {
    private const val patternKey = "custom_poster_url_pattern"
    private const val enabledScreensKey = "custom_poster_enabled_screens"

    actual fun loadPattern(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(patternKey))

    actual fun savePattern(pattern: String?) {
        if (pattern.isNullOrBlank()) {
            DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(patternKey))
        } else {
            DesktopDefaults.setObject(pattern.trim(), forKey = ProfileScopedKey.of(patternKey))
        }
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadEnabledScreens(): Set<String>? {
        val array = DesktopDefaults.arrayForKey(
            ProfileScopedKey.of(enabledScreensKey)
        ) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveEnabledScreens(keys: Set<String>?) {
        if (keys == null) {
            DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(enabledScreensKey))
        } else {
            DesktopDefaults.setObject(keys.toList(), forKey = ProfileScopedKey.of(enabledScreensKey))
        }
    }
}
