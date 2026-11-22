package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KableKillersAccountPolicyTest {
    private fun account(email: String?) = AuthState.Authenticated("u", email, isAnonymous = false)

    @Test
    fun customerAccountUsesHouseholdProfiles() {
        assertTrue(KableKillersAccountPolicy.isHouseholdAccount(account("kablekillers6@gmail.com")))
        assertTrue(KableKillersAccountPolicy.isHouseholdAccount(account(" KableKillers6@Gmail.com ")))
        assertTrue(KableKillersAccountPolicy.isHouseholdProfile(2, account("kablekillers6@gmail.com")))
        assertFalse(KableKillersAccountPolicy.isHouseholdProfile(1, account("kablekillers6@gmail.com")))
    }

    @Test
    fun personalAccountKeepsProfilesInCloud() {
        assertFalse(KableKillersAccountPolicy.isHouseholdAccount(account("millertime42085@gmail.com")))
        assertFalse(KableKillersAccountPolicy.isHouseholdProfile(3, account("millertime42085@gmail.com")))
    }

    @Test
    fun unknownEmailIsTreatedAsCustomer() {
        assertTrue(KableKillersAccountPolicy.isHouseholdAccount(account(null)))
        assertFalse(KableKillersAccountPolicy.isHouseholdAccount(AuthState.Authenticated("u", null, isAnonymous = true)))
        assertFalse(KableKillersAccountPolicy.isHouseholdAccount(AuthState.Unauthenticated))
    }

    @Test
    fun createdProfilesMirrorPrimaryOnEveryAccount() {
        assertTrue(KableKillersAccountPolicy.inheritsPrimaryConfig(2, account("millertime42085@gmail.com")))
        assertTrue(KableKillersAccountPolicy.inheritsPrimaryConfig(2, account("kablekillers6@gmail.com")))
        assertFalse(KableKillersAccountPolicy.inheritsPrimaryConfig(1, account("millertime42085@gmail.com")))
        assertFalse(KableKillersAccountPolicy.inheritsPrimaryConfig(2, AuthState.Authenticated("u", null, isAnonymous = true)))
    }

    @Test
    fun watchHistoryRuleFollowsTheLogin() {
        val customer = account("kablekillers6@gmail.com")
        val owner = account("millertime42085@gmail.com")
        assertFalse(KableKillersAccountPolicy.isHouseholdProfile(1, customer))
        (2..6).forEach { assertTrue(KableKillersAccountPolicy.isHouseholdProfile(it, customer)) }
        (1..6).forEach { assertFalse(KableKillersAccountPolicy.isHouseholdProfile(it, owner)) }
    }
}
