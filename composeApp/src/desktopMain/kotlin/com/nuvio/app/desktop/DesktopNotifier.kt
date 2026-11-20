package com.nuvio.app.desktop

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Windows notifications through the system tray (they appear as standard
 * Windows toasts). Scheduled ones only fire while the app is running.
 */
object DesktopNotifier {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kk-notifications").apply { isDaemon = true }
    }
    private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @Volatile
    private var trayIcon: TrayIcon? = null

    val isSupported: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    @Synchronized
    private fun icon(): TrayIcon? {
        trayIcon?.let { return it }
        if (!isSupported) return null
        return runCatching {
            val image = DesktopAppIcon.awtImage ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            TrayIcon(image, "Kable Killers").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()?.also { trayIcon = it }
    }

    fun show(title: String, body: String) {
        icon()?.displayMessage(title, body, TrayIcon.MessageType.NONE)
    }

    fun schedule(id: String, title: String, body: String, atEpochMillis: Long): Boolean {
        val delay = atEpochMillis - System.currentTimeMillis()
        if (delay <= 1_000L || !isSupported) return false
        cancel(id)
        scheduled[id] = scheduler.schedule({
            scheduled.remove(id)
            show(title, body)
        }, delay, TimeUnit.MILLISECONDS)
        return true
    }

    fun cancel(id: String) {
        scheduled.remove(id)?.cancel(false)
    }

    fun cancelAll(prefix: String) {
        scheduled.keys.filter { it.startsWith(prefix) }.forEach(::cancel)
    }

    fun dispose() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }
}

/** The app icon, loaded once from resources for the window and tray. */
object DesktopAppIcon {
    val awtImage: java.awt.Image? by lazy {
        runCatching {
            DesktopAppIcon::class.java.getResourceAsStream("/kk_icon.png")?.use { javax.imageio.ImageIO.read(it) }
        }.getOrNull()
    }
}
