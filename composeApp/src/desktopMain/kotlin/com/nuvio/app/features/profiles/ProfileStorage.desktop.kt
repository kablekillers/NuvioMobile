package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopDefaults

actual object ProfileStorage {
    private const val payloadKey = "profile_payload"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey)
    }
}