package com.nuvio.app.features.sports

/**
 * A guide entry represents an actual channel or event supplied by an installed add-on.
 * Schedule data is optional: never invent program names or broadcast times.
 */
data class SportsGuideEntry(
    val source: SportsCatalogMatch,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val programTitle: String? = null,
)

internal object SportsGuide {
    fun fromChannels(channels: List<SportsCatalogMatch>): List<SportsGuideEntry> =
        channels.distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
            .map { SportsGuideEntry(source = it) }

    fun current(entries: List<SportsGuideEntry>, nowEpochMillis: Long): List<SportsGuideEntry> =
        entries.filter { entry ->
            (entry.startEpochMillis == null || entry.startEpochMillis <= nowEpochMillis) &&
                (entry.endEpochMillis == null || nowEpochMillis < entry.endEpochMillis)
        }
}
