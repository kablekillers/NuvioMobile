package com.nuvio.app.core.cache

/** Android-only cache management is not exposed on iOS. */
internal actual object KableKillersCacheSettings {
    actual fun autoClearOnStartup(): Boolean = false
    actual fun setAutoClearOnStartup(enabled: Boolean) = Unit
    actual fun smartCleanupEnabled(): Boolean = false
    actual fun setSmartCleanupEnabled(enabled: Boolean) = Unit
    actual fun clearDisposableFiles(): Int = 0
}
