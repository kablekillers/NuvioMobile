package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"

    actual fun loadAnonymousUserId(): String? =
        DesktopDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        DesktopDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        DesktopDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }

    actual fun loadFlag(key: String): Boolean =
        DesktopDefaults.boolForKey(key)

    actual fun saveFlag(key: String, value: Boolean) {
        DesktopDefaults.setBool(value, forKey = key)
    }
}
