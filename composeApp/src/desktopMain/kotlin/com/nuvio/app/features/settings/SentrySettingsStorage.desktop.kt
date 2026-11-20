package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopDefaults

internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = false
}

internal actual object SentrySettingsStorage {
    private const val enabledKey = "sentry_enabled"

    actual fun loadEnabled(): Boolean? {
        val defaults = DesktopDefaults
        return if (defaults.objectForKey(enabledKey) != null) {
            defaults.boolForKey(enabledKey)
        } else {
            null
        }
    }

    actual fun saveEnabled(enabled: Boolean) {
        DesktopDefaults.setBool(enabled, forKey = enabledKey)
    }
}
