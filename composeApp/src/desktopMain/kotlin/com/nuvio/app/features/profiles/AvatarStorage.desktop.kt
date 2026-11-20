package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopDefaults

actual object AvatarStorage {
    private const val payloadKey = "avatar_catalog_payload"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey)
    }
}