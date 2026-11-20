package com.nuvio.app.features.search

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

actual object DiscoverSelectionStorage {
    private const val catalogKey = "discover_catalog_key"

    actual fun loadCatalogKey(): String? =
        DesktopDefaults.stringForKey(ProfileScopedKey.of(catalogKey))

    actual fun saveCatalogKey(catalogKey: String) {
        DesktopDefaults.setObject(
            catalogKey,
            forKey = ProfileScopedKey.of(DiscoverSelectionStorage.catalogKey),
        )
    }
}
