package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    actual fun loadPayload(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
}
