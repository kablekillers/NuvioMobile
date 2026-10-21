package com.nuvio.app.core.cache

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Android-only cache preferences and conservative temporary-file cleanup.
 *
 * This component does not touch databases, account data, addon configuration,
 * watch history, downloads, or external storage. It is intentionally not wired
 * to startup until the settings UI and lifecycle integration are reviewed.
 */
internal class KableKillersCacheManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences("kable_killers_cache_settings", Context.MODE_PRIVATE)

    var autoClearOnStartup: Boolean
        get() = preferences.getBoolean("auto_clear_on_startup", false)
        set(value) { preferences.edit().putBoolean("auto_clear_on_startup", value).apply() }

    var smartCleanupEnabled: Boolean
        get() = preferences.getBoolean("smart_cleanup_enabled", true)
        set(value) { preferences.edit().putBoolean("smart_cleanup_enabled", value).apply() }

    /**
     * Delete only old files in the app's dedicated disposable temp directory.
     * Never delete active files or anything outside this directory.
     */
    fun cleanOldTemporaryFiles(
        olderThanMillis: Long = 7L * 24 * 60 * 60 * 1000,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        require(olderThanMillis >= 0)
        val directory = File(appContext.cacheDir, "kable_killers_disposable")
        if (!directory.isDirectory) return 0
        val cutoff = nowMillis - olderThanMillis
        var removed = 0
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() > 0L && file.lastModified() < cutoff) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    /** For the future manual "Clear Cache Now" action; limited to disposable files. */
    fun clearDisposableFiles(): Int =
        cleanOldTemporaryFiles(olderThanMillis = 0)
}
