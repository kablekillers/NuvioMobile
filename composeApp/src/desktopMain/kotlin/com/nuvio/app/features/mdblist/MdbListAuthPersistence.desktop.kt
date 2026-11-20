package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopSecureStore

internal actual object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    private const val prefix = "mdblist.auth."
    actual override fun read(profileId: Int): String? = DesktopSecureStore.get("$prefix$profileId")
    actual override fun write(profileId: Int, value: String?) = DesktopSecureStore.put("$prefix$profileId", value)
    actual override fun clear() = DesktopSecureStore.removeKeys { it.startsWith(prefix) }
}
