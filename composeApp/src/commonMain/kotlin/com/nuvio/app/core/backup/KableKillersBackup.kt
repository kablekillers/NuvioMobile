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
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.SyncHomeCatalogPayload

/**
 * Where the Kable Killers backup Worker lives. Blank turns the backup off.
 * Set after the Worker is deployed (see cloudflare/kk-backup in the NuvioTV repo).
 */
internal object KableKillersBackupConfig {
    const val BASE_URL: String = "https://kable-killers-backup.kablekillers6.workers.dev"
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

/** A backup QR shown while Nuvio is down. */
data class KableKillersBackupQr(
    val code: String,
    val pollToken: String,
    val url: String,
    val expiresInSeconds: Long,
    val pollIntervalSeconds: Int,
)

sealed interface KableKillersBackupQrStatus {
    data object Waiting : KableKillersBackupQrStatus
    data object Denied : KableKillersBackupQrStatus
    data object Expired : KableKillersBackupQrStatus
    data class Approved(val accountEmail: String?) : KableKillersBackupQrStatus
}

/** Result of asking the backup for this device's built-in VPN config. */
sealed interface KableKillersVpnClaim {
    data class Assigned(val configId: Long, val label: String?, val config: String) : KableKillersVpnClaim
    /** The owner hasn't uploaded enough configs; try again later. */
    data object PoolEmpty : KableKillersVpnClaim
    /** This device isn't registered with the backup (yet), or the owner blocked it. */
    data object NotRegistered : KableKillersVpnClaim
}

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
    private const val KEY_APPLIED_SNAPSHOT = "applied_snapshot_id"
    private const val KEY_LOCAL_BASELINE = "local_baseline"
    private const val KEY_EDIT_TOKEN = "edit_token"
    private const val KEY_EDIT_TOKEN_EXPIRES = "edit_token_expires_at"
    private const val FOLLOW_INTERVAL_MS = 2 * 60_000L
    private const val SEND_CHECK_INTERVAL_MS = 30_000L

    private var lastSyncAttemptMs = 0L
    private var lastAutoRestoreMs = 0L
    /** The last check with Nuvio (through the backup) failed: the backup is the source of truth. */
    private var nuvioUnreachable = false
    private var loopsStarted = false

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
        startLoops()
    }

    /**
     * While Nuvio is down (or this phone signed in through the backup), act like Nuvio: apply newer
     * copies of Profile 1 from the backup every couple of minutes (household profiles copy Profile 1,
     * so they follow too), and send Profile 1 changes made on this phone when it may change them.
     */
    private fun startLoops() {
        if (loopsStarted) return
        loopsStarted = true
        scope.launch {
            while (true) {
                if (backupIsSourceOfTruth()) {
                    runCatchingNonCancel { followBackup() }.onFailure { log.w { "Following the backup failed: ${it.message}" } }
                }
                delay(FOLLOW_INTERVAL_MS)
            }
        }
        scope.launch {
            while (true) {
                delay(SEND_CHECK_INTERVAL_MS)
                if (backupIsSourceOfTruth() && canChangeProfileOne()) {
                    runCatchingNonCancel { sendProfileOneIfChanged() }.onFailure { log.w { "Sending Profile 1 to the backup failed: ${it.message}" } }
                }
            }
        }
    }

    private fun signedInToNuvio(): Boolean {
        val account = AuthRepository.state.value as? AuthState.Authenticated ?: return false
        return !account.isAnonymous
    }

    private fun backupIsSourceOfTruth(): Boolean =
        enabled && KableKillersBackupStorage.load(KEY_DEVICE_KEY) != null && (nuvioUnreachable || !signedInToNuvio())

    private fun backupAccountEmail(): String? {
        val account = AuthRepository.state.value as? AuthState.Authenticated
        return account?.takeUnless { it.isAnonymous }?.email?.lowercase() ?: KableKillersBackupStorage.load(KEY_ACCOUNT)?.lowercase()
    }

    /** On the customer login, Profile 1 needs the restore PIN while Nuvio is down; the owner's own login doesn't. */
    fun canChangeProfileOne(): Boolean {
        val email = backupAccountEmail() ?: return false
        if (!email.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)) return true
        return editToken() != null
    }

    /** This phone is connected to the backup for the customer login without a Nuvio sign-in. */
    fun isBackupOnlyCustomer(): Boolean {
        val status = _status.value
        return enabled && status.hasDeviceKey && !signedInToNuvio() &&
            status.accountEmail.equals(KableKillersAccountPolicy.SHARED_CUSTOMER_EMAIL, ignoreCase = true)
    }

    /** Connected to the backup without a Nuvio sign-in (any login). */
    fun isBackupOnly(): Boolean = enabled && _status.value.hasDeviceKey && !signedInToNuvio()

    private fun editToken(): String? {
        val expires = KableKillersBackupStorage.load(KEY_EDIT_TOKEN_EXPIRES)?.toLongOrNull() ?: 0L
        if (expires <= now() / 1000 + 60) return null
        return KableKillersBackupStorage.load(KEY_EDIT_TOKEN)?.takeIf { it.isNotBlank() }
    }

    /** Restore PIN unlock for Profile 1 while Nuvio can't check its own PIN. */
    suspend fun unlockWithRestorePin(pin: String): Result<Unit> = runCatchingNonCancel {
        check(enabled) { "Backup isn't set up in this build" }
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: error("This phone isn't connected to the backup yet")
        val response = post("/v1/unlock", buildJsonObject { put("pin", pin.trim()) }, bearer = key, deviceId = SyncClientIdentity.currentClientId())
        val token = response.string("edit_token") ?: error("Backup server didn't return an unlock")
        KableKillersBackupStorage.save(KEY_EDIT_TOKEN, token)
        KableKillersBackupStorage.save(KEY_EDIT_TOKEN_EXPIRES, response.string("expires_at") ?: (now() / 1000 + 3600).toString())
    }

    private suspend fun followBackup() {
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: return
        val response = try {
            post("/v1/ping", deviceBody(), bearer = key, deviceId = SyncClientIdentity.currentClientId())
        } catch (error: KableKillersBackupException) {
            if (error.status == 401) forgetDeviceKey()
            throw error
        }
        val latest = response.string("snapshot_id")?.toLongOrNull() ?: return
        if (latest != appliedSnapshotId()) applyLatest()
    }

    private fun appliedSnapshotId(): Long = KableKillersBackupStorage.load(KEY_APPLIED_SNAPSHOT)?.toLongOrNull() ?: 0L

    /** Applies the backup's current copy, whichever profile is open (household profiles read Profile 1). */
    suspend fun applyLatest(): KableKillersRestoreSummary = restoreMutex.withLock {
        check(enabled) { "Backup isn't set up in this build" }
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: error("This phone isn't connected to the backup yet")
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
        apply(payload, backupEmail).also { summary ->
            payload.string("snapshot_id")?.let { KableKillersBackupStorage.save(KEY_APPLIED_SNAPSHOT, it) }
            KableKillersBackupStorage.save(KEY_LOCAL_BASELINE, runCatching { fingerprint(localProfileOneSurfaces()) }.getOrNull())
            KableKillersBackupStorage.save(KEY_LAST_RESTORE, now().toString())
            refreshStatus()
            log.i { "Applied backup copy: $summary" }
        }
    }

    /** Profile 1's setup on this phone, in the same shape Nuvio stores it. Only valid while Profile 1 is open. */
    private fun localProfileOneSurfaces(): JsonObject = buildJsonObject {
        put("addons", buildJsonArray {
            AddonRepository.uiState.value.addons.distinctBy { it.manifestUrl }.forEachIndexed { index, addon ->
                addJsonObject {
                    put("url", addon.manifestUrl)
                    put("sort_order", index)
                    put("enabled", addon.enabled)
                    addon.userSetName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                }
            }
        })
        if (AppFeaturePolicy.pluginsEnabled) {
            put("plugins", buildJsonArray {
                PluginRepository.uiState.value.repositories.forEachIndexed { index, repo ->
                    addJsonObject {
                        put("url", repo.manifestUrl)
                        put("name", repo.name)
                        put("enabled", true)
                        put("sort_order", index)
                    }
                }
            })
        }
        put("collections", buildJsonArray {
            addJsonObject {
                put("profile_id", 1)
                put("collections_json", runCatching { json.parseToJsonElement(CollectionRepository.exportToJson()) }.getOrDefault(JsonArray(emptyList())))
            }
        })
        put("home_catalog_settings", buildJsonArray {
            addJsonObject {
                put("profile_id", 1)
                put("settings_json", json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), HomeCatalogSettingsRepository.exportToSyncPayload()))
            }
        })
        put("profile_settings_mobile", buildJsonArray {
            addJsonObject {
                put("profile_id", 1)
                put("settings_json", ProfileSettingsSync.exportActiveProfileForBackup())
            }
        })
    }

    private fun fingerprint(surfaces: JsonObject): String = surfaces.toString().hashCode().toString() + ":" + surfaces.toString().length

    private suspend fun sendProfileOneIfChanged() {
        if (ProfileRepository.activeProfileId != KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX) return
        if (restoreMutex.isLocked) return
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY) ?: return
        val surfaces = localProfileOneSurfaces()
        val print = fingerprint(surfaces)
        val baseline = KableKillersBackupStorage.load(KEY_LOCAL_BASELINE)
        if (baseline == null) {
            KableKillersBackupStorage.save(KEY_LOCAL_BASELINE, print)
            return
        }
        if (print == baseline) return
        val response = post("/v1/push", buildJsonObject {
            put("surfaces", surfaces)
            editToken()?.let { put("edit_token", it) }
        }, bearer = key, deviceId = SyncClientIdentity.currentClientId())
        KableKillersBackupStorage.save(KEY_LOCAL_BASELINE, print)
        response.string("snapshot_id")?.let { KableKillersBackupStorage.save(KEY_APPLIED_SNAPSHOT, it) }
        KableKillersBackupStorage.save(KEY_LAST_BACKUP, now().toString())
        refreshStatus()
        log.i { "Sent Profile 1 changes to the backup" }
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
            backupNow(force = false).onSuccess { nuvioUnreachable = false }.onFailure { error ->
                log.w { "Backup after Nuvio sync failed: ${error.message}" }
                val unreachable = error !is KableKillersBackupException || error.status == 502
                nuvioUnreachable = unreachable
                if (unreachable) {
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
            put("applied_snapshot_id", appliedSnapshotId())
            // Sending outage changes back up to Nuvio is done by the TV app.
            put("can_upload", false)
        }, bearer = token)
        response.string("device_key")?.let { KableKillersBackupStorage.save(KEY_DEVICE_KEY, it) }
        account.email?.let { KableKillersBackupStorage.save(KEY_ACCOUNT, it.lowercase()) }
        KableKillersBackupStorage.save(KEY_LAST_BACKUP, now().toString())
        refreshStatus()
        // Nuvio is back but Profile 1 was changed on the backup during the outage: Nuvio's copy (just
        // pulled onto this phone) is old, so put the backup's copy back until Nuvio catches up.
        if (response["outage_changes"] is JsonObject) {
            runCatchingNonCancel { applyLatest() }.onFailure { log.w { "Re-applying outage changes failed: ${it.message}" } }
        }
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

    /** This device's own VPN config from the owner's pool (each device keeps the one it got). */
    suspend fun claimVpnConfig(): Result<KableKillersVpnClaim> = runCatchingNonCancel {
        if (!enabled) return@runCatchingNonCancel KableKillersVpnClaim.NotRegistered
        val key = KableKillersBackupStorage.load(KEY_DEVICE_KEY)
        if (key.isNullOrBlank()) return@runCatchingNonCancel KableKillersVpnClaim.NotRegistered
        try {
            val response = post("/v1/vpn/claim", buildJsonObject { }, bearer = key, deviceId = SyncClientIdentity.currentClientId())
            val config = response.string("config") ?: error("Backup returned no VPN config")
            val id = (response["config_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            KableKillersVpnClaim.Assigned(configId = id, label = response.string("label"), config = config)
        } catch (error: KableKillersBackupException) {
            when (error.status) {
                404 -> KableKillersVpnClaim.PoolEmpty
                401 -> KableKillersVpnClaim.NotRegistered
                else -> throw error
            }
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

    /** Nuvio can't hand out a QR: ask the backup for one. The owner scans it and approves the phone. */
    suspend fun startBackupQr(): Result<KableKillersBackupQr> = runCatchingNonCancel {
        check(enabled) { "Backup isn't set up in this build" }
        val response = post("/v1/qr/start", deviceBody())
        KableKillersBackupQr(
            code = response.string("code") ?: error("Backup server didn't return a QR code"),
            pollToken = response.string("poll_token") ?: error("Backup server didn't return a QR code"),
            url = response.string("url") ?: error("Backup server didn't return a QR code"),
            expiresInSeconds = response.string("expires_in")?.toLongOrNull() ?: 1800L,
            pollIntervalSeconds = response.string("poll_interval")?.toIntOrNull() ?: 3,
        )
    }

    /** Checks a backup QR. Once approved, this phone's backup key is saved (restore separately). */
    suspend fun pollBackupQr(qr: KableKillersBackupQr): Result<KableKillersBackupQrStatus> = runCatchingNonCancel {
        check(enabled) { "Backup isn't set up in this build" }
        val response = post("/v1/qr/poll", buildJsonObject {
            put("code", qr.code)
            put("poll_token", qr.pollToken)
        })
        when (response.string("status")) {
            "approved" -> {
                val key = response.string("device_key") ?: error("Backup server didn't return a device key")
                val email = response.string("account_email")
                KableKillersBackupStorage.save(KEY_DEVICE_KEY, key)
                email?.let { KableKillersBackupStorage.save(KEY_ACCOUNT, it.lowercase()) }
                refreshStatus()
                KableKillersBackupQrStatus.Approved(email)
            }
            "denied" -> KableKillersBackupQrStatus.Denied
            "expired", "used" -> KableKillersBackupQrStatus.Expired
            else -> KableKillersBackupQrStatus.Waiting
        }
    }

    /** The person signed out on this phone: forget its backup key so getting back in takes a new QR. */
    fun forgetDevice() = forgetDeviceKey()

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
                payload.string("snapshot_id")?.let { id -> KableKillersBackupStorage.save(KEY_APPLIED_SNAPSHOT, id) }
                KableKillersBackupStorage.save(KEY_LOCAL_BASELINE, runCatching { fingerprint(localProfileOneSurfaces()) }.getOrNull())
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
        KableKillersBackupStorage.save(KEY_EDIT_TOKEN, null)
        KableKillersBackupStorage.save(KEY_EDIT_TOKEN_EXPIRES, null)
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
