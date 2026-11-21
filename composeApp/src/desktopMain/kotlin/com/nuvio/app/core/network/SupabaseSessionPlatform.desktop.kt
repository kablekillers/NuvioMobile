package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopSecureStore
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

internal actual fun platformAuthSessionManager(): SessionManager? = DesktopAuthSessionManager

/** Keeps the login only in Kable Killers' own DPAPI-encrypted store. */
private object DesktopAuthSessionManager : SessionManager {
    private const val KEY = "supabase.session"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun saveSession(session: UserSession) {
        DesktopSecureStore.put(KEY, json.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession? {
        val raw = DesktopSecureStore.get(KEY) ?: return null
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        DesktopSecureStore.remove(KEY)
    }
}
