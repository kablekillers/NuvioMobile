package com.nuvio.app.features.watched

import com.nuvio.app.core.storage.DesktopDefaults

actual object WatchedStorage {
    private fun payloadKey(profileId: Int) = "watched_payload_$profileId"

    actual fun loadPayload(profileId: Int): String? =
        DesktopDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey(profileId))
    }
}

