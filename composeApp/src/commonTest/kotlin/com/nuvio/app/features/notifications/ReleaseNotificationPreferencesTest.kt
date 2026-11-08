package com.nuvio.app.features.notifications

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNotificationPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun existingSavedSettingsKeepEpisodeAlertsAndDoNotEnableMovieAlerts() {
        val legacy = """{"enabled":true,"followedShows":[{"contentId":"tt123","contentType":"series","followedOnIsoDate":"2026-01-01"}]}"""
        val restored = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(legacy)
        assertTrue(restored.enabled)
        assertTrue(restored.episodeAlertsEnabled)
        assertFalse(restored.movieAlertsEnabled)
        assertEquals(1, restored.followedShows.size)
    }

    @Test
    fun independentPreferencesSurviveSerialization() {
        val original = StoredEpisodeReleaseNotificationsPayload(
            enabled = true,
            episodeAlertsEnabled = false,
            movieAlertsEnabled = true,
        )
        val restored = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(
            json.encodeToString(StoredEpisodeReleaseNotificationsPayload.serializer(), original)
        )
        assertFalse(restored.episodeAlertsEnabled)
        assertTrue(restored.movieAlertsEnabled)
    }

    @Test
    fun movieDatesMustBePrecise() {
        assertNull(releaseDateIso("2027"))
        assertEquals("2027-05-16", releaseDateIso("2027-05-16"))
        assertNull(releaseDateIso("2027-02-30"))
    }

    @Test
    fun movieNotificationIdsAreProfileScopedAndStable() {
        val a = buildMovieReleaseNotificationId(1, "tt123", "2027-05-16")
        assertEquals(a, buildMovieReleaseNotificationId(1, "tt123", "2027-05-16"))
        assertTrue(a != buildMovieReleaseNotificationId(2, "tt123", "2027-05-16"))
    }
}
