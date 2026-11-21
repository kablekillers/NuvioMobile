package com.nuvio.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import com.nuvio.app.core.backup.KableKillersBackup
import com.nuvio.app.core.storage.DesktopDefaults
import com.nuvio.app.desktop.DesktopAppIcon
import com.nuvio.app.desktop.DesktopUpdatePrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.nuvio.app.desktop.DesktopNotifier
import com.nuvio.app.desktop.DesktopWindowState
import com.nuvio.app.features.autosync.AutoSyncPreferencesDesktop
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage

/** Kable Killers for Windows. */
fun main() {
    AutoSyncPreferencesDesktop.initialize()
    KableKillersBackup.onAppStart()

    application {
        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp))
        val fullscreen by DesktopWindowState.isFullscreen.collectAsState()
        val icon = remember {
            (DesktopAppIcon.awtImage as? BufferedImage)?.let { BitmapPainter(it.toComposeImageBitmap()) }
        }

        // Remember how the window looked before fullscreen (maximized or a
        // normal window, and its size) so leaving the player restores it exactly.
        val beforeFullscreen = remember { arrayOf<Any?>(WindowPlacement.Floating, windowState.size) }

        Window(
            onCloseRequest = {
                DesktopDefaults.synchronize()
                DesktopNotifier.dispose()
                exitApplication()
            },
            state = windowState,
            title = "Kable Killers",
            icon = icon,
            onPreviewKeyEvent = { event ->
                when {
                    DesktopWindowState.playerKeyHandler?.invoke(event) == true -> true
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.F11 -> {
                        DesktopWindowState.setFullscreen(!DesktopWindowState.isFullscreen.value)
                        true
                    }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(fullscreen) {
                if (fullscreen) {
                    if (windowState.placement != WindowPlacement.Fullscreen) {
                        beforeFullscreen[0] = windowState.placement
                        beforeFullscreen[1] = windowState.size
                    }
                    windowState.placement = WindowPlacement.Fullscreen
                } else if (windowState.placement == WindowPlacement.Fullscreen) {
                    val placement = beforeFullscreen[0] as WindowPlacement
                    val size = beforeFullscreen[1] as DpSize
                    windowState.placement = placement
                    delay(80)
                    if (placement == WindowPlacement.Floating) {
                        // Nudge the size so the page re-lays out at the window's real width.
                        windowState.size = DpSize(size.width + 1.dp, size.height)
                        delay(30)
                        windowState.size = size
                    }
                    window.invalidate()
                    window.validate()
                    window.repaint()
                }
            }
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(900, 600)
                window.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) = DesktopWindowState.setActive(true)
                    override fun windowLostFocus(e: WindowEvent?) = DesktopWindowState.setActive(false)
                })
            }
            Box(Modifier.fillMaxSize()) {
                App()
                DesktopUpdatePrompt(exitApp = {
                    DesktopDefaults.synchronize()
                    DesktopNotifier.dispose()
                    exitApplication()
                })
            }
        }
    }
}
