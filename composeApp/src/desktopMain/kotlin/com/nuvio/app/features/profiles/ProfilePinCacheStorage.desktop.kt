package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopDefaults

actual object ProfilePinCacheStorage {
    actual fun loadPayload(profileIndex: Int): String? =
        DesktopDefaults.stringForKey(payloadKey(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey(profileIndex))
    }

    actual fun removePayload(profileIndex: Int) {
        DesktopDefaults.removeObjectForKey(payloadKey(profileIndex))
    }

    private fun payloadKey(profileIndex: Int): String = "profile_pin_cache_$profileIndex"
}