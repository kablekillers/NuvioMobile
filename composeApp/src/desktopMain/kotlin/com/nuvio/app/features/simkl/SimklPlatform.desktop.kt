package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopDefaults
import com.nuvio.app.core.storage.DesktopSecureStore
import com.nuvio.app.core.storage.ProfileScopedKey
import java.security.MessageDigest
import java.security.SecureRandom

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object SimklPkceCrypto {
    private val random = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(random::nextBytes)
    }

    actual fun sha256(value: ByteArray): ByteArray {
        require(value.isNotEmpty())
        return MessageDigest.getInstance("SHA-256").digest(value)
    }
}

internal actual object SimklAuthStorage {
    private const val METADATA_KEY = "simkl_auth_metadata"
    private const val ACCESS_TOKEN_KEY = "simkl_access_token"
    private const val CODE_VERIFIER_KEY = "simkl_code_verifier"

    actual fun loadMetadataPayload(): String? = DesktopDefaults.stringForKey(ProfileScopedKey.of(METADATA_KEY))

    actual fun saveMetadataPayload(payload: String) {
        DesktopDefaults.setObject(payload, forKey = ProfileScopedKey.of(METADATA_KEY))
    }

    actual fun loadAccessToken(): String? = DesktopSecureStore.get(secureKey(ACCESS_TOKEN_KEY))
    actual fun saveAccessToken(value: String?) = DesktopSecureStore.put(secureKey(ACCESS_TOKEN_KEY), value?.takeIf { it.isNotBlank() })
    actual fun loadCodeVerifier(): String? = DesktopSecureStore.get(secureKey(CODE_VERIFIER_KEY))
    actual fun saveCodeVerifier(value: String?) = DesktopSecureStore.put(secureKey(CODE_VERIFIER_KEY), value?.takeIf { it.isNotBlank() })

    actual fun removeProfile(profileId: Int) {
        DesktopDefaults.removeObjectForKey(ProfileScopedKey.of(METADATA_KEY, profileId))
        DesktopSecureStore.remove(secureKey(ACCESS_TOKEN_KEY, profileId))
        DesktopSecureStore.remove(secureKey(CODE_VERIFIER_KEY, profileId))
    }

    private fun secureKey(key: String, profileId: Int? = null): String =
        "simkl." + (profileId?.let { ProfileScopedKey.of(key, it) } ?: ProfileScopedKey.of(key))
}
