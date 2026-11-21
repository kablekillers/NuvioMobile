package com.nuvio.app.features.updater

import android.content.Context
import android.content.SharedPreferences

internal actual object WhatsNewStorage {
    private const val preferencesName = "kk_whats_new"
    private const val lastSeenKey = "last_seen_version"
    private var preferences: SharedPreferences? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun lastSeenVersion(): String? = preferences?.getString(lastSeenKey, null)

    actual fun setLastSeenVersion(version: String) {
        preferences?.edit()?.putString(lastSeenKey, version)?.apply()
    }

    actual fun wasUpdatedSinceInstall(): Boolean {
        val context = appContext ?: return false
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return false
        return info.lastUpdateTime > info.firstInstallTime + 60_000L
    }
}
