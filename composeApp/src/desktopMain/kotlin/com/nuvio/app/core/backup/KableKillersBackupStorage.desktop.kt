package com.nuvio.app.core.backup

import com.nuvio.app.core.storage.DesktopDefaults

internal actual object KableKillersBackupStorage {
    private const val PREFIX = "kable_killers_backup."

    actual fun load(key: String): String? =
        DesktopDefaults.stringForKey(PREFIX + key)

    actual fun save(key: String, value: String?) {
        if (value == null) {
            DesktopDefaults.removeObjectForKey(PREFIX + key)
        } else {
            DesktopDefaults.setObject(value, forKey = PREFIX + key)
        }
    }
}
