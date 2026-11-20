package com.nuvio.app.features.sports

import com.nuvio.app.core.storage.DesktopDefaults
import com.nuvio.app.desktop.DesktopNotifier

internal actual object SportsPreferencesStorage {
    private const val payloadKey = "kk_sports_prefs"
    actual fun load(): String? = DesktopDefaults.stringForKey(payloadKey)
    actual fun save(payload: String) {
        DesktopDefaults.setObject(payload, forKey = payloadKey)
    }
}

internal actual object SportsReminderPlatform {
    actual suspend fun schedule(id: String, title: String, body: String, triggerAtEpochMillis: Long): Boolean =
        DesktopNotifier.schedule("sports.$id", title, body, triggerAtEpochMillis)

    actual suspend fun cancel(id: String) = DesktopNotifier.cancel("sports.$id")
}
