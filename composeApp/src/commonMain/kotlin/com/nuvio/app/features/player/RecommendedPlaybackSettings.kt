package com.nuvio.app.features.player

/**
 * One-press "recommended" playback setup: automatic engine switch and source fallback on, with
 * sensible limits. The values it replaces are kept so the change can be undone in this session.
 */
object RecommendedPlaybackSettings {
    private data class Snapshot(
        val autoSwitchEngine: Boolean,
        val fallbackOn: Boolean,
        val fallbackMaxSources: Int,
        val startupTimeoutSeconds: Int,
    )

    private val recommended = Snapshot(
        autoSwitchEngine = true,
        fallbackOn = true,
        fallbackMaxSources = DEFAULT_SOURCE_FALLBACK_MAX_SOURCES,
        startupTimeoutSeconds = DEFAULT_SOURCE_STARTUP_TIMEOUT_SECONDS,
    )

    private var previous: Snapshot? = null

    private fun PlayerSettingsUiState.snapshot() = Snapshot(
        autoSwitchEngine = autoSwitchPlaybackEngineOnError,
        fallbackOn = streamAutoPlayFallbackOnFailure,
        fallbackMaxSources = streamAutoPlayFallbackMaxSources,
        startupTimeoutSeconds = streamAutoPlayFallbackStartupTimeoutSeconds,
    )

    fun isApplied(state: PlayerSettingsUiState): Boolean = state.snapshot() == recommended

    fun canUndo(state: PlayerSettingsUiState): Boolean = previous != null && isApplied(state)

    fun apply(state: PlayerSettingsUiState) {
        if (isApplied(state)) return
        previous = state.snapshot()
        write(recommended)
    }

    fun undo() {
        val old = previous ?: return
        previous = null
        write(old)
    }

    private fun write(values: Snapshot) {
        PlayerSettingsRepository.setAutoSwitchPlaybackEngineOnError(values.autoSwitchEngine)
        PlayerSettingsRepository.setStreamAutoPlayFallbackOnFailure(values.fallbackOn)
        PlayerSettingsRepository.setStreamAutoPlayFallbackMaxSources(values.fallbackMaxSources)
        PlayerSettingsRepository.setStreamAutoPlayFallbackStartupTimeoutSeconds(values.startupTimeoutSeconds)
    }
}
