package com.nuvio.app.core.backup

import platform.Foundation.NSUserDefaults

internal actual object KableKillersBackupStorage {
    private const val PREFIX = "kable_killers_backup."

    actual fun load(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(PREFIX + key)

    actual fun save(key: String, value: String?) {
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(PREFIX + key)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFIX + key)
        }
    }
}
