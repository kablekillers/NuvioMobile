package com.nuvio.app.core.storage

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64
import java.util.Properties

/**
 * Windows replacement for the iOS Keychain. Values (login and service tokens)
 * are encrypted with Windows DPAPI, so only this Windows user account can read
 * them, and kept in %APPDATA%\KableKillers\secure.properties.
 * On non-Windows systems (used only for development) values are base64 only.
 */
object DesktopSecureStore {
    private val lock = Any()
    private val file: File get() = File(DesktopPaths.appDataDir, "secure.properties")
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val props: Properties by lazy {
        Properties().apply { runCatching { file.takeIf { it.exists() }?.inputStream()?.use(::load) } }
    }

    fun get(key: String): String? = synchronized(lock) {
        val encoded = props.getProperty(key) ?: return null
        runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            (if (isWindows) Crypt32Util.cryptUnprotectData(bytes) else bytes).decodeToString()
        }.getOrNull()
    }

    fun put(key: String, value: String?) = synchronized(lock) {
        if (value == null) {
            props.remove(key)
        } else {
            val bytes = value.encodeToByteArray()
            val sealed = if (isWindows) Crypt32Util.cryptProtectData(bytes) else bytes
            props.setProperty(key, Base64.getEncoder().encodeToString(sealed))
        }
        save()
    }

    fun remove(key: String) = put(key, null)

    fun removeKeys(predicate: (String) -> Boolean) = synchronized(lock) {
        props.stringPropertyNames().filter(predicate).forEach(props::remove)
        save()
    }

    fun clear() = synchronized(lock) {
        props.clear()
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { props.store(it, null) }
            java.nio.file.Files.move(
                temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
