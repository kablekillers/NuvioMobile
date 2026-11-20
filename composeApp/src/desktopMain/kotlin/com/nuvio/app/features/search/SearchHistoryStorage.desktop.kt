package com.nuvio.app.features.search

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object SearchHistoryStorage {
    private const val payloadKey = "search_history_payload"
    private const val enabledKey = "recent_searches_enabled"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadEnabled(): Boolean? {
        val defaults = DesktopDefaults
        val key = ProfileScopedKey.of(enabledKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun saveEnabled(enabled: Boolean) {
        DesktopDefaults.setBool(enabled, forKey = ProfileScopedKey.of(enabledKey))
    }
}
