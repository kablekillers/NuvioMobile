package com.nuvio.app.core.cache

import android.content.Context

internal actual object KableKillersCacheSettings {
    private var manager: KableKillersCacheManager? = null

    fun initialize(context: Context) {
        manager = KableKillersCacheManager(context)
    }

    actual fun autoClearOnStartup(): Boolean = manager?.autoClearOnStartup ?: false
    actual fun setAutoClearOnStartup(enabled: Boolean) {
        manager?.autoClearOnStartup = enabled
    }
    actual fun smartCleanupEnabled(): Boolean = manager?.smartCleanupEnabled ?: true
    actual fun setSmartCleanupEnabled(enabled: Boolean) {
        manager?.smartCleanupEnabled = enabled
    }
    actual fun clearDisposableFiles(): Int = manager?.clearDisposableFiles() ?: 0
}
