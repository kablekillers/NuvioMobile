package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopSecureStore

internal actual object ServerStorage {
    private const val prefix = "servers."
    actual fun read(key: String): String? = DesktopSecureStore.get(prefix + key)
    actual fun write(key: String, value: String?) = DesktopSecureStore.put(prefix + key, value)
    actual fun clear() = DesktopSecureStore.removeKeys { it.startsWith(prefix) }
}
