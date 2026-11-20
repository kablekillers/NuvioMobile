package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopDefaults

actual object ContinueWatchingEnrichmentStorage {
    actual fun loadPayload(key: String): String? =
        DesktopDefaults.stringForKey(key)

    actual fun savePayload(key: String, payload: String) {
        DesktopDefaults.setObject(payload, forKey = key)
    }

    actual fun removePayload(key: String) {
        DesktopDefaults.removeObjectForKey(key)
    }
}
