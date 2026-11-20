package com.nuvio.app.features.membership

import com.nuvio.app.core.storage.DesktopDefaults
import com.nuvio.app.core.storage.DesktopPaths
import java.io.File

internal actual object MemberAssetStorage {
    private const val accessPayloadKey = "member_access_payload"
    private const val backgroundCatalogPayloadKey = "member_background_catalog_payload"
    private val backgroundDirectory: File get() = File(DesktopPaths.cacheDir, "NuvioMemberBackgrounds")
    private val avatarDirectory: File get() = File(DesktopPaths.cacheDir, "NuvioMemberAvatars")

    actual fun loadAccessPayload(): String? = DesktopDefaults.stringForKey(accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = accessPayloadKey)
    }

    actual fun loadProfileBackgroundCatalogPayload(): String? =
        DesktopDefaults.stringForKey(backgroundCatalogPayloadKey)

    actual fun saveProfileBackgroundCatalogPayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = backgroundCatalogPayloadKey)
    }

    actual fun loadProfileBackground(cacheKey: String): ByteArray? =
        File(backgroundDirectory, "${safeKey(cacheKey)}.png").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    actual fun saveProfileBackground(cacheKey: String, bytes: ByteArray) {
        write(File(backgroundDirectory, "${safeKey(cacheKey)}.png"), bytes)
    }

    actual fun loadProfileAvatar(cacheKey: String): String? =
        File(avatarDirectory, safeKey(cacheKey)).takeIf { it.isFile }?.toURI()?.toString()

    actual fun saveProfileAvatar(cacheKey: String, bytes: ByteArray): String? {
        val file = File(avatarDirectory, safeKey(cacheKey))
        return if (write(file, bytes)) file.toURI().toString() else null
    }

    actual fun clearAccess() {
        DesktopDefaults.removeObjectForKey(accessPayloadKey)
    }

    private fun write(file: File, bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    private fun safeKey(key: String): String = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
