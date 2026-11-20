package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

expect object PluginRepository {
    val uiState: StateFlow<PluginsUiState>

    fun initialize()

    fun onProfileChanged(profileId: Int)

    fun clearLocalState()

    suspend fun pullFromServer(profileId: Int)

    /** Kable Killers backup restore of Profile 1's plugin rows. Returns how many were restored. */
    suspend fun restoreFromBackup(profileId: Int, rows: JsonArray): Int

    suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult

    fun removeRepository(manifestUrl: String)

    fun refreshAll()

    fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean = false)

    fun toggleScraper(scraperId: String, enabled: Boolean)

    fun setPluginsEnabled(enabled: Boolean)

    fun setGroupStreamsByRepository(enabled: Boolean)

    fun setLocalPluginSearchPaused(paused: Boolean)

    fun getEnabledScrapersForType(type: String): List<PluginScraper>

    suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>>

    suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>>
}