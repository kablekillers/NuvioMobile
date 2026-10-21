package com.nuvio.app.core.cache

/** Platform bridge for conservative Kable Killers temporary-file cleanup. */
internal expect object KableKillersCacheSettings {
    fun autoClearOnStartup(): Boolean
    fun setAutoClearOnStartup(enabled: Boolean)
    fun smartCleanupEnabled(): Boolean
    fun setSmartCleanupEnabled(enabled: Boolean)
    fun clearDisposableFiles(): Int
}
