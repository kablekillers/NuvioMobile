package com.nuvio.app.features.autosync

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.storage.DesktopDefaults

/** AutoSync settings on Windows, stored per profile under the Android key names. */
internal object AutoSyncPreferencesDesktop {
    private const val preferredSubtitleAutoSyncOnStartKey = "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"

    private var installed = false

    fun initialize() {
        if (installed) return
        installed = true
        AutoSyncPreferencesRepository.installPersistence(
            load = { loadBoolean(preferredSubtitleAutoSyncOnStartKey) },
            save = { enabled -> saveBoolean(preferredSubtitleAutoSyncOnStartKey, enabled) },
            loadAggressiveMode = { loadBoolean(aggressiveModeKey) },
            saveAggressiveMode = { enabled -> saveBoolean(aggressiveModeKey, enabled) },
            loadSyncToleranceMs = {
                val defaults = DesktopDefaults
                val key = ProfileScopedKey.of(syncToleranceMsKey)
                if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else null
            },
            saveSyncToleranceMs = { toleranceMs ->
                DesktopDefaults.setInteger(
                    toleranceMs.toLong(),
                    forKey = ProfileScopedKey.of(syncToleranceMsKey),
                )
            },
        )
    }

    private fun loadBoolean(keyBase: String): Boolean? {
        val defaults = DesktopDefaults
        val key = ProfileScopedKey.of(keyBase)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    private fun saveBoolean(keyBase: String, enabled: Boolean) {
        DesktopDefaults.setBool(enabled, forKey = ProfileScopedKey.of(keyBase))
    }
}
