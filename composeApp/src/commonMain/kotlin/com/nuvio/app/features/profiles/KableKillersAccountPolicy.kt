package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState

/**
 * Kable Killers profile storage rules (same as the TV app).
 *
 * - The shared customer login (kablekillers6@gmail.com) keeps household profiles
 *   on the phone they were made on. Only Profile 1 talks to the cloud; other
 *   profiles copy Profile 1's add-ons, rows and settings and never upload them.
 * - Any other signed-in account keeps every profile in the cloud, like normal Nuvio.
 * - On every signed-in account, profiles other than Profile 1 always match
 *   Profile 1: add-ons, plugins, rows (order and hidden), collections, settings,
 *   provider logins and media servers are read from Profile 1 and never uploaded
 *   under the profile's own index.
 * - Guest (anonymous) sessions keep their existing local-only behaviour.
 *
 * A signed-in account whose email is unknown is treated as the customer
 * account, so a customer phone can never upload its local profiles.
 */
object KableKillersAccountPolicy {
    const val SHARED_CUSTOMER_EMAIL = "kablekillers6@gmail.com"
    const val PRIMARY_PROFILE_INDEX = 1

    fun isHouseholdAccount(state: AuthState = AuthRepository.state.value): Boolean {
        val account = state as? AuthState.Authenticated ?: return false
        if (account.isAnonymous) return false
        val email = account.email?.trim().orEmpty()
        return email.isEmpty() || email.equals(SHARED_CUSTOMER_EMAIL, ignoreCase = true)
    }

    /** True when [profileIndex] is a device-local household profile on the customer account. */
    fun isHouseholdProfile(
        profileIndex: Int = ProfileRepository.activeProfileId,
        state: AuthState = AuthRepository.state.value,
    ): Boolean = profileIndex != PRIMARY_PROFILE_INDEX && isHouseholdAccount(state)

    /** True when [profileIndex] mirrors Profile 1's whole setup (any signed-in account). */
    fun inheritsPrimaryConfig(
        profileIndex: Int = ProfileRepository.activeProfileId,
        state: AuthState = AuthRepository.state.value,
    ): Boolean {
        if (profileIndex == PRIMARY_PROFILE_INDEX) return false
        val account = state as? AuthState.Authenticated ?: return false
        return !account.isAnonymous
    }

    /** Cloud profile id to read configuration from. Created profiles read Profile 1. */
    fun remoteReadProfileId(profileIndex: Int = ProfileRepository.activeProfileId): Int =
        if (inheritsPrimaryConfig(profileIndex)) PRIMARY_PROFILE_INDEX else profileIndex

    /** Created profiles never upload configuration under their own index. */
    fun canPushConfiguration(profileIndex: Int = ProfileRepository.activeProfileId): Boolean =
        !inheritsPrimaryConfig(profileIndex)
}
