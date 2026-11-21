package com.nuvio.app.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Window state shared between the Windows entry point and platform code. */
object DesktopWindowState {
    private val _isActive = MutableStateFlow(true)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    /** Set while the player is open; gets first look at key presses (shortcuts). */
    @Volatile
    var playerKeyHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null

    fun setActive(active: Boolean) { _isActive.value = active }
    fun setFullscreen(fullscreen: Boolean) { _isFullscreen.value = fullscreen }
}
