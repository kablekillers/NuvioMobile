package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.isAnonymous
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

@Serializable
private data class StoredProfilePayload(
    val userId: String,
    val activeProfileIndex: Int = 1,
    val hasEverSelectedProfile: Boolean = false,
    val rememberLastProfileEnabled: Boolean = false,
    val profiles: List<NuvioProfile> = emptyList(),
)

object ProfileRepository {
    private const val HOUSEHOLD_PIN_KEY_OFFSET = 100
    private const val HOUSEHOLD_PIN_MAX_ATTEMPTS = 5
    private const val HOUSEHOLD_PIN_LOCKOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private var activeProfileIndex: Int = 1
    private var loadedCacheForUserId: String? = null

    val activeProfileId: Int get() = activeProfileIndex

    fun setRememberLastProfileEnabled(enabled: Boolean) {
        if (_state.value.rememberLastProfileEnabled == enabled) return

        _state.value = _state.value.copy(rememberLastProfileEnabled = enabled)
        persist()
    }

    fun loadCachedProfiles(): Boolean {
        val stored = decodeStoredPayload() ?: return false
        loadedCacheForUserId = stored.userId
        applyStoredPayload(stored)
        ThemeSettingsRepository.onProfileChanged()
        return _state.value.profiles.isNotEmpty()
    }

    fun ensureLoaded(userId: String) {
        if (loadedCacheForUserId == userId && _state.value.isLoaded) return

        val stored = decodeStoredPayload()
        loadedCacheForUserId = userId
        if (stored == null) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        if (stored.userId != userId) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        applyStoredPayload(stored)
    }

    fun clearInMemory() {
        loadedCacheForUserId = null
        activeProfileIndex = 1
        _state.value = ProfileState()
    }

    suspend fun pullProfiles() {
        if (AuthRepository.state.value.isAnonymous) {
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
            return
        }
        try {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profiles")
            val remoteProfiles = result.decodeList<NuvioProfile>()
            val profiles = if (KableKillersAccountPolicy.isHouseholdAccount()) {
                householdProfiles(remoteProfiles)
            } else {
                remoteProfiles
            }
            _state.value = _state.value.copy(
                profiles = profiles.sortedBy { it.profileIndex },
                isLoaded = true,
                activeProfile = profiles.find { it.profileIndex == activeProfileIndex }
                    ?: profiles.firstOrNull(),
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile pull")) return
            log.e(e) { "Failed to pull profiles" }
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
        }
    }

    /**
     * Kable Killers backup restore of the profile list. Logins with cloud profiles
     * get their whole list back; the shared customer login keeps household profiles
     * local and only takes Profile 1's name and look (same as a normal pull).
     */
    internal fun restoreFromBackup(rows: JsonArray, cloudProfiles: Boolean): Int {
        val remoteProfiles = runCatching {
            json.decodeFromJsonElement(ListSerializer(NuvioProfile.serializer()), rows)
        }.getOrNull().orEmpty()
        if (remoteProfiles.none { it.profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX }) return 0
        val profiles = if (cloudProfiles) {
            val restoredIds = remoteProfiles.map { it.profileIndex }.toSet()
            val localOnly = _state.value.profiles.filter { it.profileIndex !in restoredIds }
            (remoteProfiles + localOnly).take(MAX_PROFILES)
        } else {
            // Customer login: Profile 1 is the owner's and stays locked on customer phones.
            householdProfiles(remoteProfiles).map {
                if (it.profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX) it.copy(pinEnabled = true) else it
            }
        }
        _state.value = _state.value.copy(
            profiles = profiles.sortedBy { it.profileIndex },
            isLoaded = true,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
        )
        _state.value.activeProfile?.let { activeProfileIndex = it.profileIndex }
        persist()
        return if (cloudProfiles) remoteProfiles.size else 1
    }

    fun selectProfile(profileIndex: Int) {
        activeProfileIndex = profileIndex
        CustomPosterUrlRepository.onProfileChanged()
        val selectedProfile = _state.value.profiles.find { it.profileIndex == profileIndex }
        _state.value = _state.value.copy(
            activeProfile = selectedProfile,
            hasEverSelectedProfile = selectedProfile != null || _state.value.hasEverSelectedProfile,
        )
        persist()
        WatchedRepository.onProfileChanged(profileIndex)
        TrackingSettingsRepository.onProfileChanged()
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.onProfileChanged()
        LibraryRepository.onProfileChanged(profileIndex)
        com.nuvio.app.features.calendar.UpcomingEpisodesRepository.onProfileChanged()
        LibraryDisplaySettingsRepository.onProfileChanged()
        WatchProgressRepository.onProfileChanged(profileIndex)
        AddonRepository.onProfileChanged(profileIndex)
        if (com.nuvio.app.core.build.AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.onProfileChanged(profileIndex)
        }
        ThemeSettingsRepository.onProfileChanged()
        PosterCardStyleRepository.onProfileChanged()
        CardDepthStyleRepository.onProfileChanged()
        PlayerSettingsRepository.onProfileChanged()
        StreamBadgeSettingsRepository.onProfileChanged()
        P2pSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.onProfileChanged()
        HomeRepository.clear()
        MetaScreenSettingsRepository.onProfileChanged()
        com.nuvio.app.features.shuffle.EpisodeShuffleRepository.onProfileChanged()
        ContinueWatchingPreferencesRepository.onProfileChanged()
        com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache.onProfileChanged()
        EpisodeReleaseNotificationsRepository.onProfileChanged()
        TmdbSettingsRepository.onProfileChanged()
        MdbListSettingsRepository.onProfileChanged()
        SearchHistoryRepository.onProfileChanged()
        SearchRepository.reset()
        CollectionRepository.onProfileChanged()
        CollectionMobileSettingsRepository.onProfileChanged()
        DownloadsRepository.onProfileChanged()
        ServerRepository.onProfileChanged()
        ProfileSettingsSync.onProfileChanged()
    }

    suspend fun pushProfiles(profiles: List<ProfilePushPayload>) {
        // Customer account: household profiles stay on this phone. Nothing about
        // them (or Profile 1) is uploaded, same as the TV app.
        if (AuthRepository.state.value.isAnonymous || KableKillersAccountPolicy.isHouseholdAccount()) {
            applyPayloadsLocally(profiles)
            return
        }
        try {
            val params = buildJsonObject {
                put("p_client_max_profiles", MAX_PROFILES)
                put("p_profiles", json.encodeToJsonElement(profiles))
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_profiles", params)
            pullProfiles()
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile push")) return
            log.e(e) { "Failed to push profiles" }
        }
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        avatarUrl: String? = null,
        usesPrimaryAddons: Boolean = false,
    ) {
        val existing = _state.value.profiles
        val nextIndex = ((1..MAX_PROFILES).toSet() - existing.map { it.profileIndex }.toSet()).minOrNull() ?: return
        // A reused profile number must never inherit a deleted profile's device PIN.
        ProfilePinCacheStorage.removePayload(householdPinKey(nextIndex))

        val allPayloads = existing.map { profile ->
            ProfilePushPayload(
                profileIndex = profile.profileIndex,
                name = profile.name,
                avatarColorHex = profile.avatarColorHex,
                usesPrimaryAddons = profile.usesPrimaryAddons,
                usesPrimaryPlugins = profile.usesPrimaryPlugins,
                avatarId = profile.avatarId,
                avatarUrl = profile.avatarUrl,
                profileBackgroundId = profile.profileBackgroundId,
                profileBackgroundUrl = profile.profileBackgroundUrl,
            )
        } + ProfilePushPayload(
            profileIndex = nextIndex,
            name = name,
            avatarColorHex = avatarColorHex,
            // Kable Killers: every new profile copies Profile 1's add-ons and plugins.
            usesPrimaryAddons = true,
            usesPrimaryPlugins = true,
            avatarId = avatarId,
            avatarUrl = avatarUrl,
        )

        pushProfiles(allPayloads)
    }

    /**
     * Customer account list: Profile 1 from the cloud plus the profiles that
     * live on this phone. The first time, profiles that came from the old shared
     * cloud list are dropped except the one this phone is using.
     */
    private fun householdProfiles(remoteProfiles: List<NuvioProfile>): List<NuvioProfile> {
        val authState = AuthRepository.state.value as? AuthState.Authenticated
        val primaryIndex = KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX
        var local = _state.value.profiles
        val migrationKey = authState?.let { "kk_household_profiles_${it.userId}" }
        if (migrationKey != null && !AuthStorage.loadFlag(migrationKey)) {
            local = local.filter { it.profileIndex == primaryIndex || it.profileIndex == activeProfileIndex }
                .map { it.copy(usesPrimaryAddons = true, usesPrimaryPlugins = true) }
            AuthStorage.saveFlag(migrationKey, true)
        }
        val primary = remoteProfiles.find { it.profileIndex == primaryIndex }
            ?: local.find { it.profileIndex == primaryIndex }
            ?: remoteProfiles.firstOrNull()?.copy(profileIndex = primaryIndex)
        val others = local.filter { it.profileIndex != primaryIndex }
        return (listOfNotNull(primary) + others).sortedBy { it.profileIndex }
    }

    suspend fun updateProfile(
        profileIndex: Int,
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        avatarUrl: String? = null,
        profileBackgroundId: String? = null,
        profileBackgroundUrl: String? = null,
        usesPrimaryAddons: Boolean = false,
    ) {
        val allPayloads = _state.value.profiles.map { profile ->
            if (profile.profileIndex == profileIndex) {
                ProfilePushPayload(
                    profileIndex = profileIndex,
                    name = name,
                    avatarColorHex = avatarColorHex,
                    // Profiles other than Profile 1 always keep using its add-ons.
                    usesPrimaryAddons = usesPrimaryAddons || profileIndex != 1,
                    usesPrimaryPlugins = profileIndex != 1,
                    avatarId = avatarId,
                    avatarUrl = avatarUrl,
                    profileBackgroundId = profileBackgroundId,
                    profileBackgroundUrl = profileBackgroundUrl,
                )
            } else {
                ProfilePushPayload(
                    profileIndex = profile.profileIndex,
                    name = profile.name,
                    avatarColorHex = profile.avatarColorHex,
                    usesPrimaryAddons = profile.usesPrimaryAddons,
                    usesPrimaryPlugins = profile.usesPrimaryPlugins,
                    avatarId = profile.avatarId,
                    avatarUrl = profile.avatarUrl,
                    profileBackgroundId = profile.profileBackgroundId,
                    profileBackgroundUrl = profile.profileBackgroundUrl,
                )
            }
        }

        pushProfiles(allPayloads)
    }

    suspend fun deleteProfile(profileIndex: Int) {
        // Customer account: deleting a household profile must not touch the
        // shared cloud data that other phones may use under the same index.
        if (AuthRepository.state.value.isAnonymous || KableKillersAccountPolicy.isHouseholdAccount()) {
            if (profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX) return
            val remaining = _state.value.profiles.filter { it.profileIndex != profileIndex }
            ProfilePinCacheStorage.removePayload(profileIndex)
            ProfilePinCacheStorage.removePayload(householdPinKey(profileIndex))
            ServerRepository.removeProfile(profileIndex)
            _state.value = _state.value.copy(
                profiles = remaining,
                activeProfile = if (_state.value.activeProfile?.profileIndex == profileIndex) remaining.firstOrNull() else _state.value.activeProfile,
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
            return
        }
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_delete_profile_data", params)
            ServerRepository.removeProfile(profileIndex)
            pullProfiles()
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile delete")) return
            log.e(e) { "Failed to delete profile $profileIndex" }
        }
    }

    suspend fun verifyPin(profileIndex: Int, pin: String): PinVerifyResult {
        if (KableKillersAccountPolicy.isHouseholdProfile(profileIndex)) {
            return verifyHouseholdPinOrOwner(profileIndex, pin)
        }
        // Nuvio checks Profile 1's PIN on its own servers. On a phone signed in only through the
        // Kable Killers backup, the owner's restore PIN opens Profile 1 (and allows sending changes).
        if (profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX &&
            com.nuvio.app.core.backup.KableKillersBackup.isBackupOnly()
        ) {
            return verifyRestorePin(pin)
        }
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return verifyPinLocally(profileIndex, pin)
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
            }
            val result = SupabaseProvider.client.postgrest.rpc("verify_profile_pin", params)
            result.decodeSingle<PinVerifyResult>().also { verifyResult ->
                if (verifyResult.unlocked) {
                    pullProfiles()
                    rememberVerifiedPin(profileIndex = profileIndex, pin = pin)
                }
            }
        }.getOrElse { e ->
            log.e(e) { "Failed to verify pin" }
            val local = verifyPinLocally(profileIndex, pin)
            // Nuvio unreachable: the owner's restore PIN also opens Profile 1 through the backup.
            if (!local.unlocked && profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX &&
                com.nuvio.app.core.backup.KableKillersBackup.status.value.hasDeviceKey
            ) {
                val backup = verifyRestorePin(pin)
                if (backup.unlocked) return backup
            }
            local
        }
    }

    private suspend fun verifyRestorePin(pin: String): PinVerifyResult =
        com.nuvio.app.core.backup.KableKillersBackup.unlockWithRestorePin(pin).fold(
            onSuccess = { PinVerifyResult(unlocked = true) },
            onFailure = { error ->
                val tooMany = (error as? com.nuvio.app.core.backup.KableKillersBackupException)?.status == 429
                PinVerifyResult(unlocked = false, retryAfterSeconds = if (tooMany) 900 else 0)
            },
        )

    suspend fun setPin(profileIndex: Int, pin: String, currentPin: String? = null): PinVerifyResult {
        if (KableKillersAccountPolicy.isHouseholdProfile(profileIndex)) {
            return setHouseholdPin(profileIndex, pin, currentPin)
        }
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_set_requires_internet))
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("set_profile_pin", params)
            pullProfiles()
            rememberVerifiedPin(profileIndex = profileIndex, pin = pin)
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to set pin" }
        }.getOrElse {
            PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_set_failed))
        }
    }

    suspend fun clearPin(profileIndex: Int, currentPin: String? = null): PinVerifyResult {
        if (KableKillersAccountPolicy.isHouseholdProfile(profileIndex)) {
            return clearHouseholdPin(profileIndex, currentPin)
        }
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_clear_requires_internet))
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin", params)
            pullProfiles()
            ProfilePinCacheStorage.removePayload(profileIndex)
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin" }
        }.getOrElse {
            PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_clear_failed))
        }
    }

    suspend fun clearPinWithPassword(profileIndex: Int, accountPassword: String) {
        runCatching {
            val params = buildJsonObject {
                put("p_account_password", accountPassword)
                put("p_profile_id", profileIndex)
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin_with_account_password", params)
            pullProfiles()
            ProfilePinCacheStorage.removePayload(profileIndex)
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin with password" }
        }
    }

    suspend fun pullProfileLocks(): List<ProfileLockState> {
        return runCatching {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_locks")
            result.decodeList<ProfileLockState>()
        }.getOrElse { e ->
            log.e(e) { "Failed to pull profile locks" }
            emptyList()
        }
    }

    private fun applyPayloadsLocally(payloads: List<ProfilePushPayload>) {
        val authState = AuthRepository.state.value as? AuthState.Authenticated ?: return
        // Keep each profile's lock state: the payload has no PIN fields, and
        // dropping them would leave Profile 1 (or a parent's profile) unlocked.
        val existingByIndex = _state.value.profiles.associateBy { it.profileIndex }
        val profiles = payloads.map { p ->
            val existing = existingByIndex[p.profileIndex]
            NuvioProfile(
                pinEnabled = existing?.pinEnabled ?: false,
                updatedAt = existing?.updatedAt.orEmpty(),
                id = "",
                userId = authState.userId,
                profileIndex = p.profileIndex,
                name = p.name,
                avatarColorHex = p.avatarColorHex,
                avatarId = p.avatarId,
                avatarUrl = p.avatarUrl,
                profileBackgroundId = p.profileBackgroundId,
                profileBackgroundUrl = p.profileBackgroundUrl,
                usesPrimaryAddons = p.usesPrimaryAddons,
                usesPrimaryPlugins = p.usesPrimaryPlugins,
            )
        }.sortedBy { it.profileIndex }
        _state.value = _state.value.copy(
            profiles = profiles,
            isLoaded = true,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
        )
        if (_state.value.activeProfile != null) {
            activeProfileIndex = _state.value.activeProfile!!.profileIndex
        }
        syncPinCache(profiles)
        persist()
    }

    private fun decodeStoredPayload(): StoredProfilePayload? {
        val payload = ProfileStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return null

        return runCatching {
            json.decodeFromString<StoredProfilePayload>(payload)
        }.getOrNull()
    }

    private fun applyStoredPayload(stored: StoredProfilePayload) {
        val profiles = stored.profiles.sortedBy { it.profileIndex }
        activeProfileIndex = stored.activeProfileIndex
        _state.value = ProfileState(
            profiles = profiles,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
            isLoaded = profiles.isNotEmpty(),
            hasEverSelectedProfile = stored.hasEverSelectedProfile,
            rememberLastProfileEnabled = stored.rememberLastProfileEnabled,
        )
        _state.value.activeProfile?.let { activeProfileIndex = it.profileIndex }
        syncPinCache(profiles)
    }

    // ---- Household (customer) profile PINs: saved on this phone only ----
    //
    // The cloud stores PINs by profile number for the whole login, so a cloud PIN
    // on one customer's "Profile 2" would lock every customer's Profile 2. On the
    // shared customer login, household PINs are kept on the phone instead (salted
    // hash, under a separate key range from the cloud PIN cache), and the owner's
    // Profile 1 PIN also unlocks them as a recovery path.

    private fun householdPinKey(profileIndex: Int): Int = HOUSEHOLD_PIN_KEY_OFFSET + profileIndex

    private val householdPinFailures = mutableMapOf<Int, Int>()
    private val householdPinLockedUntil = mutableMapOf<Int, Long>()

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun checkHouseholdPin(profileIndex: Int, pin: String): PinVerifyResult {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        val raw = ProfilePinCacheStorage.loadPayload(householdPinKey(profileIndex)).orEmpty().trim()
        if (profile?.pinEnabled != true && raw.isEmpty()) return PinVerifyResult(unlocked = true)
        val until = householdPinLockedUntil[profileIndex] ?: 0L
        val now = nowMs()
        if (until > now) {
            val seconds = ((until - now + 999) / 1000).toInt()
            return PinVerifyResult(unlocked = false, retryAfterSeconds = seconds, message = localizedString(Res.string.pin_incorrect))
        }
        val cached = runCatching { json.decodeFromString<CachedProfilePinPayload>(raw) }.getOrNull()
        val ok = cached != null &&
            hashProfilePin(profileIndex = profileIndex, salt = cached.salt, pin = pin) == cached.digest
        if (ok) {
            householdPinFailures.remove(profileIndex)
            return PinVerifyResult(unlocked = true)
        }
        val count = (householdPinFailures[profileIndex] ?: 0) + 1
        householdPinFailures[profileIndex] = count
        if (count >= HOUSEHOLD_PIN_MAX_ATTEMPTS) {
            householdPinFailures.remove(profileIndex)
            householdPinLockedUntil[profileIndex] = now + HOUSEHOLD_PIN_LOCKOUT_MS
        }
        return PinVerifyResult(unlocked = false, message = localizedString(Res.string.pin_incorrect))
    }

    private suspend fun verifyHouseholdPinOrOwner(profileIndex: Int, pin: String): PinVerifyResult {
        val local = checkHouseholdPin(profileIndex, pin)
        if (local.unlocked) return local
        val primary = _state.value.profiles.find { it.profileIndex == KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX }
        if (primary?.pinEnabled == true && verifyPin(KableKillersAccountPolicy.PRIMARY_PROFILE_INDEX, pin).unlocked) {
            return PinVerifyResult(unlocked = true)
        }
        return local
    }

    private fun setHouseholdLockFlag(profileIndex: Int, enabled: Boolean) {
        val profiles = _state.value.profiles.map {
            if (it.profileIndex == profileIndex) it.copy(pinEnabled = enabled) else it
        }
        _state.value = _state.value.copy(
            profiles = profiles,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: _state.value.activeProfile,
        )
        persist()
    }

    private suspend fun setHouseholdPin(profileIndex: Int, pin: String, currentPin: String?): PinVerifyResult {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
            ?: return PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_set_failed))
        if (profile.pinEnabled) {
            if (currentPin == null || !verifyHouseholdPinOrOwner(profileIndex, currentPin).unlocked) {
                return PinVerifyResult(unlocked = false, message = localizedString(Res.string.pin_incorrect))
            }
        }
        val salt = generateProfilePinSalt()
        val payload = CachedProfilePinPayload(
            salt = salt,
            digest = hashProfilePin(profileIndex = profileIndex, salt = salt, pin = pin),
            profileUpdatedAt = "",
        )
        ProfilePinCacheStorage.savePayload(householdPinKey(profileIndex), json.encodeToString(payload))
        householdPinFailures.remove(profileIndex)
        householdPinLockedUntil.remove(profileIndex)
        setHouseholdLockFlag(profileIndex, true)
        return PinVerifyResult(unlocked = true)
    }

    private suspend fun clearHouseholdPin(profileIndex: Int, currentPin: String?): PinVerifyResult {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        if (profile?.pinEnabled == true) {
            if (currentPin == null || !verifyHouseholdPinOrOwner(profileIndex, currentPin).unlocked) {
                return PinVerifyResult(unlocked = false, message = localizedString(Res.string.pin_incorrect))
            }
        }
        ProfilePinCacheStorage.removePayload(householdPinKey(profileIndex))
        setHouseholdLockFlag(profileIndex, false)
        return PinVerifyResult(unlocked = true)
    }

    private fun rememberVerifiedPin(profileIndex: Int, pin: String) {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        val salt = generateProfilePinSalt()
        val payload = CachedProfilePinPayload(
            salt = salt,
            digest = hashProfilePin(profileIndex = profileIndex, salt = salt, pin = pin),
            profileUpdatedAt = profile?.updatedAt.orEmpty(),
        )
        ProfilePinCacheStorage.savePayload(profileIndex, json.encodeToString(payload))
    }

    private fun verifyPinLocally(profileIndex: Int, pin: String): PinVerifyResult {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        if (profile?.pinEnabled != true) {
            return PinVerifyResult(unlocked = true)
        }

        val payload = ProfilePinCacheStorage.loadPayload(profileIndex).orEmpty().trim()
        if (payload.isEmpty()) {
            return PinVerifyResult(
                unlocked = false,
                message = localizedString(Res.string.profile_pin_offline_verification_requires_online),
            )
        }

        val cached = runCatching {
            json.decodeFromString<CachedProfilePinPayload>(payload)
        }.getOrNull() ?: return PinVerifyResult(
            unlocked = false,
            message = localizedString(Res.string.profile_pin_offline_verification_requires_online),
        )

        if (
            cached.profileUpdatedAt.isNotBlank() &&
            profile.updatedAt.isNotBlank() &&
            cached.profileUpdatedAt != profile.updatedAt
        ) {
            ProfilePinCacheStorage.removePayload(profileIndex)
            return PinVerifyResult(
                unlocked = false,
                message = localizedString(Res.string.profile_pin_changed_requires_refresh),
            )
        }

        val digest = hashProfilePin(profileIndex = profileIndex, salt = cached.salt, pin = pin)
        return if (digest == cached.digest) {
            PinVerifyResult(unlocked = true)
        } else {
            PinVerifyResult(unlocked = false, message = localizedString(Res.string.pin_incorrect))
        }
    }

    private fun syncPinCache(profiles: List<NuvioProfile>) {
        val profilesByIndex = profiles.associateBy { it.profileIndex }
        for (profileIndex in 1..MAX_PROFILES) {
            val profile = profilesByIndex[profileIndex]
            if (profile == null || !profile.pinEnabled) {
                ProfilePinCacheStorage.removePayload(profileIndex)
                continue
            }

            val raw = ProfilePinCacheStorage.loadPayload(profileIndex).orEmpty().trim()
            if (raw.isEmpty()) continue

            val cached = runCatching {
                json.decodeFromString<CachedProfilePinPayload>(raw)
            }.getOrNull() ?: run {
                ProfilePinCacheStorage.removePayload(profileIndex)
                continue
            }

            if (
                cached.profileUpdatedAt.isNotBlank() &&
                profile.updatedAt.isNotBlank() &&
                cached.profileUpdatedAt != profile.updatedAt
            ) {
                ProfilePinCacheStorage.removePayload(profileIndex)
            }
        }
    }

    private fun persist() {
        val authState = AuthRepository.state.value as? AuthState.Authenticated ?: return
        val state = _state.value
        ProfileStorage.savePayload(
            json.encodeToString(
                StoredProfilePayload(
                    userId = authState.userId,
                    activeProfileIndex = activeProfileIndex,
                    hasEverSelectedProfile = state.hasEverSelectedProfile,
                    rememberLastProfileEnabled = state.rememberLastProfileEnabled,
                    profiles = state.profiles,
                ),
            ),
        )
    }
}

@kotlinx.serialization.Serializable
data class ProfileLockState(
    @kotlinx.serialization.SerialName("profile_index") val profileIndex: Int,
    @kotlinx.serialization.SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @kotlinx.serialization.SerialName("pin_locked_until") val pinLockedUntil: String? = null,
)
