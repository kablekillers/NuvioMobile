package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopDefaults

internal actual object PlatformMdbListSyncStorage : MdbListSyncStorage {
    private const val prefix = "nuvio_mdblist_sync.profile."

    actual override suspend fun load(profileId: Int): String? = DesktopDefaults.stringForKey("$prefix$profileId")

    actual override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        DesktopDefaults.setObject(payload, forKey = "$prefix$profileId")
    }

    actual override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        DesktopDefaults.removeObjectForKey("$prefix$profileId")
    }

    actual fun clearAll() {
        DesktopDefaults.removeKeys { it.startsWith(prefix) }
    }
}
