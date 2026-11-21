package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object PlayerExtrasStorage {
    private const val payloadKey = "player_extras_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
}

/** Kable Killers ships Android only; iOS gets no seek previews. */
@Composable
internal actual fun rememberScrubThumbnailLoader(source: ScrubThumbnailSource?): ScrubThumbnailLoader? = null

@Composable
internal actual fun rememberMediaVolumeMuted(): State<Boolean> = remember { mutableStateOf(false) }
