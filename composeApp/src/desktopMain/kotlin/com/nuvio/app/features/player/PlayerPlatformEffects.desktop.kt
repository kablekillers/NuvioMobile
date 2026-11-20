package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.desktop.DesktopWindowState

@Composable
actual fun LockPlayerToLandscape() = Unit

/** The player fills the app window on Windows (fullscreen is toggled separately). */
@Composable
actual fun FullscreenPlayerDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}

@Composable
actual fun HidePlayerSystemBars() = Unit

/** While the player is open the window goes fullscreen, and returns when it closes. */
@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    DisposableEffect(Unit) {
        DesktopWindowState.setFullscreen(true)
        onDispose { DesktopWindowState.setFullscreen(false) }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

@Composable
internal actual fun rememberAudioOutputKey(enabled: Boolean): String? = null

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? = null
