package com.nuvio.app.core.backup

import android.content.Context
import android.content.SharedPreferences

internal actual object KableKillersBackupStorage {
    private const val PREFERENCES_NAME = "kable_killers_backup"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun load(key: String): String? = preferences?.getString(key, null)

    actual fun save(key: String, value: String?) {
        preferences?.edit()?.apply {
            if (value == null) remove(key) else putString(key, value)
        }?.apply()
    }
}
