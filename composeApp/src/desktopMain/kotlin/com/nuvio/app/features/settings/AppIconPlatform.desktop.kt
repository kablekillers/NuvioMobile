package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopDefaults

// Windows apps can't swap their icon at runtime.
internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = false
    private const val selectedIconKey = "selected_app_icon"

    actual fun loadSelectedIconKey(): String? = DesktopDefaults.stringForKey(selectedIconKey)

    actual fun saveSelectedIconKey(key: String) {
        DesktopDefaults.setObject(key, forKey = selectedIconKey)
    }

    actual fun currentIconName(): String? = null

    actual suspend fun activateIcon(name: String?): Boolean = false
}
