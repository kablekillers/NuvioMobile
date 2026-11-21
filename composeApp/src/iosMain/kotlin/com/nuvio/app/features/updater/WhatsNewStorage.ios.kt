package com.nuvio.app.features.updater

import platform.Foundation.NSUserDefaults

internal actual object WhatsNewStorage {
    private const val lastSeenKey = "kk_whats_new_last_seen_version"

    actual fun lastSeenVersion(): String? = NSUserDefaults.standardUserDefaults.stringForKey(lastSeenKey)

    actual fun setLastSeenVersion(version: String) {
        NSUserDefaults.standardUserDefaults.setObject(version, forKey = lastSeenKey)
    }

    actual fun wasUpdatedSinceInstall(): Boolean = false
}
