package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.concurrent.thread

/** Starts VLC/mpv and reports back (without a position) when the player closes. */
@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean {
    val latest = rememberUpdatedState(onResult)
    return remember {
        { intentResult: ExternalPlayerIntentResult.Success ->
            val launch = intentResult.intent as? DesktopExternalPlayers.Launch
            if (launch == null) {
                false
            } else {
                runCatching {
                    val process = ProcessBuilder(launch.command).redirectErrorStream(true).start()
                    thread(name = "kk-external-player", isDaemon = true) {
                        runCatching { process.inputStream.use { it.readAllBytes() } }
                        process.waitFor()
                        latest.value(null)
                    }
                    true
                }.getOrDefault(false)
            }
        }
    }
}
