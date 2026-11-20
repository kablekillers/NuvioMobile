package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopDefaults

actual object WatchProgressStorage {
    private const val payloadKey = "watch_progress_payload"

    actual fun loadPayload(profileId: Int): String? =
        DesktopDefaults.stringForKey("${payloadKey}_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        DesktopDefaults.setObject(payload, forKey = "${payloadKey}_$profileId")
    }
}
