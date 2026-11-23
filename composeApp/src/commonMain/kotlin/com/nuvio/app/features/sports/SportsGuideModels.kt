package com.nuvio.app.features.sports

/**
 * Normalized data for a sports-only linear channel guide.
 *
 * Channel streams are resolved separately from schedule metadata. A guide entry must not
 * imply that the app can play a channel unless an installed source supplies a stream.
 */
internal data class SportsGuideChannel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val sourceNames: Set<String> = emptySet(),
    val playable: Boolean = false,
)

internal data class SportsGuideProgram(
    val channelId: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val description: String? = null,
) {
    init {
        require(endEpochMillis > startEpochMillis) { "Program end must follow start" }
    }
}

internal object SportsGuideSchedule {
    /** Display only actual EPG entries. Missing schedule data is not fabricated. */
    fun nowAndNext(
        channelId: String,
        atEpochMillis: Long,
        programs: List<SportsGuideProgram>,
    ): Pair<SportsGuideProgram?, SportsGuideProgram?> {
        val channelPrograms = programs.asSequence()
            .filter { it.channelId == channelId }
            .sortedBy { it.startEpochMillis }
            .toList()
        val current = channelPrograms.firstOrNull {
            it.startEpochMillis <= atEpochMillis && atEpochMillis < it.endEpochMillis
        }
        val next = channelPrograms.firstOrNull {
            it.startEpochMillis > atEpochMillis &&
                (current == null || it.startEpochMillis >= current.endEpochMillis)
        }
        return current to next
    }

    fun channelFromProviderName(
        providerName: String,
        logoUrl: String? = null,
    ): SportsGuideChannel? {
        val identity = SportsChannelRegistry.identify(providerName) ?: return null
        // Provider-supplied artwork is allowed as a candidate, but the UI should
        // display it only after source and branding checks.
        return SportsGuideChannel(
            id = identity.id,
            name = identity.displayName,
            logoUrl = logoUrl?.takeIf { it.startsWith("https://") },
            sourceNames = setOf(providerName),
        )
    }
}
