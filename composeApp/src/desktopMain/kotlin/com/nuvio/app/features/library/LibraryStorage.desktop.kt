package com.nuvio.app.features.library

import com.nuvio.app.core.storage.DesktopDefaults

actual object LibraryStorage {
    private fun payloadKey(profileId: Int) = "library_payload_$profileId"

    actual fun loadPayload(profileId: Int): String? =
        DesktopDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey(profileId))
    }
}
