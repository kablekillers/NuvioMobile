package com.nuvio.app.core.backup

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.KableKillersAccountPolicy
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Where the Kable Killers backup Worker lives. Blank turns the backup off.
 * Set after the Worker is deployed (see cloudflare/kk-backup in the NuvioTV repo).
 */
internal object KableKillersBackupConfig {
    const val BASE_URL: String = ""
}

data class KableKillersBackupStatus(
    val hasDeviceKey: Boolean = false,
    val accountEmail: String? = null,
    val lastBackupAtMs: Long = 0L,
    val lastRestoreAtMs: Long = 0L,
)

data class KableKillersRestoreSummary(
    val addons: Int,
    val plugins: Int,
    val collections: Int,
    val homeRows: Int,
    val settings: Boolean,
    val profiles: Int,
)

class KableKillersBackupException(val status: Int, message: String) : Exception(message)

/**
 * Keeps this login's Profile 1 setup copied to the Kable Killers backup on
 * Cloudflare, and restores it from there when Nuvio can't. Same rules as the TV:
 * nothing is written to Nuvio, only Profile 1's add-ons, plugins, collections,
 * Home rows, settings and profile list are restored, and the customer login
 * keeps household profiles local.
 */
object KableKillersBackup {
    private val log = Logger.withTag("KableKillersBackup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val restoreMutex = Mutex()
    private const val SYNC_MIN_INTERVAL_MS = 10 * 60_000L
    private const val AUTO_RESTORE_MIN_INTERVAL_MS = 60 * 60_000L
    private const val KEY_DEVICE_KEY = "device_key"
    private const val KEY_ACCOUNT = "account_email"
    private const val KEY_LAST_BACKUP = "last_backup_at"
    private const val KEY_LAST_RESTORE = "last_restore_at"

    private var lastSyncAttemptMs = 0L
    private var lastAutoRestoreMs = 0L

    private val _status = MutableStateFlow(KableKillersBackupStatus())
    val status: StateFlow<KableKillersBackupStatus> = _status.asStateFlow()

    val enabled: Boolean get() = KableKillersBackupConfig.BASE_URL.isNotBlank()

    private fun baseUrl() = KableKillersBackupConfig.BASE_URL.trim().trimEnd('/')
    private fun now() = EpisodeReleaseDatePlatform.nowEpochMs()

    fun refreshStatus() {
        _status.value = KableKillersBackupStatus(
            hasDeviceKey = !KableKillersBackupStorage.load(KEY_DEVICE_KEY).isNullOrBlank(),
            accountEmail = KableKillersBackupStorage.load(KEY_ACCOUNT),
            lastBackupAtMs = KableKillersBackupStorage.load(KEY_LAST_BACKUP)?.toLongOrNull() ?: 0L,
            lastRestoreAtMs = KableKillersBackupStorage.load(KEY_LAST_RESTORE)?.toLongOrNull() ?: 0L,
        )
    }

    // ---------- Hooks ----------

    fun onAppStart() {
        refreshStatus()
        if (!enabled) return
        scope.launch {
            runCatching { ping() }.onFailure { log.w { "Backup check-in failed: ${it.message}" } }
        }
    }

    /**
     * Called after every full Nuvio sync. Mobile pulls don't report Nuvio outages,
     * so the backup itself is the check: if it can't reach Nuvio either and Profile 1
     * is empty on this phone, Profile 1 is filled in from the backup.
     */
    fun afterNuvioSync() {
        if (!enabled) return
        val time = now()
        if (time - lastSyncAttemptMs < SYNC_MIN_INTERVAL_MS) return
        lastSyncAttemptMs = time
        scope.launch {
            backupNow(force = false).onFailure { error ->
                log.w { "Backup after Nuvio sync failed: ${error.message}" }
                val nuvioUnreachable = error !is KableKillersBackupException || error.status == 502
                if (nuvioUnreachable) {
                    runCatching { autoRestoreIfEmpty() }.onFailure { log.w { "Automatic restore failed: ${it.message}" } }
                }
            }
        }
    }

    // ---------- Actions ----------

    suspend fun backupNow(force: Boolean): Result<Unit> = runCatchingNonCancel {
        check(enabled) { "Backup isn't set up in this build" }
        val account = AuthRepository.state.value as? AuthState.Authenticated
        if (account == null || account.isAnonymous) error("Sign in to Nuvio to back up")
        val token = SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken?.takeIf { it.isNotBlank() }
            ?: error("No Nuvio sign-in on this phone")
        val response = post("/v1/sync", deviceBody {
            KableKillersBackupStorage.load(KEY_DEVICE_KEY)?.let { put("device_key", it) }
            if (force) put("force", true)
        }, bearer = token)
        response.string("device_key")?.let { KableKillersBackupStorage.save(KEY_DEVICE_KEY, it) }
        account.email?.let { KableKillersBackupStorage.save(KEY_ACCOUNT, it.lowercase()) }
        KableKillersBackupStorage.save(KEY_LAST_BACKUP, now().toString())
        refreshStatus()
    }

    suspend fun ping() {
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: return
        try {
            post("/v1/ping", deviceBody(), bearer = key, deviceId = SyncClientIdentity.currentClientId())
        } catch (error: KableKillersBackupException) {
            if (error.status == 401) forgetDeviceKey()
            throw error
        }
    }

    /** New phone while Nuvio is down: prove the login with the owner's restore PIN. */
    suspend fun signInWithRestorePin(email: String, pin: String): Result<Unit> = runCatchingNonCancel {
        check(enabled) { "Backup isn't set up in this build" }
        val normalized = email.trim().lowercase()
        val response = post("/v1/pin-login", deviceBody {
            put("email", normalized)
            put("pin", pin.trim())
        })
        val key = response.string("device_key") ?: error("Backup server didn't return a device key")
        KableKillersBackupStorage.save(KEY_DEVICE_KEY, key)
        KableKillersBackupStorage.save(KEY_ACCOUNT, response.string("account_email") ?: normalized)
        refreshStatus()
    }

    suspend fun restoreNow(): Result<KableKillersRestoreSummary> = runCatchingNonCancel {
        restoreMutex.withLock {
            check(enabled) { "Backup isn't set up in this build" }
            val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: error("This phone isn't connected to the backup yet")
            if (ProfileRepository.activeProfileId != KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX) {
                error("Switch to Profile 1 to restore")
            }
            val payload = try {
                request("GET", "/v1/config", body = "", bearer = key, deviceId = SyncClientIdentity.currentClientId())
            } catch (error: KableKillersBackupException) {
                if (error.status == 401) forgetDeviceKey()
                throw error
            }
            check(payload.string("schema") == "kk-backup-v1") { "Unknown backup format" }
            val backupEmail = payload.string("account_email").orEmpty()
            val signedIn = AuthRepository.state.value as? AuthState.Authenticated
            val signedInEmail = signedIn?.takeUnless { it.isAnonymous }?.email
            if (signedInEmail != null && !signedInEmail.equals(backupEmail, ignoreCase = true)) {
                error("This backup belongs to $backupEmail, not $signedInEmail")
            }
            apply(payload, backupEmail).also {
                KableKillersBackupStorage.save(KEY_LAST_RESTORE, now().toString())
                refreshStatus()
                log.i { "Restored Profile 1 from backup: $it" }
            }
        }
    }

    // ---------- Internals ----------

    private suspend fun autoRestoreIfEmpty() {
        if (KableKillersBackupStorage.load(KEY_DEVICE_KEY) == null) return
        if (ProfileRepository.activeProfileId != KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX) return
        val time = now()
        if (time - lastAutoRestoreMs < AUTO_RESTORE_MIN_INTERVAL_MS) return
        if (AddonRepository.uiState.value.addons.isNotEmpty()) return
        lastAutoRestoreMs = time
        log.i { "Nuvio unreachable and Profile 1 has no add-ons; restoring from backup" }
        restoreNow().getOrThrow()
    }

    private suspend fun apply(payload: JsonObject, backupEmail: String): KableKillersRestoreSummary {
        val surfaces = payload["surfaces"]?.jsonObject ?: error("Backup has no data")
        val profileId = KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX
        val cloudProfiles = !backupEmail.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)

        val profiles = surfaces.array("profiles")?.let { ProfileRepository.restoreFromBackup(it, cloudProfiles) } ?: 0
        val addons = surfaces.array("addons")?.let { AddonRepository.restoreFromBackup(profileId, it) } ?: 0
        val plugins = if (AppFeaturePolicy.pluginsEnabled) {
            surfaces.array("plugins")?.let { PluginRepository.restoreFromBackup(profileId, it) } ?: 0
        } else 0
        val collections = surfaces.firstRowField("collections", "collections_json")
            ?.let { CollectionSyncService.restoreFromBackup(profileId, it) } ?: 0
        val homeRows = (surfaces.firstRowField("home_catalog_settings", "settings_json") as? JsonObject)
            ?.let { HomeCatalogSettingsSyncService.restoreFromBackup(it) } ?: 0
        val settings = (surfaces.firstRowField("profile_settings_mobile", "settings_json") as? JsonObject)
            ?.let { ProfileSettingsSync.restoreFromBackup(profileId, it) } ?: false

        return KableKillersRestoreSummary(addons, plugins, collections, homeRows, settings, profiles)
    }

    private fun forgetDeviceKey() {
        KableKillersBackupStorage.save(KEY_DEVICE_KEY, null)
        refreshStatus()
    }

    private fun deviceBody(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val metadata = currentDeviceClientMetadata()
        return buildJsonObject {
            put("device_id", SyncClientIdentity.currentClientId())
            put("device_name", metadata.deviceName)
            put("device_model", metadata.deviceName)
            put("platform", "mobile")
            put("app_version", AppVersionConfig.VERSION_NAME)
            extra()
        }
    }

    private suspend fun post(path: String, body: JsonObject, bearer: String? = null, deviceId: String? = null): JsonObject =
        request("POST", path, body.toString(), bearer, deviceId)

    private suspend fun request(method: String, path: String, body: String, bearer: String?, deviceId: String?): JsonObject {
        val headers = buildMap {
            put("Accept", "application/json")
            if (method == "POST") put("Content-Type", "application/json")
            if (!bearer.isNullOrBlank()) put("Authorization", "Bearer $bearer")
            if (!deviceId.isNullOrBlank()) put("X-KK-Device-Id", deviceId)
        }
        val response = httpRequestRaw(
            method = method,
            url = baseUrl() + path,
            headers = headers,
            body = body,
            maxResponseBodyBytes = 16 * 1024 * 1024,
        )
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (response.status !in 200..299) {
            val message = parsed?.string("error") ?: "Backup server returned HTTP ${response.status}"
            throw KableKillersBackupException(response.status, message)
        }
        return parsed ?: JsonObject(emptyMap())
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.firstRowField(key: String, field: String): JsonElement? =
        (array(key)?.firstOrNull() as? JsonObject)?.get(field)

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
