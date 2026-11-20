package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopDefaults

actual object SyncClientIdentityStorage {
    private const val clientIdKey = "client_instance_id"

    actual fun loadClientId(): String? =
        DesktopDefaults.stringForKey(clientIdKey)

    actual fun saveClientId(clientId: String) {
        DesktopDefaults.setObject(clientId, forKey = clientIdKey)
    }
}
